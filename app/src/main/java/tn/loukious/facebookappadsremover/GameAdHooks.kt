package tn.loukious.facebookappadsremover

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.Messenger
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

fun resolveGameAdPayload(target: Any?, payload: Any?, messageType: String? = null): Boolean {
    if (target == null || payload == null) return false

    val promiseId = extractPromiseId(payload)
    if (promiseId == null) {
        Logger.w(TAG, "Unable to extract promiseID for resolved game ad payload")
        return false
    }

    val resolveMethod = resolveGameAdResolveMethod(target.javaClass)
    if (resolveMethod == null) {
        Logger.w(TAG, "Unable to resolve success helper for resolved game ad payload")
        return false
    }

    val successPayload = buildGameAdSuccessPayload(payload, messageType)
    return runCatching {
        resolveMethod.invoke(target, promiseId, successPayload)
        true
    }.getOrElse {
        Logger.e(TAG, "Failed to resolve game ad payload", it)
        false
    }
}

fun rejectGameAdPayload(
    target: Any?,
    payload: Any?,
    message: String = GAME_AD_REJECTION_MESSAGE,
    code: String = GAME_AD_REJECTION_CODE
): Boolean {
    if (target == null || payload == null) return false

    val bridgeRejectMethod = resolveGameAdBridgeRejectMethod(target.javaClass)
    if (bridgeRejectMethod != null) {
        val success = runCatching {
            bridgeRejectMethod.invoke(target, message, code, payload)
            true
        }.getOrElse {
            Logger.e(TAG, "Failed to reject game ad payload via bridge reject helper", it)
            false
        }
        if (success) {
            return true
        }
    }

    val promiseId = extractPromiseId(payload)
    if (promiseId == null) {
        Logger.w(TAG, "Unable to extract promiseID for rejected game ad payload")
        return false
    }
    val rejectMethod = resolveGameAdRejectMethod(target.javaClass)
    if (rejectMethod == null) {
        Logger.w(TAG, "Unable to resolve reject helper for rejected game ad payload")
        return false
    }
    return runCatching {
        rejectMethod.invoke(
            target,
            promiseId,
            message,
            code
        )
        true
    }.getOrElse {
        Logger.e(TAG, "Failed to reject game ad payload", it)
        false
    }
}

fun resolveGameAdResolveMethod(type: Class<*>?): Method? {
    if (type == null) return null

    val candidates = (type.declaredMethods + type.methods).filter { method ->
        !Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            method.parameterCount == 2 &&
            method.parameterTypes[0] == String::class.java &&
            !method.parameterTypes[1].isPrimitive
    }

    return (candidates.firstOrNull { it.parameterTypes[1] == Any::class.java }
        ?: candidates.firstOrNull { JSONObject::class.java.isAssignableFrom(it.parameterTypes[1]) }
        ?: candidates.firstOrNull()
        )?.apply { isAccessible = true }
}

fun resolveGameAdBridgeRejectMethod(type: Class<*>?): Method? {
    if (type == null) return null
    return (type.declaredMethods + type.methods).firstOrNull { method ->
        !Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            method.parameterCount == 3 &&
            method.parameterTypes[0] == String::class.java &&
            method.parameterTypes[1] == String::class.java &&
            method.parameterTypes[2] == JSONObject::class.java
    }?.apply { isAccessible = true }
}

fun resolveGameAdRejectMethod(type: Class<*>?): Method? {
    if (type == null) return null
    return (type.declaredMethods + type.methods).firstOrNull { method ->
        !Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            method.parameterCount == 3 &&
            method.parameterTypes.all { it == String::class.java }
    }?.apply { isAccessible = true }
}

fun dispatchGameEvent(target: Any?, eventType: String, content: Any?): Boolean {
    if (target == null) return false
    val dispatchMethod = resolveGameEventDispatchMethod(target.javaClass) ?: return false
    val eventValue = resolveGameEventValue(dispatchMethod.parameterTypes[0], eventType) ?: return false

    return runCatching {
        dispatchMethod.invoke(target, eventValue, content ?: JSONObject.NULL)
        true
    }.getOrElse {
        Logger.w(TAG, "Failed to dispatch game event type=$eventType", it)
        false
    }
}

fun resolveGameEventDispatchMethod(type: Class<*>?): Method? {
    if (type == null) return null
    return (type.declaredMethods + type.methods).firstOrNull { method ->
        !Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            method.parameterCount == 2 &&
            method.parameterTypes[0] != String::class.java &&
            method.parameterTypes[1] == Any::class.java
    }?.apply { isAccessible = true }
}

fun resolveGameEventValue(eventType: Class<*>, eventName: String): Any? {
    val valuesMethod = (eventType.declaredMethods + eventType.methods).firstOrNull { method ->
        Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 0 &&
            method.returnType.isArray &&
            method.returnType.componentType == eventType
    }?.apply { isAccessible = true }

    val values = runCatching { valuesMethod?.invoke(null) as? Array<*> }.getOrNull().orEmpty()
    values.firstOrNull { value -> value?.toString() == eventName }?.let { return it }

    return eventType.declaredFields.firstOrNull { field ->
        Modifier.isStatic(field.modifiers) &&
            field.type == eventType &&
            runCatching {
                field.isAccessible = true
                field.get(null)?.toString() == eventName
            }.getOrDefault(false)
    }?.let { field -> runCatching { field.get(null) }.getOrNull() }
}

fun inferGameAdMessageType(method: Method, payload: Any?): String? {
    val payloadType = (payload as? JSONObject)?.optString("type").takeIf { it?.isNotBlank() == true }
    if (payloadType != null) return payloadType

    return when (method.name) {
        "D3s" -> "getinterstitialadasync"
        "D3x" -> "getrewardedinterstitialasync"
        "D3z" -> "getrewardedvideoasync"
        "D55" -> "hidebanneradasync"
        "D9v" -> "loadadasync"
        "D9x" -> "loadbanneradasync"
        "DX0" -> "showadasync"
        else -> null
    }
}

fun dispatchPostResolveGameAdSignals(target: Any?, payload: Any?, messageType: String?) {
    when (messageType) {
        "loadbanneradasync", "hidebanneradasync" -> {
            val content = buildGameAdSuccessPayload(payload, messageType)
            if (dispatchGameEvent(target, "hidebannerad", content)) {
                Logger.i(TAG, "Dispatched hidebannerad for game banner message type=$messageType")
            }
        }
    }
}

fun rememberGameAdPayload(target: Any?, payload: Any?, messageType: String?) {
    if (target == null || payload !is JSONObject || messageType !in GAME_AD_MESSAGE_TYPES) return

    val now = System.currentTimeMillis()
    recentGameAdTargets[target] = now

    val snapshotPayload = runCatching { JSONObject(payload.toString()) }.getOrNull() ?: payload
    extractGameAdContent(snapshotPayload)
        ?.optString("adInstanceID")
        ?.takeIf { it.isNotBlank() }
        ?.let { adInstanceId ->
            messageType?.let { type -> gameAdInstanceTypes[adInstanceId] = type }
        }
    extractPromiseId(snapshotPayload)?.let { promiseId ->
        gameAdPromiseSnapshots.entries.removeIf { now - it.value.timestampMs > GAME_AD_PROMISE_WINDOW_MS }
        gameAdPromiseSnapshots[promiseId] = GameAdPromiseSnapshot(snapshotPayload, messageType, now)
    }
    synchronized(recentGameAdPayloads) {
        recentGameAdPayloads.removeAll { now - it.timestampMs > GAME_AD_RECENT_WINDOW_MS }
        recentGameAdPayloads.add(GameAdPayloadSnapshot(target, snapshotPayload, messageType, now))
        while (recentGameAdPayloads.size > 20) {
            recentGameAdPayloads.removeAt(0)
        }
    }
}

fun completeRecentGameAdRequests(source: String) {
    val now = System.currentTimeMillis()
    val snapshots = synchronized(recentGameAdPayloads) {
        recentGameAdPayloads.removeAll { now - it.timestampMs > GAME_AD_RECENT_WINDOW_MS }
        recentGameAdPayloads.toList()
    }

    var resolved = 0
    snapshots.asReversed().forEach { snapshot ->
        if (shouldAutofixGameAdMessage(snapshot.messageType) &&
            resolveGameAdPayload(snapshot.target, snapshot.payload, snapshot.messageType)
        ) {
            dispatchPostResolveGameAdSignals(snapshot.target, snapshot.payload, snapshot.messageType)
            resolved++
        }
    }

    val targets = synchronized(recentGameAdTargets) {
        recentGameAdTargets.entries.removeIf { now - it.value > GAME_AD_RECENT_WINDOW_MS }
        recentGameAdTargets.keys.toList()
    }
    targets.forEach { target ->
        dispatchGameEvent(target, "hidebannerad", JSONObject().put("completed", true))
    }

    if (resolved > 0) {
        Logger.i(TAG, "Re-resolved $resolved recent game ad request(s) via $source")
    }
}

fun shouldConvertGameAdRejectToSuccess(promiseId: String, reason: String): Boolean {
    val snapshot = gameAdPromiseSnapshots[promiseId]
    if (shouldAutofixGameAdMessage(snapshot?.messageType)) return true

    val normalized = reason.lowercase()
    if (!isRecentGameAdActivityClose()) return false
    return normalized.contains("banner")
}

fun shouldAutofixGameAdMessage(messageType: String?): Boolean {
    return messageType in GAME_AD_AUTOFIX_MESSAGE_TYPES
}

fun rejectUnavailableGameAdPayloadIfNeeded(
    target: Any?,
    payload: Any?,
    messageType: String?,
    source: String
): Boolean {
    if (!shouldMakeGameAdUnavailable(payload, messageType)) return false

    if (!rejectGameAdPayload(
            target,
            payload,
            GAME_AD_UNAVAILABLE_MESSAGE,
            GAME_AD_UNAVAILABLE_CODE
        )
    ) {
        Logger.w(TAG, "Unable to mark rewarded game ad unavailable via $source type=$messageType")
        return false
    }

    lastUnavailableGameAdMs.set(System.currentTimeMillis())
    Logger.i(TAG, "Marked rewarded game ad unavailable via $source type=$messageType")
    return true
}

fun shouldMakeGameAdUnavailable(payload: Any?, messageType: String?): Boolean {
    if (messageType in GAME_AD_UNAVAILABLE_MESSAGE_TYPES) return true
    if (messageType !in setOf("loadadasync", "showadasync")) return false

    val content = extractGameAdContent(payload)
    val adInstanceId = content?.optString("adInstanceID")?.takeIf { it.isNotBlank() }
    val knownType = adInstanceId?.let { gameAdInstanceTypes[it] }
    if (knownType in GAME_AD_UNAVAILABLE_MESSAGE_TYPES) return true

    val placementText = listOf(
        content?.optString("placementID").orEmpty(),
        content?.optString("adType").orEmpty(),
        content?.optString("type").orEmpty(),
        content?.optString("format").orEmpty()
    ).joinToString(" ").lowercase()
    if (placementText.contains("reward")) return true

    return payload?.toString()?.lowercase()?.contains("rewarded") == true
}

fun isRecentUnavailableGameAd(): Boolean {
    val rejectedAt = lastUnavailableGameAdMs.get()
    return rejectedAt > 0 && System.currentTimeMillis() - rejectedAt < GAME_AD_RECENT_WINDOW_MS
}

fun isRecentGameAdActivityClose(): Boolean {
    val closedAt = lastGameAdActivityCloseMs.get()
    return closedAt > 0 && System.currentTimeMillis() - closedAt < 15_000L
}

fun gameAdPromiseTypeFromReason(reason: String): String? {
    val normalized = reason.lowercase()
    return when {
        normalized.contains("reward") && normalized.contains("interstitial") -> "getrewardedinterstitialasync"
        normalized.contains("reward") -> "getrewardedvideoasync"
        normalized.contains("interstitial") -> "getinterstitialadasync"
        normalized.contains("banner") -> "loadbanneradasync"
        normalized.contains("show") || normalized.contains("watch") || normalized.contains("complete") -> "showadasync"
        normalized.contains("load") -> "loadadasync"
        else -> null
    }
}

fun hasRecentGameAdRequest(): Boolean {
    val now = System.currentTimeMillis()
    return synchronized(recentGameAdPayloads) {
        recentGameAdPayloads.removeAll { now - it.timestampMs > GAME_AD_RECENT_WINDOW_MS }
        recentGameAdPayloads.isNotEmpty()
    }
}

fun hookPlayableAdActivity(module: XposedModule, method: Method) {
    module.hook(method).intercept { chain ->
        val res = chain.proceed()
        val activity = chain.thisObject as? Activity ?: return@intercept res
        if (activity.javaClass.name != method.declaringClass.name) return@intercept res
        handleGameAdActivity(activity, "direct hook ${method.declaringClass.name}.${method.name}")
        res
    }
}

fun hookGlobalGameAdActivityLifecycleFallback(module: XposedModule) {
    val onResume = (Activity::class.java.declaredMethods + Activity::class.java.methods).firstOrNull { method ->
        method.name == "onResume" && method.parameterCount == 0
    }?.apply { isAccessible = true } ?: return

    module.hook(onResume).intercept { chain ->
        val res = chain.proceed()
        val activity = chain.thisObject as? Activity ?: return@intercept res
        val isGameAdActivity = activity.javaClass.name in GAME_AD_ACTIVITY_CLASS_NAMES
        if (!(ENABLE_GAME_AD_DIAGNOSTICS && isGameAdActivity)) {
            scheduleGameAdSurfaceSweep(activity.window?.decorView, "activity resume ${activity.javaClass.name}")
        }
        if (activity.javaClass.name !in GAME_AD_ACTIVITY_CLASS_NAMES) return@intercept res
        markGameAdDiagnosticFlow("activity.onResume ${activity.javaClass.name}")
        logGameAdDiagnostic(
            "activity.onResume",
            "${activity.javaClass.name} intent=${formatDiagValue(activity.intent)}"
        )
        handleGameAdActivity(activity, "global lifecycle fallback")
        res
    }

    Logger.i(TAG, "Hooked global game ad activity lifecycle fallback")
}

fun hookGameAdActivityLaunchFallbacks(module: XposedModule) {
    val methods = LinkedHashMap<String, Method>()
    listOf(Instrumentation::class.java, Activity::class.java, ContextWrapper::class.java).forEach { type ->
        (type.declaredMethods + type.methods)
            .filter { method ->
                method.name in setOf("execStartActivity", "startActivity", "startActivityForResult", "startActivityIfNeeded") &&
                    method.parameterTypes.any { it == Intent::class.java }
            }
            .forEach { method ->
                method.isAccessible = true
                val signature = buildString {
                    append(method.declaringClass.name)
                    append('.')
                    append(method.name)
                    append('(')
                    append(method.parameterTypes.joinToString(",") { it.name })
                    append(')')
                }
                methods.putIfAbsent(signature, method)
            }
    }

    var hooked = 0
    methods.values.forEach { method ->
        runCatching {
            hookGameAdActivityLaunchMethod(module, method)
            hooked++
        }.onFailure {
            Logger.w(TAG, "Failed to hook game ad launch fallback ${method.declaringClass.name}.${method.name}", it)
        }
    }
    Logger.i(TAG, "Hooked $hooked game ad activity launch fallback method(s)")
}

fun hookGameAdActivityLaunchMethod(module: XposedModule, method: Method) {
    module.hook(method).intercept { chain ->
        val intent = chain.args.firstOrNull { it is Intent } as? Intent ?: return@intercept chain.proceed()
        val blockedClassName = resolveBlockedGameAdActivity(intent) ?: return@intercept run {
            val res = chain.proceed()
            val intentAfter = chain.args.firstOrNull { it is Intent } as? Intent ?: return@run res
            val blockedClassNameAfter = resolveBlockedGameAdActivity(intentAfter) ?: return@run res
            logGameAdDiagnostic(
                "activity.launch.after",
                "${methodSignature(method)} target=$blockedClassNameAfter result=${formatDiagValue(res)}"
            )
            res
        }

        markGameAdDiagnosticFlow("activity.launch $blockedClassName")
        logGameAdDiagnostic(
            "activity.launch.before",
            "${methodSignature(method)} target=$blockedClassName args=${formatDiagArgs(chain.args)}"
        )
        
        if (ENABLE_GAME_AD_AUTOFIX && shouldBlockGameAdActivityLaunch(blockedClassName)) {
            completeRecentGameAdRequests("launch fallback $blockedClassName")
            Logger.i(
                TAG,
                "Blocked game ad activity launch to $blockedClassName via ${method.declaringClass.name}.${method.name}"
            )
            return@intercept if (method.returnType == Boolean::class.javaPrimitiveType) {
                false
            } else {
                null
            }
        }

        val res = chain.proceed()
        logGameAdDiagnostic(
            "activity.launch.after",
            "${methodSignature(method)} target=$blockedClassName result=${formatDiagValue(res)}"
        )
        res
    }
}

fun shouldBlockGameAdActivityLaunch(className: String): Boolean {
    return className in HARD_BLOCKED_GAME_AD_ACTIVITY_CLASS_NAMES ||
        (className in setOf(AUDIENCE_NETWORK_ACTIVITY_CLASS, AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS) &&
            isRecentUnavailableGameAd())
}

fun resolveBlockedGameAdActivity(intent: Intent): String? {
    val explicitTarget = intent.component?.className
    if (explicitTarget != null && explicitTarget in GAME_AD_ACTIVITY_CLASS_NAMES) {
        return explicitTarget
    }
    return null
}

fun handleGameAdActivity(activity: Activity, source: String) {
    if (!ENABLE_GAME_AD_AUTOFIX) {
        markGameAdDiagnosticFlow("activity.handle ${activity.javaClass.name}")
        logGameAdDiagnostic(
            "activity.handle.passive",
            "${activity.javaClass.name} source=$source intent=${formatDiagValue(activity.intent)}"
        )
        dumpAudienceNetworkActivityState(activity, "activity.handle.passive")
        return
    }

    when (activity.javaClass.name) {
        AUDIENCE_NETWORK_ACTIVITY_CLASS,
        AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS -> {
            markGameAdDiagnosticFlow("activity.handle ${activity.javaClass.name}")
            logGameAdDiagnostic(
                "activity.handle.audienceNetworkCompat",
                "${activity.javaClass.name} source=$source intent=${formatDiagValue(activity.intent)}"
            )
            dumpAudienceNetworkActivityState(activity, "activity.handle.audienceNetworkCompat")
            completeAudienceNetworkRewardObject(activity, source)
            finishGameAdActivity(activity, source)
        }
        else -> finishGameAdActivity(activity, source)
    }
}

fun finishGameAdActivity(activity: Activity, source: String) {
    if (activity.isFinishing) return
    lastGameAdActivityCloseMs.set(System.currentTimeMillis())
    completeRecentGameAdRequests(source)
    if (activity.javaClass.name in GAME_AD_ACTIVITY_CLASS_NAMES) {
        activity.setResult(Activity.RESULT_OK, buildGameAdActivityResultIntent())
    } else {
        activity.setResult(Activity.RESULT_CANCELED, Intent())
    }
    activity.finish()
    Logger.i(TAG, "Closed game ad activity ${activity.javaClass.name} via $source")
}

fun buildGameAdActivityResultIntent(): Intent {
    return Intent().apply {
        putExtra("success", true)
    }
}

fun hookGlobalGameAdSurfaceFallbacks(module: XposedModule) {
    if (!gameAdSurfaceHooksInstalled.compareAndSet(0, 1)) return

    var hooked = 0
    (ViewGroup::class.java.declaredMethods + ViewGroup::class.java.methods)
        .filter { method ->
            method.name == "addView" &&
                method.parameterTypes.any { it == View::class.java }
        }
        .distinctBy { method ->
            method.name + method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
        }
        .forEach { method ->
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val parent = chain.thisObject as? ViewGroup
                val child = chain.args.firstOrNull { it is View } as? View
                val res = chain.proceed()
                if (child != null) {
                    if (isPotentialNativeGameAdView(child)) {
                        hideLikelyAdContainer(child, "native ad view add ${child.javaClass.name}")
                        scheduleGameAdSurfaceSweep(child, "native ad view add ${child.javaClass.name}")
                    } else if (isPotentialExplicitFeedAdMarkerView(child)) {
                        hideLikelyAdContainer(child, "explicit feed ad view add ${child.javaClass.name}")
                        scheduleGameAdSurfaceSweep(child, "explicit feed ad view add ${child.javaClass.name}")
                    } else if (ENABLE_FEED_UI_MARKER_FALLBACKS && isPotentialFeedAdMarkerView(child)) {
                        hideLikelyAdContainer(child, "feed ad marker view add ${child.javaClass.name}")
                        scheduleGameAdSurfaceSweep(child, "feed ad marker view add ${child.javaClass.name}")
                    } else if (ENABLE_FEED_UI_MARKER_FALLBACKS && isPotentialFeedReelCtaAdMarkerView(child)) {
                        hideLikelyFeedReelCtaAdContainer(child, "feed reel CTA view add ${child.javaClass.name}")
                        scheduleGameAdSurfaceSweep(child, "feed reel CTA view add ${child.javaClass.name}")
                    } else if (shouldScheduleFeedRowSweep(parent, child)) {
                        scheduleFeedRowSweep(child, "feed row add ${child.javaClass.name}")
                    } else if (child is WebView) {
                        injectGameAdHidingScript(child)
                    }
                }
                res
            }
            hooked++
        }

    (TextView::class.java.declaredMethods + TextView::class.java.methods)
        .filter { method ->
            method.name == "setText" &&
                method.parameterTypes.isNotEmpty() &&
                CharSequence::class.java.isAssignableFrom(method.parameterTypes[0])
        }
        .distinctBy { method ->
            method.name + method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
        }
        .forEach { method ->
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val textView = chain.thisObject as? TextView
                val res = chain.proceed()
                if (textView != null) {
                    if (isExplicitFeedAdMarkerText(textView.text)) {
                        hideLikelyAdContainer(textView, "explicit feed ad text")
                    } else if (ENABLE_FEED_UI_MARKER_FALLBACKS) {
                        if (isAnyAdMarkerText(textView.text)) {
                            hideLikelyAdContainer(textView, "ad marker text")
                        } else if (isFeedReelCtaAdMarkerText(textView.text)) {
                            hideLikelyFeedReelCtaAdContainer(textView, "feed reel CTA text")
                        }
                    }
                }
                res
            }
            hooked++
        }

    (View::class.java.declaredMethods + View::class.java.methods)
        .filter { method ->
            method.name == "setContentDescription" &&
                method.parameterTypes.size == 1 &&
                CharSequence::class.java.isAssignableFrom(method.parameterTypes[0])
        }
        .distinctBy { method ->
            method.name + method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
        }
        .forEach { method ->
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val view = chain.thisObject as? View
                val res = chain.proceed()
                if (view != null) {
                    if (isExplicitFeedAdMarkerText(view.contentDescription)) {
                        hideLikelyAdContainer(view, "explicit feed ad content description")
                    } else if (ENABLE_FEED_UI_MARKER_FALLBACKS) {
                        if (isFeedAdMarkerText(view.contentDescription)) {
                            hideLikelyAdContainer(view, "feed ad content description")
                        } else if (isFeedReelCtaAdMarkerText(view.contentDescription)) {
                            hideLikelyFeedReelCtaAdContainer(view, "feed reel CTA content description")
                        }
                    }
                }
                res
            }
            hooked++
        }

    (WebView::class.java.declaredMethods + WebView::class.java.methods)
        .filter { method ->
            method.name in setOf("loadUrl", "loadData", "loadDataWithBaseURL") ||
                method.name == "onAttachedToWindow"
        }
        .distinctBy { method ->
            method.name + method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
        }
        .forEach { method ->
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val webView = chain.thisObject as? WebView
                val res = chain.proceed()
                if (webView != null) {
                    injectGameAdHidingScript(webView)
                    scheduleGameAdSurfaceSweep(webView, "webview ${method.name}")
                }
                res
            }
            hooked++
        }

    Logger.i(TAG, "Hooked $hooked global ad surface fallback method(s)")
}

fun scheduleGameAdSurfaceSweep(view: View?, reason: String) {
    val root = view?.rootView ?: view ?: return
    longArrayOf(0L, 250L, 1_000L, 2_500L, 5_000L).forEach { delayMs ->
        root.postDelayed({
            sweepGameAdSurface(root, reason)
        }, delayMs)
    }
}

fun shouldScheduleFeedRowSweep(parent: ViewGroup?, child: View?): Boolean {
    if (parent == null || child !is ViewGroup) return false
    return parent.javaClass.name.contains("RecyclerView")
}

fun extractPromiseId(payload: Any?): String? {
    if (payload == null) return null
    val content = extractGameAdContent(payload)
    return content?.optString("promiseID")?.takeIf { it.isNotBlank() }
        ?: content?.optString("promiseId")?.takeIf { it.isNotBlank() }
        ?: (payload as? JSONObject)?.optString("promiseID")?.takeIf { it.isNotBlank() }
}

fun extractGameAdContent(payload: Any?): JSONObject? {
    return (payload as? JSONObject)?.optJSONObject("content")
}

fun buildGameAdPayloadFromServiceBundle(bundle: Bundle, messageType: String): JSONObject {
    val payload = JSONObject()
    payload.put("type", messageType)
    payload.put("content", bundleToJsonObject(bundle))
    return payload
}

fun resolveGameAdInstanceId(placementId: String, messageType: String?, bannerPosition: String?): String {
    return buildString {
        append(GAME_AD_SUCCESS_INSTANCE_PREFIX)
        append('_')
        append(placementId)
        if (messageType != null) {
            append('_')
            append(messageType)
        }
        if (bannerPosition != null) {
            append('_')
            append(bannerPosition)
        }
    }
}

fun buildGameAdSuccessPayload(payload: Any?, messageType: String? = null): JSONObject {
    val effectiveMessageType = messageType
        ?: (payload as? JSONObject)?.optString("type").orEmpty()
    val content = extractGameAdContent(payload)
    val result = JSONObject()

    val placementId = content?.optString("placementID")?.takeIf { it.isNotBlank() }
    val requestedAdInstanceId = content?.optString("adInstanceID")?.takeIf { it.isNotBlank() }
    val bannerPosition = content?.optString("bannerPosition")?.takeIf { it.isNotBlank() }

    result.put("success", true)
    if (effectiveMessageType.contains("reward", ignoreCase = true)) {
        result.put("completed", true)
        result.put("didComplete", true)
        result.put("watched", true)
        result.put("rewarded", true)
        result.put("completionGesture", "post")
    }

    if (placementId != null) {
        result.put("placementID", placementId)
    }
    if (bannerPosition != null) {
        result.put("bannerPosition", bannerPosition)
    }

    val adInstanceId = when {
        requestedAdInstanceId != null -> {
            gameAdInstanceIds.putIfAbsent(requestedAdInstanceId, requestedAdInstanceId)
            requestedAdInstanceId
        }
        placementId != null && effectiveMessageType != "loadbanneradasync" ->
            resolveGameAdInstanceId(placementId, effectiveMessageType, bannerPosition)
        else -> null
    }

    if (adInstanceId != null) {
        result.put("adInstanceID", adInstanceId)
        effectiveMessageType.takeIf { it.isNotBlank() }?.let { type ->
            gameAdInstanceTypes[adInstanceId] = type
        }
    }

    return result
}

fun forceGameAdSuccessResult(
    promiseId: String,
    original: Any?,
    payload: JSONObject?,
    messageType: String?
): JSONObject {
    val result = when (original) {
        is JSONObject -> copyJsonObject(original)
        else -> JSONObject()
    }
    val success = buildGameAdSuccessPayload(
        payload ?: JSONObject().put("content", JSONObject().put("promiseID", promiseId)),
        messageType
    )

    val keys = success.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        result.put(key, success.opt(key))
    }

    result.put("success", true)
    if (messageType?.contains("reward", ignoreCase = true) == true) {
        result.put("completed", true)
        result.put("didComplete", true)
        result.put("watched", true)
        result.put("rewarded", true)
        result.put("completionGesture", "post")
    }
    return result
}

fun hookGameAdRequest(module: XposedModule, method: Method) {
    module.hook(method).intercept { chain ->
        val payload = chain.args.getOrNull(0) as? JSONObject ?: return@intercept chain.proceed()
        val messageType = inferGameAdMessageType(method, payload)
        rememberGameAdPayload(chain.thisObject, payload, messageType)

        if (rejectUnavailableGameAdPayloadIfNeeded(chain.thisObject, payload, messageType, "direct hook")) {
            return@intercept null
        }

        if (ENABLE_GAME_AD_AUTOFIX && shouldAutofixGameAdMessage(messageType)) {
            if (resolveGameAdPayload(chain.thisObject, payload, messageType)) {
                dispatchPostResolveGameAdSignals(chain.thisObject, payload, messageType)
                Logger.i(TAG, "Autofixed game ad request message type=$messageType")
                return@intercept null
            }
        }
        chain.proceed()
    }
}

fun hookGameAdBridge(module: XposedModule, method: Method) {
    module.hook(method).intercept { chain ->
        val eventType = chain.args.getOrNull(0) as? String ?: return@intercept chain.proceed()
        val rawPayload = chain.args.getOrNull(1) as? String ?: return@intercept chain.proceed()
        val payload = runCatching { JSONObject(rawPayload) }.getOrNull() ?: return@intercept chain.proceed()
        val messageType = payload.optString("type").takeIf { it.isNotBlank() }

        rememberGameAdPayload(chain.thisObject, payload, messageType)

        if (eventType == "resolve" && shouldConvertGameAdRejectToSuccess(
                extractPromiseId(payload).orEmpty(),
                "bridge resolve"
            )
        ) {
            val content = extractGameAdContent(payload)
            if (content?.optBoolean("success") == false) {
                val promiseId = extractPromiseId(payload).orEmpty()
                val fixed = forceGameAdSuccessResult(promiseId, payload, null, messageType)
                val newArgs = chain.args.toTypedArray()
                newArgs[1] = fixed.toString()
                Logger.i(TAG, "Converted bridge reject to success for promiseID=$promiseId type=$messageType")
                return@intercept chain.proceed(newArgs)
            }
        }
        chain.proceed()
    }
}

fun hookGameAdResultMethods(module: XposedModule, bridgeClass: Class<*>) {
    val methods = bridgeClass.declaredMethods.filter { method ->
        method.name in setOf("resolve", "reject") &&
            !Modifier.isStatic(method.modifiers) &&
            method.parameterCount >= 1 &&
            method.parameterTypes[0] == String::class.java
    }

    methods.forEach { method ->
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            val promiseId = chain.args.getOrNull(0) as? String ?: return@intercept chain.proceed()
            val snapshot = gameAdPromiseSnapshots[promiseId] ?: return@intercept chain.proceed()
            if (method.name == "reject" && shouldConvertGameAdRejectToSuccess(
                    promiseId,
                    chain.args.getOrNull(1)?.toString().orEmpty()
                )
            ) {
                val originalPayload = chain.args.getOrNull(3) as? JSONObject
                val fixed = forceGameAdSuccessResult(
                    promiseId,
                    originalPayload,
                    snapshot.payload,
                    snapshot.messageType
                )
                val resolveMethod = resolveGameAdResolveMethod(bridgeClass)
                if (resolveMethod != null) {
                    runCatching {
                        resolveMethod.invoke(chain.thisObject, promiseId, fixed)
                        Logger.i(
                            TAG,
                            "Intercepted game ad reject and forced success for promiseID=$promiseId type=${snapshot.messageType}"
                        )
                        return@intercept null
                    }.onFailure {
                        Logger.e(TAG, "Failed to force success from intercepted reject for promiseID=$promiseId", it)
                    }
                }
            }
            chain.proceed()
        }
    }

    if (methods.isNotEmpty()) {
        Logger.i(TAG, "Hooked ${methods.size} game ad result helper(s) on ${bridgeClass.name}")
    }
}

fun hookGameAdServiceDispatchMethods(module: XposedModule, bridgeClass: Class<*>) {
    val methods = bridgeClass.declaredMethods.filter { method ->
        method.name == "postMessage" &&
            !Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 1 &&
            method.parameterTypes[0] == Bundle::class.java
    }

    methods.forEach { method ->
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            val bundle = chain.args.getOrNull(0) as? Bundle ?: return@intercept chain.proceed()
            val messageType = bundle.getString("type")
            if (messageType == null) {
                Logger.w(TAG, "Skipped game ad service bundle processing; missing message type")
                return@intercept chain.proceed()
            }

            val payload = buildGameAdPayloadFromServiceBundle(bundle, messageType)
            rememberGameAdPayload(chain.thisObject, payload, messageType)

            if (rejectUnavailableGameAdPayloadIfNeeded(chain.thisObject, payload, messageType, "service bundle")) {
                return@intercept null
            }

            if (ENABLE_GAME_AD_AUTOFIX && shouldAutofixGameAdMessage(messageType)) {
                if (resolveGameAdPayload(chain.thisObject, payload, messageType)) {
                    dispatchPostResolveGameAdSignals(chain.thisObject, payload, messageType)
                    Logger.i(TAG, "Autofixed game ad service request message type=$messageType")
                    return@intercept null
                }
            }
            chain.proceed()
        }
    }

    if (methods.isNotEmpty()) {
        Logger.i(TAG, "Hooked ${methods.size} game ad service dispatch helper(s) on ${bridgeClass.name}")
    }
}

fun hookGameAdSystemDiagnostics(module: XposedModule, classLoader: ClassLoader) {
    if (!gameAdSystemDiagnosticsInstalled.compareAndSet(0, 1)) return

    val handlerClass = runCatching { classLoader.loadClass("android.os.Handler") }.getOrNull()
    val handleMessage = handlerClass?.declaredMethods?.firstOrNull { method ->
        method.name == "handleMessage" &&
            method.parameterCount == 1 &&
            method.parameterTypes[0] == Message::class.java
    }?.apply { isAccessible = true }

    if (handleMessage != null) {
        module.hook(handleMessage).intercept { chain ->
            val handler = chain.thisObject as? Handler
            val message = chain.args.getOrNull(0) as? Message
            if (handler != null && message != null) {
                val ownerName = handler.javaClass.name

                if (isRecentGameAdDiagnosticFlow()) {
                    if (ownerName.contains("audiencenetwork", ignoreCase = true) ||
                        ownerName.contains("neko", ignoreCase = true) ||
                        ownerName.contains("game", ignoreCase = true)
                    ) {
                        logGameAdDiagnostic(
                            "handler.handleMessage",
                            "$ownerName message=${formatDiagValue(message)}"
                        )
                        hookDynamicGameAdDiagnostic(module, handler.javaClass)
                    } else if (ENABLE_BROAD_HANDLER_GAME_AD_DIAGNOSTICS) {
                        logGameAdDiagnostic(
                            "handler.handleMessage.broad",
                            "$ownerName message=${formatDiagValue(message)}"
                        )
                    }
                }

                if (ownerName.contains("audiencenetwork.internal", ignoreCase = true) &&
                    ownerName.contains("ipc", ignoreCase = true)
                ) {
                    val replyTo = message.replyTo
                    if (replyTo != null) {
                        logGameAdDiagnostic(
                            "audienceNetwork.ipc.replyTo",
                            "$ownerName replyTo=${formatDiagValue(replyTo)}"
                        )
                    }
                }
            }
            chain.proceed()
        }
    }

    val messengerClass = runCatching { classLoader.loadClass("android.os.Messenger") }.getOrNull()
    val send = messengerClass?.declaredMethods?.firstOrNull { method ->
        method.name == "send" &&
            method.parameterCount == 1 &&
            method.parameterTypes[0] == Message::class.java
    }?.apply { isAccessible = true }

    if (send != null) {
        module.hook(send).intercept { chain ->
            if (isRecentGameAdDiagnosticFlow()) {
                val message = chain.args.getOrNull(0) as? Message
                logGameAdDiagnostic(
                    "messenger.send",
                    "target=${formatDiagValue(chain.thisObject)} message=${formatDiagValue(message)}"
                )
            }
            chain.proceed()
        }
    }

    Logger.i(TAG, "Hooked game ad system diagnostics")
}

fun hookDynamicGameAdDiagnostic(module: XposedModule, type: Class<*>) {
    if (!ENABLE_GAME_AD_DIAGNOSTICS) return
    if (!gameAdDiagnosticClassesHooked.add(type.name)) return

    var hooked = 0
    (type.declaredMethods + type.methods).forEach { method ->
        if (Modifier.isAbstract(method.modifiers) || method.isBridge || method.isSynthetic) return@forEach
        val name = method.name
        if (name == "toString" || name == "hashCode" || name == "equals" || name == "getClass") return@forEach

        runCatching {
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                if (isRecentGameAdDiagnosticFlow()) {
                    logGameAdDiagnostic(
                        "dynamic.before",
                        "${methodSignature(method)} args=${formatDiagArgs(chain.args)}"
                    )
                }
                val res = runCatching { chain.proceed() }
                if (isRecentGameAdDiagnosticFlow()) {
                    logGameAdDiagnostic(
                        "dynamic.after",
                        "${methodSignature(method)} result=${formatDiagValue(res.getOrNull())} throwable=${formatDiagThrowable(res.exceptionOrNull())}"
                    )
                }
                res.getOrThrow()
            }
            hooked++
        }
    }

    if (hooked > 0) {
        Logger.i(TAG, "Installed $hooked dynamic diagnostic hooks on ${type.name}")
    }
}

fun markGameAdDiagnosticFlow(reason: String) {
    if (!ENABLE_GAME_AD_DIAGNOSTICS) return
    lastGameAdDiagnosticFlowMs.set(System.currentTimeMillis())
    Logger.i(TAG, "Marked start of game ad diagnostic flow via $reason")
}

fun isRecentGameAdDiagnosticFlow(): Boolean {
    val startAt = lastGameAdDiagnosticFlowMs.get()
    return startAt > 0 && System.currentTimeMillis() - startAt < GAME_AD_DIAG_FLOW_WINDOW_MS
}

fun logGameAdDiagnostic(tag: String, msg: String) {
    if (!ENABLE_GAME_AD_DIAGNOSTICS) return
    val count = gameAdDiagnosticLogCount.incrementAndGet()
    if (count > GAME_AD_DIAG_LOG_LIMIT) return

    Logger.i(TAG, "GameAdDiag[$tag] ${msg.take(GAME_AD_DIAG_TEXT_LIMIT)}")
}

fun methodSignature(method: Method): String {
    return "${method.declaringClass.simpleName}.${method.name}"
}

fun formatDiagArgs(args: List<Any?>?): String {
    if (args == null) return "null"
    return args.joinToString(", ", prefix = "[", postfix = "]") { formatDiagValue(it) }
}

fun formatDiagValue(value: Any?): String {
    if (value == null) return "null"
    return when (value) {
        is String -> "\"$value\""
        is Number, is Boolean -> value.toString()
        is Intent -> "Intent(action=${value.action} component=${value.component} extras=${formatDiagBundle(value.extras)})"
        is Bundle -> "Bundle${formatDiagBundle(value)}"
        is Message -> "Message(what=${value.what} arg1=${value.arg1} arg2=${value.arg2} obj=${formatDiagValue(value.obj)} data=${formatDiagBundle(value.peekData())})"
        is Messenger -> "Messenger(binder=${value.binder})"
        is JSONObject -> "JSONObject($value)"
        else -> "${value.javaClass.simpleName}@${System.identityHashCode(value)}"
    }
}

fun formatDiagBundle(bundle: Bundle?): String {
    if (bundle == null) return "null"
    val keys = runCatching { bundle.keySet() }.getOrNull() ?: return "{<locked>}"
    return keys.joinToString(", ", prefix = "{", postfix = "}") { key ->
        "$key=${formatDiagValue(runCatching { bundle.get(key) }.getOrNull())}"
    }
}

fun formatDiagThrowable(t: Throwable?): String {
    if (t == null) return "null"
    return "${t.javaClass.simpleName}(${t.message})"
}

fun dumpAudienceNetworkActivityState(activity: Activity, source: String) {
    if (!ENABLE_GAME_AD_DIAGNOSTICS) return
    val now = System.currentTimeMillis()
    val shouldDump = synchronized(audienceNetworkActivityStateDumps) {
        val previous = audienceNetworkActivityStateDumps[activity]
        if (previous != null && now - previous < 10_000L) {
            false
        } else {
            audienceNetworkActivityStateDumps[activity] = now
            true
        }
    }
    if (!shouldDump) return

    val root = activity.window?.decorView ?: return
    logGameAdDiagnostic("activity.dumpState.start", "${activity.javaClass.name} source=$source")

    val seen = IdentityHashMap<Any, Boolean>()
    val queue = java.util.ArrayDeque<AudienceNetworkGraphNode>()
    queue.add(AudienceNetworkGraphNode(activity, "activity", 0))

    var dumped = 0
    while (queue.isNotEmpty() && dumped < AUDIENCE_NETWORK_STATE_DUMP_LIMIT) {
        val node = queue.removeFirst()
        if (seen.put(node.value, true) != null) continue

        val type = node.value.javaClass
        logGameAdDiagnostic("activity.dumpState.node", "path=${node.path} type=${type.name} value=${formatDiagValue(node.value)}")
        dumped++

        if (node.depth >= 6) continue
        if (node.value is View && node.depth >= 1) continue

        audienceNetworkFieldsFor(type).forEach { field ->
            val fieldValue = runCatching { field.get(node.value) }.getOrNull() ?: return@forEach
            if (shouldQueueAudienceNetworkObject(fieldValue)) {
                queue.add(AudienceNetworkGraphNode(fieldValue, "${node.path}.${field.name}", node.depth + 1))
            }
        }
    }

    logGameAdDiagnostic("activity.dumpState.end", "${activity.javaClass.name} nodes=$dumped")
}
