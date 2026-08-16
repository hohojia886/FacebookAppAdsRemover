package tn.loukious.facebookappadsremover

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal val AUDIENCE_NETWORK_REWARD_COMPLETION_METHOD_NAMES = setOf(
    "onRewardedVideoCompleted",
    "onRewardedAdCompleted",
    "onRewardedInterstitialCompleted",
    "onAdComplete",
    "onAdCompleted"
)

internal val AUDIENCE_NETWORK_CLOSE_LISTENER_CLASS_NAMES = setOf(
    "X.mGv",
    "X.mGo",
    "p000X.mGv",
    "p000X.mGo"
)

internal val AUDIENCE_NETWORK_FOCUSED_DIAGNOSTIC_CLASS_NAMES = setOf(
    "mgv",
    "mgo",
    "mkr",
    "mkq",
    "mks",
    "mdx",
    "mkp"
)

internal data class AudienceNetworkGraphNode(
    val value: Any,
    val path: String,
    val depth: Int
)

internal fun hookAudienceNetworkViewDiagnostics(module: XposedModule) {
    if (!ENABLE_AUDIENCE_NETWORK_VIEW_DIAGNOSTICS ||
        !audienceNetworkViewDiagnosticsInstalled.compareAndSet(0, 1)
    ) {
        return
    }

    val viewMethods = (View::class.java.declaredMethods + View::class.java.methods)
        .filter { method ->
            when (method.name) {
                "performClick", "callOnClick" -> method.parameterCount == 0
                "setOnClickListener" -> method.parameterCount == 1 &&
                    View.OnClickListener::class.java.isAssignableFrom(method.parameterTypes[0])
                "setOnTouchListener" -> method.parameterCount == 1 &&
                    View.OnTouchListener::class.java.isAssignableFrom(method.parameterTypes[0])
                else -> false
            }
        }
        .distinctBy { methodSignature(it) }

    viewMethods.forEach { method ->
        runCatching {
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val view = chain.thisObject as? View
                if (view != null) {
                    val shouldLogView = shouldLogAudienceNetworkViewDiagnostic(view, chain.args.toTypedArray())
                    chain.args.getOrNull(0)?.takeIf {
                        shouldLogView || shouldHookAudienceNetworkListenerClass(it.javaClass.name)
                    }?.let { listener ->
                        tryHookAudienceNetworkViewListenerClass(module, listener.javaClass, "View.${method.name}")
                    }
                    findViewOnClickListener(view)?.takeIf {
                        shouldLogView || shouldHookAudienceNetworkListenerClass(it.javaClass.name)
                    }?.let { listener ->
                        tryHookAudienceNetworkViewListenerClass(module, listener.javaClass, "View.${method.name}.existingClick")
                    }
                    findViewOnTouchListener(view)?.takeIf {
                        shouldLogView || shouldHookAudienceNetworkListenerClass(it.javaClass.name)
                    }?.let { listener ->
                        tryHookAudienceNetworkViewListenerClass(module, listener.javaClass, "View.${method.name}.existingTouch")
                    }
                    
                    if (shouldLogView) {
                        markGameAdDiagnosticFlow("anView.${method.name} ${view.javaClass.name}")
                        logGameAdDiagnostic(
                            "anView.${method.name}.before",
                            "${methodSignature(method)} ${describeAudienceNetworkView(view)} args=${formatDiagArgs(chain.args.toTypedArray())}"
                        )
                    }
                }
                
                val result = chain.proceed()
                
                if (view != null) {
                    if (ENABLE_AUDIENCE_NETWORK_AUTO_EXIT_WHEN_READY && method.name == "setOnClickListener") {
                        val listenerName = chain.args.getOrNull(0)?.javaClass?.name.orEmpty()
                        if (isAudienceNetworkFinalExitListener(listenerName)) {
                            scheduleAudienceNetworkRegisteredExitClick(
                                view,
                                "registered ${method.declaringClass.name}.${method.name} listener=$listenerName"
                            )
                        }
                    }
                    if (shouldLogAudienceNetworkViewDiagnostic(view, chain.args.toTypedArray()) || isRecentGameAdDiagnosticFlow()) {
                        logGameAdDiagnostic(
                            "anView.${method.name}.after",
                            "${methodSignature(method)} result=${formatDiagValue(result)} throwable=none ${describeAudienceNetworkView(view)}"
                        )
                    }
                }
                result
            }
        }.onFailure {
            Logger.w(TAG, "Failed to hook Audience Network view diagnostic ${method.declaringClass.name}.${method.name}", it)
        }
    }

    Logger.i(TAG, "Hooked ${viewMethods.size} Audience Network view diagnostic method(s)")
}

internal fun dumpAudienceNetworkActivityState(activity: Activity, source: String) {
    if (!ENABLE_GAME_AD_DIAGNOSTICS) return
    if (activity.javaClass.name !in setOf(AUDIENCE_NETWORK_ACTIVITY_CLASS, AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS)) {
        return
    }

    val now = System.currentTimeMillis()
    val shouldDump = synchronized(audienceNetworkActivityStateDumps) {
        val previous = audienceNetworkActivityStateDumps[activity]
        if (previous != null && now - previous < 2_000L) {
            false
        } else {
            audienceNetworkActivityStateDumps[activity] = now
            true
        }
    }
    if (!shouldDump) return

    logGameAdDiagnostic(
        "anActivity.dump",
        "$source activity=${activity.javaClass.name} intent=${formatDiagValue(activity.intent)}"
    )
    dumpAudienceNetworkIntentExtras(activity.intent, source)
    dumpAudienceNetworkViewState(activity, source)
    // dumpAudienceNetworkObjectGraph is skipped to avoid complexity in dynamic module passing during recursion.
}

internal fun dumpAudienceNetworkIntentExtras(intent: Intent?, source: String) {
    val extras = intent?.extras ?: return
    val keys = runCatching { extras.keySet().toList() }.getOrDefault(emptyList())
    keys.take(24).forEach { key ->
        val value = runCatching { extras.get(key) }.getOrNull()
        logGameAdDiagnostic(
            "anActivity.intentExtra",
            "$source $key=${formatDiagValue(value)}"
        )
    }
}

internal fun dumpAudienceNetworkViewState(activity: Activity, source: String) {
    val root = activity.window?.decorView ?: return

    collectAudienceNetworkCloseCandidates(root)
        .take(12)
        .forEachIndexed { index, view ->
            logGameAdDiagnostic(
                "anView.closeCandidate",
                "$source #$index score=${audienceNetworkCloseCandidateScore(view, root)} ${describeAudienceNetworkView(view)}"
            )
        }

    var logged = 0
    fun visit(view: View, depth: Int) {
        if (logged < 80 && shouldDescribeAudienceNetworkViewInTree(view)) {
            findViewOnClickListener(view)?.let { listener ->
                // Note: Missing module here, skipping dynamic hooking in tree visitor.
            }
            logged++
            logGameAdDiagnostic(
                "anView.tree",
                "$source depth=$depth ${describeAudienceNetworkView(view)}"
            )
        }

        val group = view as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            visit(group.getChildAt(index), depth + 1)
        }
    }

    visit(root, 0)
}

internal fun tryHookAudienceNetworkDiagnosticObjectClass(module: XposedModule, clazz: Class<*>, source: String) {
    if (isGameAdDiagnosticClassName(clazz.name)) {
        tryHookGameAdDiagnosticClass(module, clazz)
    }
    if (isPotentialAudienceNetworkAppClass(clazz.name)) {
        tryHookAudienceNetworkViewListenerClass(module, clazz, source)
    }
}

internal fun tryHookAudienceNetworkViewListenerClass(module: XposedModule, clazz: Class<*>, source: String) {
    val className = clazz.name
    if (!shouldHookAudienceNetworkListenerClass(className) ||
        !audienceNetworkViewListenerClassesHooked.add(className)
    ) {
        return
    }

    var hooked = 0
    val methods = runCatching { clazz.declaredMethods }.getOrDefault(emptyArray())
    methods.asSequence()
        .filter { method -> isAudienceNetworkViewListenerDiagnosticMethod(method) }
        .distinctBy { methodSignature(it) }
        .take(24)
        .forEach { method ->
            runCatching {
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    if (shouldLogAudienceNetworkListenerCall(method, chain.args.toTypedArray())) {
                        markGameAdDiagnosticFlow("anListener.${method.name} ${method.declaringClass.name}")
                        logGameAdDiagnostic(
                            "anListener.${method.name}.before",
                            "${methodSignature(method)} this=${formatDiagValue(chain.thisObject)} args=${formatDiagArgs(chain.args.toTypedArray())}"
                        )
                    }
                    
                    val result = chain.proceed()
                    
                    if (shouldLogAudienceNetworkListenerCall(method, chain.args.toTypedArray()) || isRecentGameAdDiagnosticFlow()) {
                        logGameAdDiagnostic(
                            "anListener.${method.name}.after",
                            "${methodSignature(method)} result=${formatDiagValue(result)} throwable=none"
                        )
                    }
                    result
                }
                hooked++
            }.onFailure {
                Logger.w(TAG, "Failed to hook Audience Network listener diagnostic ${clazz.name}.${method.name}", it)
            }
        }

    if (hooked > 0) {
        logGameAdDiagnostic(
            "anListener.hooked",
            "$source class=$className hooked=$hooked methods=${audienceNetworkInterestingMethodsSummary(clazz)}"
        )
    }
}

internal fun isAudienceNetworkViewListenerDiagnosticMethod(method: Method): Boolean {
    if (method.declaringClass == Any::class.java || method.isSynthetic || method.isBridge) return false
    if (Modifier.isStatic(method.modifiers) || method.parameterCount > 6) return false
    if (method.name in setOf("wait", "notify", "notifyAll", "hashCode", "equals", "toString")) return false

    val methodName = method.name.lowercase()
    if (methodName in setOf("onclick", "ontouch")) return true
    if (method.declaringClass.name.isFocusedAudienceNetworkClassName() &&
        methodName in setOf("finish", "a02", "a03", "ccz")
    ) {
        return true
    }
    return method.parameterTypes.any { type ->
        isGameAdDiagnosticClassName(type.name)
    } || isGameAdDiagnosticClassName(method.returnType.name)
}

internal fun shouldLogAudienceNetworkListenerCall(method: Method, args: Array<Any?>?): Boolean {
    if (args.orEmpty().any { value -> value is View && shouldLogAudienceNetworkViewDiagnostic(value, null) }) {
        return true
    }
    val methodName = method.name.lowercase()
    return isRecentGameAdDiagnosticFlow() &&
        (methodName in setOf("onclick", "ontouch") ||
            methodName.hasAudienceNetworkViewSignal() ||
            methodName.hasGameAdSignal())
}

internal fun shouldLogAudienceNetworkViewDiagnostic(view: View, args: Array<Any?>?): Boolean {
    val activity = contextActivityForView(view)
    if (activity?.javaClass?.name in setOf(AUDIENCE_NETWORK_ACTIVITY_CLASS, AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS)) {
        return true
    }

    if (view.javaClass.name.hasGameAdSignal() || audienceNetworkViewMarker(view).hasAudienceNetworkViewSignal()) {
        return true
    }

    if (audienceNetworkParentPath(view).hasGameAdSignal()) return true

    return args.orEmpty().any { value ->
        value?.javaClass?.name?.let { shouldHookAudienceNetworkListenerClass(it) || it.hasGameAdSignal() } == true ||
            isGameAdDiagnosticValue(value)
    }
}

internal fun shouldDescribeAudienceNetworkViewInTree(view: View): Boolean {
    if (view.javaClass.name.hasGameAdSignal()) return true
    val marker = audienceNetworkViewMarker(view)
    if (marker.hasAudienceNetworkViewSignal()) return true
    if (view.isClickable || findViewOnClickListener(view) != null || findViewOnTouchListener(view) != null) return true
    return audienceNetworkParentPath(view).hasGameAdSignal()
}

internal fun describeAudienceNetworkView(view: View): String {
    val location = IntArray(2)
    val locationText = runCatching {
        view.getLocationOnScreen(location)
        "${location[0]},${location[1]}"
    }.getOrDefault("?,?")

    val clickListener = findViewOnClickListener(view)?.javaClass?.name ?: "null"
    val touchListener = findViewOnTouchListener(view)?.javaClass?.name ?: "null"
    val text = truncateDiag((view as? TextView)?.text?.toString().orEmpty(), 80)
    val description = truncateDiag(view.contentDescription?.toString().orEmpty(), 80)
    val activityName = contextActivityForView(view)?.javaClass?.name ?: "null"

    return "view=${view.javaClass.name}@${Integer.toHexString(System.identityHashCode(view))} " +
        "activity=$activityName id=${viewIdLabel(view)} shown=${view.isShown} enabled=${view.isEnabled} " +
        "clickable=${view.isClickable} size=${view.width}x${view.height} loc=$locationText " +
        "text=\"$text\" desc=\"$description\" clickListener=$clickListener touchListener=$touchListener " +
        "parents=${audienceNetworkParentPath(view)}"
}

internal fun audienceNetworkViewMarker(view: View): String {
    return buildString {
        append(view.javaClass.name)
        append(' ')
        append(view.contentDescription?.toString().orEmpty())
        append(' ')
        append((view as? TextView)?.text?.toString().orEmpty())
        append(' ')
        append(viewIdLabel(view))
    }.lowercase()
}

internal fun audienceNetworkParentPath(view: View): String {
    val names = ArrayList<String>()
    var current = view.parent
    var depth = 0
    while (current != null && depth < 8) {
        names.add(current.javaClass.name)
        current = (current as? View)?.parent
        depth++
    }
    return names.joinToString(">")
}

internal fun shouldHookAudienceNetworkListenerClass(className: String): Boolean {
    return className in AUDIENCE_NETWORK_CLOSE_LISTENER_CLASS_NAMES ||
        className.isFocusedAudienceNetworkClassName() ||
        className.startsWith("com.facebook.ads.") ||
        className.contains("audiencenetwork", ignoreCase = true)
}

internal fun isAudienceNetworkFinalExitListener(className: String): Boolean {
    return className == "X.mGo" || className == "p000X.mGo"
}

internal fun isAudienceNetworkClosePromptListener(className: String): Boolean {
    return className == "X.mGv" || className == "p000X.mGv"
}

internal fun scheduleAudienceNetworkRegisteredExitClick(view: View, source: String) {
    val now = System.currentTimeMillis()
    val shouldSchedule = synchronized(scheduledAudienceNetworkExitViews) {
        val previous = scheduledAudienceNetworkExitViews[view]
        if (previous != null && now - previous < AUDIENCE_NETWORK_REWARD_CLOSE_RETRY_WINDOW_MS) {
            false
        } else {
            scheduledAudienceNetworkExitViews[view] = now
            true
        }
    }
    if (!shouldSchedule) return

    Logger.i(TAG, "Scheduled Audience Network final exit click for ${view.javaClass.name} via $source")
    listOf(0L, 250L, 500L, 1_000L, 2_000L, 3_500L, 5_000L, 7_500L).forEach { delayMs ->
        view.postDelayed({
            if (!isAudienceNetworkFinalExitViewReady(view)) return@postDelayed
            val clicked = runCatching { view.performClick() }.getOrDefault(false)
            if (clicked) {
                lastGameAdActivityCloseMs.set(System.currentTimeMillis())
                Logger.i(
                    TAG,
                    "Clicked registered Audience Network final exit ${view.javaClass.name} via $source delay=${delayMs}ms"
                )
            }
        }, delayMs)
    }
}

internal fun isAudienceNetworkFinalExitViewReady(view: View): Boolean {
    val listenerName = findViewOnClickListener(view)?.javaClass?.name.orEmpty()
    return isAudienceNetworkFinalExitListener(listenerName) &&
        view.isShown &&
        view.isEnabled &&
        view.isClickable &&
        view.width > 0 &&
        view.height > 0
}

internal fun String.isFocusedAudienceNetworkClassName(): Boolean {
    return substringAfterLast('.').lowercase() in AUDIENCE_NETWORK_FOCUSED_DIAGNOSTIC_CLASS_NAMES
}

internal fun audienceNetworkInterestingMethodsSummary(type: Class<*>): String {
    return runCatching {
        audienceNetworkMethodsFor(type)
            .asSequence()
            .filter { method ->
                method.parameterCount <= 4 &&
                    method.name !in setOf("wait", "notify", "notifyAll", "hashCode", "equals", "toString")
            }
            .take(24)
            .joinToString(";") { method ->
                "${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})>${method.returnType.simpleName}"
            }
    }.getOrDefault("")
}

internal fun String.hasAudienceNetworkViewSignal(): Boolean {
    val normalized = lowercase()
    return normalized.contains("close") ||
        normalized.contains("dismiss") ||
        normalized.contains("skip") ||
        normalized.contains("done") ||
        normalized.contains("click") ||
        normalized.contains("touch") ||
        normalized.contains("reward") ||
        normalized.contains("complete") ||
        normalized.contains("watched") ||
        normalized.contains("video") ||
        normalized.contains("interstitial") ||
        normalized.contains("adchoices") ||
        normalized.contains("ads served by meta") ||
        normalized.contains("audiencenetwork") ||
        normalized.contains("com.facebook.ads")
}

internal fun hookAudienceNetworkRewardFallbacks(module: XposedModule, classLoader: ClassLoader) {
    if (!audienceNetworkRewardHooksInstalled.compareAndSet(0, 1)) return

    listOf(
        "com.facebook.ads.RewardedVideoAd",
        "com.facebook.ads.RewardedInterstitialAd",
        "com.facebook.ads.RewardedVideoAdListener",
        "com.facebook.ads.RewardedInterstitialAdListener",
        "com.facebook.ads.RewardedVideoAd\$RewardedVideoAdLoadConfigBuilder",
        "com.facebook.ads.RewardedInterstitialAd\$RewardedInterstitialAdLoadConfigBuilder"
    ).forEach { className ->
        runCatching { tryHookAudienceNetworkRewardClass(module, classLoader.loadClass(className)) }
    }

    (ClassLoader::class.java.declaredMethods + ClassLoader::class.java.methods)
        .filter { method ->
            method.name == "loadClass" &&
                method.parameterTypes.isNotEmpty() &&
                method.parameterTypes[0] == String::class.java
        }
        .distinctBy { methodSignature(it) }
        .forEach { method ->
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                val clazz = result as? Class<*>
                if (clazz != null && isAudienceNetworkRewardRelevantClass(clazz.name)) {
                    tryHookAudienceNetworkRewardClass(module, clazz)
                }
                result
            }
        }

    Logger.i(TAG, "Hooked Audience Network reward dynamic class fallback")
}

internal fun tryHookAudienceNetworkRewardClass(module: XposedModule, clazz: Class<*>) {
    val className = clazz.name
    if (!isAudienceNetworkRewardRelevantClass(className) ||
        !audienceNetworkRewardClassesHooked.add(className)
    ) {
        return
    }

    var hooked = 0
    val methods = runCatching { clazz.declaredMethods + clazz.methods }.getOrDefault(emptyArray())
    methods.distinctBy { method ->
        method.name + method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
    }.forEach { method ->
        runCatching {
            method.isAccessible = true
            if (isAudienceNetworkRewardShowMethod(clazz, method)) {
                module.hook(method).intercept { chain ->
                    val adObject = chain.thisObject
                    if (adObject != null) {
                        markGameAdDiagnosticFlow("anReward.show ${method.declaringClass.name}.${method.name}")
                        logGameAdDiagnostic(
                            "anReward.show.before",
                            "${methodSignature(method)} this=${formatDiagValue(adObject)} args=${formatDiagArgs(chain.args.toTypedArray())}"
                        )
                        if (ENABLE_GAME_AD_AUTOFIX) {
                            if (completeAudienceNetworkRewardObject(adObject, "show ${method.declaringClass.name}.${method.name}")) {
                                Logger.i(TAG, "Skipped Audience Network rewarded show via ${method.declaringClass.name}.${method.name}")
                                return@intercept when (method.returnType) {
                                    Boolean::class.javaPrimitiveType, Boolean::class.java -> true
                                    else -> null
                                }
                            }
                        }
                    }
                    
                    val result = chain.proceed()
                    logGameAdDiagnostic(
                        "anReward.show.after",
                        "${methodSignature(method)} result=${formatDiagValue(result)} throwable=none"
                    )
                    result
                }
                hooked++
            } else if (isAudienceNetworkRewardListenerRegistrationMethod(method)) {
                module.hook(method).intercept { chain ->
                    logGameAdDiagnostic(
                        "anReward.listener.before",
                        "${methodSignature(method)} this=${formatDiagValue(chain.thisObject)} args=${formatDiagArgs(chain.args.toTypedArray())}"
                    )
                    rememberAudienceNetworkRewardListeners(chain.thisObject, chain.args.toTypedArray(), method)
                    
                    val result = chain.proceed()
                    
                    rememberAudienceNetworkRewardListeners(chain.thisObject, chain.args.toTypedArray(), method)
                    rememberAudienceNetworkRewardListeners(result, chain.args.toTypedArray(), method)
                    logGameAdDiagnostic(
                        "anReward.listener.after",
                        "${methodSignature(method)} result=${formatDiagValue(result)} throwable=none"
                    )
                    result
                }
                hooked++
            } else if (isAudienceNetworkRewardLoadMethod(clazz, method)) {
                module.hook(method).intercept { chain ->
                    markGameAdDiagnosticFlow("anReward.load ${method.declaringClass.name}.${method.name}")
                    logGameAdDiagnostic(
                        "anReward.load.before",
                        "${methodSignature(method)} this=${formatDiagValue(chain.thisObject)} args=${formatDiagArgs(chain.args.toTypedArray())}"
                    )
                    rememberAudienceNetworkRewardListeners(chain.thisObject, chain.args.toTypedArray(), method)
                    
                    val result = chain.proceed()
                    logGameAdDiagnostic(
                        "anReward.load.after",
                        "${methodSignature(method)} result=${formatDiagValue(result)} throwable=none"
                    )
                    result
                }
                hooked++
            }
        }.onFailure {
            Logger.w(TAG, "Failed to hook Audience Network reward method ${clazz.name}.${method.name}", it)
        }
    }

    if (hooked > 0) {
        Logger.i(TAG, "Hooked $hooked Audience Network reward method(s) in $className")
    }
}

internal fun isAudienceNetworkRewardRelevantClass(className: String): Boolean {
    val normalized = className.lowercase()
    return (normalized.startsWith("com.facebook.ads.") ||
        normalized.startsWith("com.facebook.audiencenetwork.") ||
        normalized.contains("audiencenetwork")) &&
        (
            normalized.contains("reward") ||
                normalized.contains("adlistener") ||
                normalized.contains("adconfig") ||
                normalized.endsWith(".ad")
            )
}

internal fun isAudienceNetworkRewardShowMethod(clazz: Class<*>, method: Method): Boolean {
    val className = clazz.name.lowercase()
    return className.contains("reward") &&
        method.name == "show" &&
        !Modifier.isStatic(method.modifiers) &&
        method.parameterCount <= 1 &&
        (method.returnType == Void.TYPE ||
            method.returnType == Boolean::class.javaPrimitiveType ||
            method.returnType == Boolean::class.java)
}

internal fun isAudienceNetworkRewardLoadMethod(clazz: Class<*>, method: Method): Boolean {
    return clazz.name.lowercase().contains("reward") &&
        method.name.lowercase().contains("load") &&
        !Modifier.isStatic(method.modifiers) &&
        method.parameterCount >= 1
}

internal fun isAudienceNetworkRewardListenerRegistrationMethod(method: Method): Boolean {
    if (Modifier.isStatic(method.modifiers) || method.parameterCount == 0) return false
    val name = method.name.lowercase()
    if (name.contains("listener")) return true
    return method.parameterTypes.any { type ->
        val typeName = type.name.lowercase()
        typeName.contains("listener") &&
            (typeName.contains("reward") || typeName.contains("ad"))
    }
}

internal fun rememberAudienceNetworkRewardListeners(owner: Any?, args: Array<Any?>?, method: Method) {
    if (owner == null || args == null) return
    args.forEach { arg ->
        if (arg != null && isAudienceNetworkRewardListenerObject(arg)) {
            audienceNetworkRewardAdListeners[owner] = arg
            Logger.i(
                TAG,
                "Remembered Audience Network reward listener ${arg.javaClass.name} from ${method.declaringClass.name}.${method.name}"
            )
        } else {
            findAudienceNetworkRewardListeners(arg).firstOrNull()?.let { listener ->
                audienceNetworkRewardAdListeners[owner] = listener
                Logger.i(
                    TAG,
                    "Remembered nested Audience Network reward listener ${listener.javaClass.name} from ${method.declaringClass.name}.${method.name}"
                )
            }
        }
    }
}

internal fun isAudienceNetworkRewardListenerObject(value: Any?): Boolean {
    if (value == null) return false
    val type = value.javaClass
    val className = type.name.lowercase()
    if (className.contains("listener") && (className.contains("reward") || className.contains("ad"))) {
        return true
    }
    if (audienceNetworkInterfacesFor(type).any { iface ->
            val ifaceName = iface.name.lowercase()
            ifaceName.contains("listener") && (ifaceName.contains("reward") || ifaceName.contains("ad"))
        }) {
        return true
    }
    return audienceNetworkRewardMethodsFor(type).any { method ->
        method.name in AUDIENCE_NETWORK_REWARD_COMPLETION_METHOD_NAMES ||
            method.name.contains("Reward", ignoreCase = true) ||
            method.name.contains("InterstitialDismissed", ignoreCase = true)
    }
}

internal fun audienceNetworkInterfacesFor(type: Class<*>): List<Class<*>> {
    val interfaces = LinkedHashSet<Class<*>>()
    fun collect(current: Class<*>?) {
        if (current == null || current == Any::class.java) return
        current.interfaces.forEach { iface ->
            if (interfaces.add(iface)) collect(iface)
        }
        collect(current.superclass)
    }
    collect(type)
    return interfaces.toList()
}

internal fun completeAudienceNetworkRewardObject(adObject: Any, source: String): Boolean {
    if (!ENABLE_AUDIENCE_NETWORK_REWARD_FALLBACKS) return false

    val listeners = LinkedHashSet<Any>()
    synchronized(audienceNetworkRewardAdListeners) {
        audienceNetworkRewardAdListeners[adObject]?.let { listeners.add(it) }
    }
    listeners.addAll(findAudienceNetworkRewardListeners(adObject))

    var invoked = 0
    listeners.forEach { listener ->
        invoked += invokeAudienceNetworkRewardListenerCallbacks(listener, adObject, source)
    }

    if (invoked > 0) {
        Logger.i(TAG, "Completed Audience Network reward callbacks invoked=$invoked listeners=${listeners.size} via $source")
        completeRecentGameAdRequests(source)
        return true
    }

    Logger.w(TAG, "No Audience Network reward listener completed for ${adObject.javaClass.name} via $source")
    return false
}

internal fun findAudienceNetworkRewardListeners(root: Any?): List<Any> {
    if (root == null) return emptyList()

    val listeners = LinkedHashSet<Any>()
    val seen = IdentityHashMap<Any, Boolean>()
    val queue = java.util.ArrayDeque<Pair<Any, Int>>()
    queue.add(root to 0)

    var inspected = 0
    while (!queue.isEmpty() && inspected < 96 && listeners.size < 8) {
        val (value, depth) = queue.removeFirst()
        if (seen.put(value, true) != null) continue
        inspected++

        if (value !== root && isAudienceNetworkRewardListenerObject(value)) {
            listeners.add(value)
            continue
        }
        if (depth >= 5 || !shouldQueueAudienceNetworkObject(value)) continue

        audienceNetworkFieldsFor(value.javaClass).forEach { field ->
            val fieldValue = runCatching { field.get(value) }.getOrNull() ?: return@forEach
            when (fieldValue) {
                is Iterable<*> -> fieldValue.take(12).forEach { item ->
                    if (item != null &&
                        (isAudienceNetworkRewardListenerObject(item) || shouldQueueAudienceNetworkObject(item))
                    ) {
                        queue.add(item to depth + 1)
                    }
                }
                is Array<*> -> fieldValue.take(12).forEach { item ->
                    if (item != null &&
                        (isAudienceNetworkRewardListenerObject(item) || shouldQueueAudienceNetworkObject(item))
                    ) {
                        queue.add(item to depth + 1)
                    }
                }
                else -> if (isAudienceNetworkRewardListenerObject(fieldValue) ||
                    shouldQueueAudienceNetworkObject(fieldValue)
                ) {
                    queue.add(fieldValue to depth + 1)
                }
            }
        }
    }

    return listeners.toList()
}

internal fun invokeAudienceNetworkRewardListenerCallbacks(listener: Any, adObject: Any, source: String): Int {
    var invoked = 0
    val methodGroups = listOf(
        setOf("onAdLoaded", "onLoggingImpression", "onInterstitialDisplayed"),
        setOf(
            "onRewardedVideoCompleted",
            "onRewardedAdCompleted",
            "onRewardedInterstitialCompleted",
            "onAdComplete",
            "onAdCompleted"
        ),
        setOf("onRewardedVideoClosed", "onRewardedInterstitialClosed", "onAdClosed", "onInterstitialDismissed")
    )

    methodGroups.forEach { group ->
        audienceNetworkRewardMethodsFor(listener.javaClass)
            .filter { method -> method.name in group }
            .forEach { method ->
                val args = audienceNetworkCallbackArgs(method, adObject) ?: return@forEach
                runCatching {
                    method.invoke(listener, *args)
                    invoked++
                    Logger.i(
                        TAG,
                        "Invoked Audience Network callback ${listener.javaClass.name}.${method.name} via $source"
                    )
                }.onFailure {
                    Logger.w(TAG, "Failed Audience Network callback ${listener.javaClass.name}.${method.name}", it)
                }
            }
    }

    return invoked
}

internal fun audienceNetworkCallbackArgs(method: Method, adObject: Any): Array<Any?>? {
    return when (method.parameterCount) {
        0 -> emptyArray()
        1 -> {
            val paramType = method.parameterTypes[0]
            if (paramType.isAssignableFrom(adObject.javaClass)) arrayOf(adObject) else null
        }
        else -> null
    }
}

internal fun audienceNetworkRewardMethodsFor(type: Class<*>): List<Method> {
    val methods = LinkedHashMap<String, Method>()
    var current: Class<*>? = type
    while (current != null && current != Any::class.java && current != Activity::class.java) {
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
    if (activity.isFinishing) return

    val now = System.currentTimeMillis()
    val shouldSchedule = synchronized(scheduledGameAdActivityCloses) {
        val previous = scheduledGameAdActivityCloses[activity]
        if (previous != null && now - previous < AUDIENCE_NETWORK_REWARD_CLOSE_RETRY_WINDOW_MS) {
            false
        } else {
            scheduledGameAdActivityCloses[activity] = now
            true
        }
    }
    if (!shouldSchedule) return

    val root = activity.window?.decorView
    if (root == null) {
        Logger.i(TAG, "Audience Network reward close skipped; missing decor via $source")
        return
    }

    Logger.i(
        TAG,
        "Scheduled Audience Network reward close autoclick for ${activity.javaClass.name} via $source"
    )

    listOf(
        0L,
        120L,
        350L,
        750L,
        1_250L,
        2_000L,
        3_000L,
        AUDIENCE_NETWORK_REWARD_CLOSE_RETRY_WINDOW_MS
    ).forEach { delayMs ->
        root.postDelayed({
            if (activity.isFinishing) return@postDelayed
            val clicked = clickLikelyAudienceNetworkCloseButton(activity, "$source delay=${delayMs}ms")
            if (!clicked) {
                Logger.i(TAG, "Audience Network close button not ready after ${delayMs}ms via $source")
            }
        }, delayMs)
    }
}

internal fun clickLikelyAudienceNetworkCloseButton(activity: Activity, source: String): Boolean {
    val root = activity.window?.decorView ?: return false

    val candidates = collectAudienceNetworkCloseCandidates(root)
    candidates.forEach { view ->
        val clicked = runCatching { view.performClick() }.getOrDefault(false)
        if (clicked) {
            lastGameAdActivityCloseMs.set(System.currentTimeMillis())
            Logger.i(TAG, "Clicked Audience Network close candidate ${view.javaClass.name} via $source")
            return true
        }
    }
    return false
}

internal fun collectAudienceNetworkCloseCandidates(root: View): List<View> {
    val candidates = ArrayList<Pair<Int, View>>()

    fun visit(view: View) {
        val group = view as? ViewGroup
        if (group != null) {
            for (index in 0 until group.childCount) {
                visit(group.getChildAt(index))
            }
        }

        val score = audienceNetworkCloseCandidateScore(view, root)
        if (score > 0) {
            candidates.add(score to view)
        }
    }

    visit(root)
    return candidates
        .sortedWith(compareByDescending<Pair<Int, View>> { it.first }.thenBy { it.second.width * it.second.height })
        .map { it.second }
}

internal fun audienceNetworkCloseCandidateScore(view: View, root: View): Int {
    if (!view.isShown || !view.isEnabled) return 0

    val className = view.javaClass.name.lowercase()
    val listenerName = findViewOnClickListener(view)?.javaClass?.name.orEmpty()
    val marker = buildString {
        append(className)
        append(' ')
        append(view.contentDescription?.toString()?.lowercase().orEmpty())
        append(' ')
        append((view as? TextView)?.text?.toString()?.lowercase().orEmpty())
        append(' ')
        append(listenerName.lowercase())
        append(' ')
        append(audienceNetworkParentPath(view).lowercase())
    }

    if (marker.contains("mute") ||
        marker.contains("sound") ||
        marker.contains("volume") ||
        marker.contains("keep watching") ||
        marker.contains("lose reward")
    ) {
        return 0
    }
    if (isAudienceNetworkFinalExitListener(listenerName)) return 300
    if (isAudienceNetworkClosePromptListener(listenerName) && marker.contains("close")) return 180
    if (marker.contains("fullscreenadtoolbar") && marker.contains("close")) return 230
    if ((view.id == 33 || view.id == 34) && isTopRightSmallControl(view, root) && marker.contains("imageview")) return 160
    if (marker.contains("close") || marker.contains("dismiss") || marker.contains("skip") || marker.contains("done")) {
        return 120
    }
    if (!view.isClickable) return 0
    if (className.contains("close") || className.contains("dismiss")) return 70
    return 0
}

internal fun forceAudienceNetworkRewardCompletion(activity: Activity, source: String) {
    if (!ENABLE_AUDIENCE_NETWORK_REWARD_FALLBACKS) return
    if (activity.javaClass.name !in GAME_AD_ACTIVITY_CLASS_NAMES) return

    val seen = IdentityHashMap<Any, Boolean>()
    val queue = java.util.ArrayDeque<Pair<Any, Int>>()
    queue.add(activity to 0)

    var inspected = 0
    var invoked = 0
    while (!queue.isEmpty() && inspected < 96) {
        val (value, depth) = queue.removeFirst()
        if (seen.put(value, true) != null) continue
        inspected++

        invoked += invokeAudienceNetworkRewardCompletionMethods(value)
        if (depth >= 5 || !shouldTraverseAudienceNetworkObject(value, value === activity)) continue

        audienceNetworkFieldsFor(value.javaClass).forEach { field ->
            val fieldValue = runCatching { field.get(value) }.getOrNull() ?: return@forEach
            when (fieldValue) {
                is Iterable<*> -> fieldValue.take(12).forEach { item ->
                    if (item != null && shouldQueueAudienceNetworkObject(item)) queue.add(item to depth + 1)
                }
                is Array<*> -> fieldValue.take(12).forEach { item ->
                    if (item != null && shouldQueueAudienceNetworkObject(item)) queue.add(item to depth + 1)
                }
                else -> if (shouldQueueAudienceNetworkObject(fieldValue)) {
                    queue.add(fieldValue to depth + 1)
                }
            }
        }
    }

    Logger.i(TAG, "Forced Audience Network reward callbacks invoked=$invoked inspected=$inspected via $source")
}

internal fun invokeAudienceNetworkRewardCompletionMethods(target: Any): Int {
    var invoked = 0
    audienceNetworkMethodsFor(target.javaClass)
        .filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 0 &&
                (
                    method.name in AUDIENCE_NETWORK_REWARD_COMPLETION_METHOD_NAMES ||
                        (method.name.contains("Reward", ignoreCase = true) &&
                            method.name.contains("Complete", ignoreCase = true))
                    )
        }
        .forEach { method ->
            runCatching {
                method.invoke(target)
                invoked++
            }.onFailure {
                Logger.w(TAG, "Failed to invoke Audience Network reward callback ${target.javaClass.name}.${method.name}", it)
            }
        }
    return invoked
}

internal fun audienceNetworkFieldsFor(type: Class<*>): List<Field> {
    val fields = ArrayList<Field>()
    var current: Class<*>? = type
    while (current != null &&
        current != Any::class.java &&
        current != Activity::class.java &&
        fields.size < 48
    ) {
        current.declaredFields.forEach { field ->
            if (!Modifier.isStatic(field.modifiers) && fields.size < 48) {
                field.isAccessible = true
                fields.add(field)
            }
        }
        current = current.superclass
    }
    return fields
}

internal fun audienceNetworkMethodsFor(type: Class<*>): List<Method> {
    val methods = LinkedHashMap<String, Method>()
    var current: Class<*>? = type
    while (current != null &&
        current != Any::class.java &&
        current != Activity::class.java
    ) {
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
    return className.startsWith("com.facebook.") ||
        className.startsWith("X.") ||
        className.startsWith("p000X.") ||
        className.hasGameAdSignal()
}

internal fun shouldQueueAudienceNetworkObject(value: Any): Boolean {
    val type = value.javaClass
    if (type.isPrimitive ||
        value is String ||
        value is Number ||
        value is Boolean ||
        value is CharSequence
    ) {
        return false
    }
    return shouldTraverseAudienceNetworkObject(value, false)
}

internal fun shouldTraverseAudienceNetworkObject(value: Any, isRootActivity: Boolean): Boolean {
    if (isRootActivity) return true
    val className = value.javaClass.name.lowercase()
    return className.startsWith("com.facebook.ads.") ||
        className.startsWith("com.facebook.audiencenetwork.") ||
        className.contains("audiencenetwork") ||
        className.contains("reward") ||
        className.contains("interstitial") ||
        className.contains("fullscreen") ||
        className.contains("listener") ||
        className.contains(".ads.")
}
