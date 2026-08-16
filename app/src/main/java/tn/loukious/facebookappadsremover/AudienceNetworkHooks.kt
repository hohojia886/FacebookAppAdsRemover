package tn.loukious.facebookappadsremover

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal val AUDIENCE_NETWORK_REWARD_COMPLETION_METHOD_NAMES = setOf(
    "onRewardServerSuccess",
    "onRewardServerFailed",
    "onRewardedVideoCompleted",
    "onRewardedVideoClosed",
    "onRewardServerResponse"
)

internal val AUDIENCE_NETWORK_CLOSE_LISTENER_CLASS_NAMES = setOf(
    "com.facebook.ads.internal.api.AdCloseListener",
    "com.facebook.ads.internal.api.RewardedVideoAdApi\$RewardedVideoAdListener",
    "com.facebook.ads.RewardedVideoAdListener"
)

internal val AUDIENCE_NETWORK_FOCUSED_DIAGNOSTIC_CLASS_NAMES = setOf(
    "com.facebook.ads.internal.api.AdViewApi",
    "com.facebook.ads.internal.api.RewardedVideoAdApi",
    "com.facebook.ads.internal.api.InterstitialAdApi",
    "com.facebook.ads.AdView",
    "com.facebook.ads.RewardedVideoAd",
    "com.facebook.ads.InterstitialAd"
)

internal data class AudienceNetworkGraphNode(
    val value: Any,
    val path: String,
    val depth: Int
)

internal fun hookAudienceNetworkViewDiagnostics(module: XposedModule) {
    if (!ENABLE_AUDIENCE_NETWORK_VIEW_DIAGNOSTICS || audienceNetworkViewDiagnosticsInstalled.getAndIncrement() != 0) return

    val viewClass = View::class.java
    (viewClass.declaredMethods + viewClass.methods)
        .filter { it.name == "onAttachedToWindow" && it.parameterCount == 0 }
        .distinctBy { methodSignature(it) }
        .forEach { method ->
            module.hook(method).intercept { chain ->
                val res = chain.proceed()
                val view = chain.thisObject as? View ?: return@intercept res
                if (shouldLogAudienceNetworkViewDiagnostic(view, null)) {
                    val activity = runCatching { view.context as? Activity }.getOrNull()
                    activity?.let { dumpAudienceNetworkActivityState(it, "view attach ${view.javaClass.name}") }
                }
                res
            }
        }

    val activityClass = Activity::class.java
    (activityClass.declaredMethods + activityClass.methods)
        .filter { it.name == "onCreate" && it.parameterCount == 1 && it.parameterTypes[0] == Bundle::class.java }
        .distinctBy { methodSignature(it) }
        .forEach { method ->
            module.hook(method).intercept { chain ->
                val res = chain.proceed()
                val activity = chain.thisObject as? Activity ?: return@intercept res
                if (activity.javaClass.name == AUDIENCE_NETWORK_ACTIVITY_CLASS) {
                    dumpAudienceNetworkActivityState(activity, "activity create")
                }
                res
            }
        }

    Logger.i(TAG, "Audience Network view diagnostics active")
}

internal fun dumpAudienceNetworkActivityState(activity: Activity, reason: String) {
    if (audienceNetworkActivityStateDumps.containsKey(activity)) return
    audienceNetworkActivityStateDumps[activity] = System.currentTimeMillis()

    Logger.i(TAG, "--- Audience Network Activity State Dump ($reason) ---")
    Logger.i(TAG, "Activity: ${activity.javaClass.name} tasksId=${activity.taskId}")
    dumpAudienceNetworkIntentExtras(activity.intent, "Activity Intent")
    dumpAudienceNetworkViewState(activity, "Activity View Tree")
    
    runCatching {
        val fragments = invokeMethodByName(activity, "getSupportFragmentManager") ?: invokeMethodByName(activity, "getFragmentManager")
        fragments?.let { Logger.i(TAG, "Fragments: ${it.javaClass.name}") }
    }
    
    val adObjects = findAudienceNetworkRewardListeners(activity)
    adObjects.forEach { obj ->
        Logger.i(TAG, "Found relevant object in activity: ${obj.javaClass.name} hash=${System.identityHashCode(obj)}")
    }
    Logger.i(TAG, "--- End State Dump ---")
}

internal fun dumpAudienceNetworkIntentExtras(intent: Intent?, label: String) {
    if (intent == null) return
    val extras = intent.extras ?: return
    Logger.i(TAG, "$label extras keys=${extras.keySet().joinToString()}")
    extras.keySet().forEach { key ->
        val value = extras.get(key)
        Logger.i(TAG, "  $key = ${if (value is Bundle) "Bundle{...}" else value?.toString()}")
    }
}

internal fun dumpAudienceNetworkViewState(activity: Activity, source: String) {
    val root = activity.window?.decorView ?: return
    val queue = ArrayDeque<Pair<View, Int>>()
    queue.add(root to 0)
    
    var count = 0
    while (queue.isNotEmpty() && count < AUDIENCE_NETWORK_STATE_DUMP_LIMIT) {
        val (view, depth) = queue.removeFirst()
        if (shouldDescribeAudienceNetworkViewInTree(view)) {
            Logger.i(TAG, "$source depth=$depth ${describeAudienceNetworkView(view)}")
        }
        
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                queue.add(view.getChildAt(i) to depth + 1)
            }
        }
        count++
    }
}

internal fun tryHookAudienceNetworkDiagnosticObjectClass(module: XposedModule, clazz: Class<*>, source: String) {
    if (!audienceNetworkViewListenerClassesHooked.add(clazz.name)) return
    
    Logger.i(TAG, "Hooking interesting Audience Network class: ${clazz.name} from $source")
    (clazz.declaredMethods + clazz.methods).forEach { method ->
        if (isAudienceNetworkViewListenerDiagnosticMethod(method)) {
            module.hook(method).intercept { chain ->
                if (shouldLogAudienceNetworkListenerCall(method, chain.args.toTypedArray())) {
                    Logger.i(TAG, "Audience Network Call: ${method.name}(${chain.args.joinToString()}) on ${chain.thisObject?.javaClass?.name}")
                }
                chain.proceed()
            }
        }
    }
}

internal fun tryHookAudienceNetworkViewListenerClass(module: XposedModule, clazz: Class<*>, source: String) {
    if (!audienceNetworkViewListenerClassesHooked.add(clazz.name)) return

    Logger.i(TAG, "Hooking Audience Network view listener: ${clazz.name} from $source")
    (clazz.declaredMethods + clazz.methods).forEach { method ->
        if (isAudienceNetworkViewListenerDiagnosticMethod(method)) {
            module.hook(method).intercept { chain ->
                val view = chain.args.firstOrNull { it is View } as? View
                if (shouldLogAudienceNetworkViewDiagnostic(view, chain.args.toTypedArray())) {
                    Logger.i(TAG, "Audience Network View Event: ${method.name} view=${view?.javaClass?.name} args=${chain.args.joinToString()}")
                    view?.let { Logger.i(TAG, "  View Details: ${describeAudienceNetworkView(it)}") }
                }
                chain.proceed()
            }
        }
    }
}

internal fun isAudienceNetworkViewListenerDiagnosticMethod(method: Method): Boolean {
    val name = method.name.lowercase()
    return name.contains("ad") && (
        name.contains("click") || 
        name.contains("load") || 
        name.contains("error") || 
        name.contains("impression") || 
        name.contains("close") ||
        name.contains("finish")
    )
}

internal fun shouldLogAudienceNetworkListenerCall(method: Method, args: Array<Any?>?): Boolean {
    if (!ENABLE_AUDIENCE_NETWORK_VIEW_DIAGNOSTICS) return false
    return method.name.lowercase().contains("error") || (args?.any { it != null } == true)
}

internal fun shouldLogAudienceNetworkViewDiagnostic(view: View?, args: Array<Any?>?): Boolean {
    if (!ENABLE_AUDIENCE_NETWORK_VIEW_DIAGNOSTICS) return false
    if (view == null) return args?.any { it != null } == true
    val name = view.javaClass.name.lowercase()
    return name.contains("ad") || name.contains("fb") || name.contains("native")
}

internal fun shouldDescribeAudienceNetworkViewInTree(view: View): Boolean {
    if (view is ViewGroup && view.childCount > 0) return true
    if (view is TextView && view.text.isNotBlank()) return true
    val name = view.javaClass.name.lowercase()
    return name.contains("ad") || name.contains("button") || name.contains("image") || view.contentDescription?.isNotBlank() == true
}

internal fun describeAudienceNetworkView(view: View): String {
    val out = StringBuilder()
    out.append(view.javaClass.name)
    out.append(" id=0x${Integer.toHexString(view.id)}")
    out.append(" vis=${when(view.visibility) { View.VISIBLE -> "V"; View.INVISIBLE -> "I"; else -> "G" }}")
    out.append(" bounds=[${view.left},${view.top}-${view.right},${view.bottom}]")
    if (view is TextView) out.append(" text=\"${view.text.take(32)}\"")
    view.contentDescription?.let { out.append(" desc=\"$it\"") }
    
    val marker = audienceNetworkViewMarker(view)
    if (marker.isNotBlank()) out.append(" marker=[$marker]")
    
    return out.toString()
}

internal fun audienceNetworkViewMarker(view: View): String {
    val tokens = ArrayList<String>()
    if (view.isClickable) tokens.add("clickable")
    if (view.isFocusable) tokens.add("focusable")
    
    val name = view.javaClass.name.lowercase()
    if (name.contains("close") || name.contains("skip") || name.contains("exit")) tokens.add("exit-candidate")
    
    return tokens.joinToString(",")
}

internal fun audienceNetworkParentPath(view: View): String {
    val path = StringBuilder()
    var current: View? = view
    var depth = 0
    while (current != null && depth < 8) {
        path.insert(0, "/${current.javaClass.simpleName}")
        current = current.parent as? View
        depth++
    }
    return path.toString()
}

internal fun shouldHookAudienceNetworkListenerClass(className: String): Boolean {
    return className.contains("com.facebook.ads") && 
        (className.contains("Listener") || className.contains("Callback"))
}

internal fun isAudienceNetworkFinalExitListener(className: String): Boolean {
    return className.contains("RewardedVideoAdListener") || className.contains("InterstitialAdListener")
}

internal fun isAudienceNetworkClosePromptListener(className: String): Boolean {
    return className.contains("AdCloseListener")
}

internal fun scheduleAudienceNetworkRegisteredExitClick(view: View, source: String) {
    if (scheduledAudienceNetworkExitViews.containsKey(view)) return
    scheduledAudienceNetworkExitViews[view] = System.currentTimeMillis()
    
    Logger.i(TAG, "Scheduling automatic exit click for Audience Network view ($source): ${describeAudienceNetworkView(view)}")
    view.postDelayed({
        runCatching {
            if (view.isAttachedToWindow && view.visibility == View.VISIBLE && isAudienceNetworkFinalExitViewReady(view)) {
                Logger.i(TAG, "Executing automatic exit click for Audience Network")
                view.performClick()
            } else {
                Logger.i(TAG, "Automatic exit click cancelled: view no longer eligible")
            }
        }
    }, 500L)
}

internal fun isAudienceNetworkFinalExitViewReady(view: View): Boolean {
    if (!view.isShown || view.alpha < 0.8f) return false
    val rect = android.graphics.Rect()
    if (!view.getGlobalVisibleRect(rect)) return false
    return rect.width() > 10 && rect.height() > 10
}

internal fun String.isFocusedAudienceNetworkClassName(): Boolean {
    return this in AUDIENCE_NETWORK_FOCUSED_DIAGNOSTIC_CLASS_NAMES
}

internal fun audienceNetworkInterestingMethodsSummary(clazz: Class<*>): String {
    val interesting = (clazz.declaredMethods + clazz.methods)
        .filter { !Modifier.isStatic(it.modifiers) && it.parameterCount <= 1 }
        .map { it.name }
        .filter { name -> 
            val lower = name.lowercase()
            lower.contains("ad") || lower.contains("load") || lower.contains("show") || lower.contains("close")
        }
        .distinct()
        .take(8)
    return interesting.joinToString()
}

internal fun String.hasAudienceNetworkViewSignal(): Boolean {
    val lower = this.lowercase()
    return lower.contains("audiencenetwork") || 
        lower.contains("adchoices") || 
        lower.contains("fbinstant") ||
        lower.contains("sponsored")
}

internal fun hookAudienceNetworkRewardFallbacks(module: XposedModule, classLoader: ClassLoader) {
    if (audienceNetworkRewardHooksInstalled.getAndIncrement() != 0) return

    val loadMethods = mutableListOf<Method>()
    val classNames = listOf(
        "com.facebook.ads.RewardedVideoAd",
        "com.facebook.ads.RewardedInterstitialAd",
        "com.facebook.ads.InterstitialAd"
    )
    
    classNames.forEach { className ->
        val clazz = runCatching { classLoader.loadClass(className) }.getOrNull() ?: return@forEach
        (clazz.declaredMethods + clazz.methods)
            .filter { isAudienceNetworkRewardLoadMethod(clazz, it) }
            .forEach { method ->
                module.hook(method).intercept { chain ->
                    val adObject = chain.thisObject ?: return@intercept chain.proceed()
                    Logger.i(TAG, "Observed Audience Network ad load: ${adObject.javaClass.name}")
                    tryHookAudienceNetworkRewardClass(module, adObject.javaClass)
                    chain.proceed()
                }
                loadMethods.add(method)
            }
    }

    if (loadMethods.isNotEmpty()) {
        Logger.i(TAG, "Audience Network reward load-path hooks installed")
    }
}

internal fun tryHookAudienceNetworkRewardClass(module: XposedModule, clazz: Class<*>) {
    if (!audienceNetworkRewardClassesHooked.add(clazz.name)) return

    Logger.i(TAG, "Hooking Audience Network reward-capable class: ${clazz.name}")
    (clazz.declaredMethods + clazz.methods).forEach { method ->
        if (isAudienceNetworkRewardShowMethod(clazz, method)) {
            module.hook(method).intercept { chain ->
                val adObject = chain.thisObject ?: return@intercept chain.proceed()
                val activity = chain.args.firstOrNull { it is Activity } as? Activity
                
                Logger.i(TAG, "Blocked Audience Network ad show: ${adObject.javaClass.name}")
                completeAudienceNetworkRewardObject(adObject, "blocked show")
                
                activity?.let { forceAudienceNetworkRewardCompletion(it, "intercepted show") }
                null // Block the ad from showing
            }
        } else if (isAudienceNetworkRewardListenerRegistrationMethod(method)) {
            module.hook(method).intercept { chain ->
                rememberAudienceNetworkRewardListeners(chain.thisObject, chain.args.toTypedArray(), method)
                chain.proceed()
            }
        }
    }
}

internal fun isAudienceNetworkRewardRelevantClass(className: String): Boolean {
    val lower = className.lowercase()
    return lower.contains("com.facebook.ads") && 
        (lower.contains("reward") || lower.contains("interstitial") || lower.contains("ad"))
}

internal fun isAudienceNetworkRewardShowMethod(clazz: Class<*>, method: Method): Boolean {
    val name = method.name.lowercase()
    return (name == "show" || name == "showad") && 
        !Modifier.isStatic(method.modifiers) && 
        method.parameterCount <= 1
}

internal fun isAudienceNetworkRewardLoadMethod(clazz: Class<*>, method: Method): Boolean {
    return method.name.lowercase().contains("load") && 
        !Modifier.isStatic(method.modifiers) && 
        method.parameterCount <= 1
}

internal fun isAudienceNetworkRewardListenerRegistrationMethod(method: Method): Boolean {
    val name = method.name.lowercase()
    return (name.startsWith("set") || name.startsWith("add") || name == "withadlistener") && 
        name.contains("listener") && 
        method.parameterCount == 1
}

internal fun rememberAudienceNetworkRewardListeners(adObject: Any?, args: Array<Any?>?, method: Method) {
    if (adObject == null || args.isNullOrEmpty()) return
    val listener = args[0] ?: return
    if (isAudienceNetworkRewardListenerObject(listener)) {
        audienceNetworkRewardAdListeners[adObject] = listener
        Logger.i(TAG, "Linked ad object ${adObject.javaClass.name} to listener ${listener.javaClass.name} via ${method.name}")
    }
}

internal fun isAudienceNetworkRewardListenerObject(listener: Any?): Boolean {
    if (listener == null) return false
    val typeName = listener.javaClass.name.lowercase()
    if (typeName.contains("listener") && (typeName.contains("reward") || typeName.contains("ad"))) {
        return true
    }
    
    val interfaces = audienceNetworkInterfacesFor(listener.javaClass)
    return interfaces.any { iface ->
        val ifaceName = iface.name.lowercase()
        ifaceName.contains("listener") && (ifaceName.contains("reward") || ifaceName.contains("ad"))
    }
}

internal fun audienceNetworkInterfacesFor(type: Class<*>): List<Class<*>> {
    val result = ArrayList<Class<*>>()
    fun collect(clazz: Class<*>?) {
        var current = clazz
        while (current != null && current != Any::class.java) {
            current.interfaces.forEach { iface ->
                if (!result.contains(iface)) {
                    result.add(iface)
                    collect(iface)
                }
            }
            current = current.superclass
        }
    }
    collect(type)
    return result
}

internal fun completeAudienceNetworkRewardObject(adObject: Any, reason: String): Boolean {
    val listener = audienceNetworkRewardAdListeners[adObject]
    if (listener != null) {
        val count = invokeAudienceNetworkRewardListenerCallbacks(adObject, listener, reason)
        if (count > 0) {
            Logger.i(TAG, "Successfully completed reward via registered listener ($reason)")
            return true
        }
    }
    
    val foundCount = invokeAudienceNetworkRewardCompletionMethods(adObject)
    if (foundCount > 0) {
        Logger.i(TAG, "Completed reward via direct object methods ($reason)")
        return true
    }
    
    return false
}

internal fun findAudienceNetworkRewardListeners(adObject: Any?): List<Any> {
    if (adObject == null) return emptyList()
    val listeners = mutableListOf<Any>()
    
    val queue = ArrayDeque<Pair<Any, Int>>()
    queue.add(adObject to 0)
    val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    seen.add(adObject)

    while (queue.isNotEmpty()) {
        val (value, depth) = queue.removeFirst()
        if (depth >= 5 || !shouldQueueAudienceNetworkObject(value)) continue

        audienceNetworkFieldsFor(value.javaClass).forEach { field ->
            val fieldValue = runCatching { field.get(value) }.getOrNull()
            if (fieldValue != null && seen.add(fieldValue)) {
                if (isAudienceNetworkRewardListenerObject(fieldValue)) {
                    listeners.add(fieldValue)
                } else {
                    queue.add(fieldValue to depth + 1)
                }
            }
        }
    }
    return listeners
}

internal fun invokeAudienceNetworkRewardListenerCallbacks(adObject: Any, listener: Any, source: String): Int {
    var count = 0
    audienceNetworkRewardMethodsFor(listener.javaClass).forEach { method ->
        if (AUDIENCE_NETWORK_REWARD_COMPLETION_METHOD_NAMES.contains(method.name)) {
            val args = audienceNetworkCallbackArgs(method, adObject)
            runCatching {
                method.invoke(listener, *args.orEmpty())
                Logger.i(TAG, "Invoked reward callback: ${method.name} on ${listener.javaClass.name} ($source)")
                count++
            }
        }
    }
    return count
}

internal fun audienceNetworkCallbackArgs(method: Method, adObject: Any): Array<Any?>? {
    if (method.parameterCount == 0) return emptyArray()
    return when (method.parameterCount) {
        1 -> {
            val paramType = method.parameterTypes[0]
            if (paramType.isAssignableFrom(adObject.javaClass)) arrayOf(adObject) else null
        }
        else -> null
    }
}

internal fun audienceNetworkRewardMethodsFor(type: Class<*>): List<Method> {
    if (!isNonStandardClass(type)) return emptyList()
    val methods = LinkedHashMap<String, Method>()
    var current: Class<*>? = type
    while (current != null && current != Any::class.java && current != Activity::class.java && isNonStandardClass(current)) {
        (current.declaredMethods + current.methods).forEach { method ->
            if (!Modifier.isStatic(method.modifiers)) {
                method.isAccessible = true
                methods.putIfAbsent("${method.name}/${method.parameterTypes.joinToString { it.name }}", method)
            }
        }
        current = current.superclass
    }
    return methods.values.toList()
}

internal fun scheduleAudienceNetworkRewardClose(activity: Activity, source: String) {
    if (scheduledGameAdActivityCloses.containsKey(activity)) return
    scheduledGameAdActivityCloses[activity] = System.currentTimeMillis()

    Logger.i(TAG, "Scheduling automatic close for Audience Network activity ($source)")
    
    val handler = Handler(activity.mainLooper)
    val checkAndClose = object : Runnable {
        var attempts = 0
        override fun run() {
            if (activity.isFinishing || activity.isDestroyed) return
            
            if (clickLikelyAudienceNetworkCloseButton(activity, "auto-close-$attempts")) {
                Logger.i(TAG, "Audience Network activity closed via button click")
                return
            }
            
            attempts++
            if (attempts < 5) {
                handler.postDelayed(this, 1000L)
            } else {
                Logger.i(TAG, "Fallback: Closing Audience Network activity directly")
                activity.finish()
            }
        }
    }
    handler.postDelayed(checkAndClose, 1500L)
}

internal fun clickLikelyAudienceNetworkCloseButton(activity: Activity, source: String): Boolean {
    val root = activity.window?.decorView ?: return false
    val candidates = collectAudienceNetworkCloseCandidates(root)
    
    val best = candidates.maxByOrNull { audienceNetworkCloseCandidateScore(it, root) }
    if (best != null && best.isShown && best.isClickable) {
        Logger.i(TAG, "Clicking likely close button: ${describeAudienceNetworkView(best)} ($source)")
        best.performClick()
        return true
    }
    return false
}

internal fun collectAudienceNetworkCloseCandidates(root: View): List<View> {
    val candidates = mutableListOf<View>()
    val queue = ArrayDeque<View>()
    queue.add(root)
    
    fun visit(view: View) {
        if (audienceNetworkViewMarker(view).contains("exit-candidate")) {
            candidates.add(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                visit(view.getChildAt(i))
            }
        }
    }
    visit(root)
    return candidates
}

internal fun audienceNetworkCloseCandidateScore(view: View, root: View): Int {
    var score = 0
    val name = view.javaClass.name.lowercase()
    if (name.contains("close")) score += 50
    if (name.contains("skip")) score += 40
    if (view is TextView && (view.text.contains("X") || view.text.isNullOrBlank())) score += 30
    
    // Closer to corners is better
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    if (location[0] < root.width * 0.2 || location[0] > root.width * 0.8) score += 20
    if (location[1] < root.height * 0.2 || location[1] > root.height * 0.8) score += 20
    
    return score
}

internal fun forceAudienceNetworkRewardCompletion(activity: Activity, reason: String) {
    Logger.i(TAG, "Forcing reward completion in activity: ${activity.javaClass.name} ($reason)")
    
    val queue = ArrayDeque<Pair<Any, Int>>()
    queue.add(activity to 0)
    val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    seen.add(activity)

    while (queue.isNotEmpty()) {
        val (value, depth) = queue.removeFirst()
        if (depth >= 5 || !shouldTraverseAudienceNetworkObject(value, value === activity)) continue

        audienceNetworkFieldsFor(value.javaClass).forEach { field ->
            val fieldValue = runCatching { field.get(value) }.getOrNull()
            if (fieldValue != null && seen.add(fieldValue)) {
                if (isAudienceNetworkRewardRelevantClass(fieldValue.javaClass.name)) {
                    completeAudienceNetworkRewardObject(fieldValue, "force-$reason")
                } else if (shouldQueueAudienceNetworkObject(fieldValue)) {
                    queue.add(fieldValue to depth + 1)
                }
            }
        }
    }
}

internal fun invokeAudienceNetworkRewardCompletionMethods(obj: Any): Int {
    var count = 0
    audienceNetworkRewardMethodsFor(obj.javaClass).forEach { method ->
        if (AUDIENCE_NETWORK_REWARD_COMPLETION_METHOD_NAMES.contains(method.name)) {
            runCatching {
                method.invoke(obj)
                Logger.i(TAG, "Invoked direct reward completion: ${method.name} on ${obj.javaClass.name}")
                count++
            }
        }
    }
    return count
}

internal fun audienceNetworkFieldsFor(type: Class<*>): List<Field> {
    if (!isNonStandardClass(type)) return emptyList()
    val fields = ArrayList<Field>()
    var current: Class<*>? = type
    while (current != null && current != Any::class.java && current != Activity::class.java && isNonStandardClass(current) && fields.size < 48) {
        current.declaredFields.forEach { field ->
            if (!Modifier.isStatic(field.modifiers)) {
                field.isAccessible = true
                fields.add(field)
            }
        }
        current = current.superclass
    }
    return fields
}

internal fun audienceNetworkMethodsFor(type: Class<*>): List<Method> {
    if (!isNonStandardClass(type)) return emptyList()
    val methods = LinkedHashMap<String, Method>()
    var current: Class<*>? = type
    while (current != null && current != Any::class.java && current != Activity::class.java && isNonStandardClass(current)) {
        current.declaredMethods.forEach { method ->
            if (!Modifier.isStatic(method.modifiers)) {
                method.isAccessible = true
                methods.putIfAbsent("${current.name}.${method.name}/${method.parameterCount}", method)
            }
        }
        current = current.superclass
    }
    return methods.values.toList()
}

internal fun isPotentialAudienceNetworkAppClass(className: String): Boolean {
    val name = className.lowercase()
    return name.startsWith("com.facebook.ads") || name.startsWith("x.") || name.startsWith("com.facebook.katana")
}

internal fun shouldQueueAudienceNetworkObject(obj: Any): Boolean {
    if (obj is View || obj is Activity || obj is android.content.Context) return true
    val name = obj.javaClass.name
    return isPotentialAudienceNetworkAppClass(name) && 
        !name.startsWith("android.") && 
        !name.startsWith("java.") && 
        !name.startsWith("kotlin.")
}

internal fun shouldTraverseAudienceNetworkObject(obj: Any, isActivity: Boolean): Boolean {
    if (isActivity) return true
    val name = obj.javaClass.name
    return isPotentialAudienceNetworkAppClass(name) && !name.contains("Litho") && !name.contains("View")
}
