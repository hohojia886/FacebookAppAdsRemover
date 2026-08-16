package tn.loukious.facebookappadsremover

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal val gameAdInstanceIds = ConcurrentHashMap<String, String>()
internal val gameAdInstanceTypes = ConcurrentHashMap<String, String>()
internal val gameAdPromiseSnapshots = ConcurrentHashMap<String, GameAdPromiseSnapshot>()
internal val recentGameAdTargets = Collections.synchronizedMap(WeakHashMap<Any, Long>())
internal val recentGameAdPayloads = Collections.synchronizedList(ArrayList<GameAdPayloadSnapshot>())

internal data class GameAdPayloadSnapshot(
    val target: Any,
    val payload: JSONObject,
    val messageType: String?,
    val timestampMs: Long
)

internal data class GameAdPromiseSnapshot(
    val payload: JSONObject,
    val messageType: String?,
    val timestampMs: Long
)

internal val GAME_AD_MESSAGE_TYPES = setOf(
    "getinterstitialadasync",
    "getrewardedvideoasync",
    "getrewardedinterstitialasync",
    "loadadasync",
    "showadasync",
    "loadbanneradasync",
    "hidebanneradasync"
)

internal val GAME_AD_AUTOFIX_MESSAGE_TYPES = setOf(
    "loadbanneradasync",
    "hidebanneradasync"
)

internal val GAME_AD_UNAVAILABLE_MESSAGE_TYPES = setOf(
    "getrewardedvideoasync",
    "getrewardedinterstitialasync"
)

internal val GAME_AD_ACTIVITY_CLASS_NAMES = setOf(
    AUDIENCE_NETWORK_ACTIVITY_CLASS,
    AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS,
    NEKO_PLAYABLE_ACTIVITY_CLASS
)

internal val HARD_BLOCKED_GAME_AD_ACTIVITY_CLASS_NAMES = setOf(
    NEKO_PLAYABLE_ACTIVITY_CLASS
)

internal val GAME_AD_METHOD_TAGS = listOf(
    "Invalid JSON content received by onGetInterstitialAdAsync: ",
    "Invalid JSON content received by onGetRewardedInterstitialAsync: ",
    "Invalid JSON content received by onRewardedVideoAsync: ",
    "Invalid JSON content received by onLoadAdAsync: ",
    "Invalid JSON content received by onShowAdAsync: "
)

internal fun hookGameAdRequest(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val payload = param.args.getOrNull(0) ?: return
            val messageType = inferGameAdMessageType(method, payload)
            markGameAdDiagnosticFlow("request ${method.declaringClass.name}.${method.name}")
            logGameAdDiagnostic(
                "request.before",
                "${methodSignature(method)} type=$messageType this=${formatDiagValue(param.thisObject)} args=${formatDiagArgs(param.args)}"
            )
            rememberGameAdPayload(param.thisObject, payload, messageType)
            if (!ENABLE_GAME_AD_AUTOFIX) return
            if (rejectUnavailableGameAdPayloadIfNeeded(param.thisObject, payload, messageType, "request ${method.declaringClass.name}.${method.name}")) {
                param.result = null
                return
            }
            if (!shouldAutofixGameAdMessage(messageType)) return

            if (resolveGameAdPayload(param.thisObject, payload, messageType)) {
                dispatchPostResolveGameAdSignals(param.thisObject, payload, messageType)
                param.result = null
                Logger.i(
                    TAG,
                    "Resolved game ad request as success in ${method.declaringClass.name}.${method.name}"
                )
            } else if (rejectGameAdPayload(param.thisObject, payload)) {
                param.result = null
                Logger.i(
                    TAG,
                    "Rejected game ad request in ${method.declaringClass.name}.${method.name}"
                )
            } else {
                Logger.w(
                    TAG,
                    "Unable to resolve or reject game ad request ${method.declaringClass.name}.${method.name}"
                )
            }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            val payload = param.args.getOrNull(0) ?: return
            val messageType = inferGameAdMessageType(method, payload)
            logGameAdDiagnostic(
                "request.after",
                "${methodSignature(method)} type=$messageType result=${formatDiagValue(param.result)} throwable=${formatDiagThrowable(param.throwable)}"
            )
        }
    })
}

internal fun hookGameAdBridge(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val rawMessage = param.args.getOrNull(0) as? String ?: return
            val payload = runCatching { JSONObject(rawMessage) }.getOrNull() ?: return
            val messageType = payload.optString("type")
            if (messageType !in GAME_AD_MESSAGE_TYPES) return

            markGameAdDiagnosticFlow("bridge ${method.declaringClass.name}.${method.name}")
            logGameAdDiagnostic(
                "bridge.before",
                "${methodSignature(method)} type=$messageType args=${formatDiagArgs(param.args)}"
            )
            rememberGameAdPayload(param.thisObject, payload, messageType)
            if (!ENABLE_GAME_AD_AUTOFIX) return
            if (rejectUnavailableGameAdPayloadIfNeeded(param.thisObject, payload, messageType, "bridge ${method.declaringClass.name}.${method.name}")) {
                param.result = null
                return
            }
            if (!shouldAutofixGameAdMessage(messageType)) return

            if (resolveGameAdPayload(param.thisObject, payload, messageType)) {
                dispatchPostResolveGameAdSignals(param.thisObject, payload, messageType)
                param.result = null
                Logger.i(
                    TAG,
                    "Resolved game ad bridge message type=$messageType in ${method.declaringClass.name}.${method.name}"
                )
            } else if (rejectGameAdPayload(param.thisObject, payload)) {
                param.result = null
                Logger.i(
                    TAG,
                    "Rejected game ad bridge message type=$messageType in ${method.declaringClass.name}.${method.name}"
                )
            } else {
                Logger.w(
                    TAG,
                    "Unable to resolve or reject game ad bridge message type=$messageType in ${method.declaringClass.name}.${method.name}"
                )
            }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            val rawMessage = param.args.getOrNull(0) as? String ?: return
            val payload = runCatching { JSONObject(rawMessage) }.getOrNull() ?: return
            val messageType = payload.optString("type")
            if (messageType !in GAME_AD_MESSAGE_TYPES) return

            logGameAdDiagnostic(
                "bridge.after",
                "${methodSignature(method)} type=$messageType result=${formatDiagValue(param.result)} throwable=${formatDiagThrowable(param.throwable)}"
            )
        }
    })
}

internal fun hookGameAdResultMethods(bridgeClass: Class<*>) {
    if (!gameAdResultHooksInstalled.compareAndSet(0, 1)) return

    val resolveMethod = resolveGameAdResolveMethod(bridgeClass)
    val rejectMethod = resolveGameAdRejectMethod(bridgeClass)
    val bridgeRejectMethod = resolveGameAdBridgeRejectMethod(bridgeClass)
    var hooked = 0

    resolveMethod?.let { method ->
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val promiseId = param.args.getOrNull(0) as? String ?: return
                val snapshot = gameAdPromiseSnapshots[promiseId] ?: return
                markGameAdDiagnosticFlow("resolve ${method.declaringClass.name}.${method.name}")
                logGameAdDiagnostic(
                    "resolve.before",
                    "${methodSignature(method)} promise=$promiseId snapshotType=${snapshot.messageType} args=${formatDiagArgs(param.args)}"
                )
                if (snapshot.messageType !in GAME_AD_MESSAGE_TYPES) return
                if (!ENABLE_GAME_AD_AUTOFIX) return
                if (!shouldAutofixGameAdMessage(snapshot.messageType)) return

                val original = param.args.getOrNull(1)
                param.args[1] = forceGameAdSuccessResult(
                    promiseId = promiseId,
                    original = original,
                    payload = snapshot.payload,
                    messageType = snapshot.messageType
                )
                Logger.i(TAG, "Forced successful game ad resolve promise=$promiseId type=${snapshot.messageType}")
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val promiseId = param.args.getOrNull(0) as? String ?: return
                val snapshot = gameAdPromiseSnapshots[promiseId] ?: return
                logGameAdDiagnostic(
                    "resolve.after",
                    "${methodSignature(method)} promise=$promiseId snapshotType=${snapshot.messageType} result=${formatDiagValue(param.result)} throwable=${formatDiagThrowable(param.throwable)}"
                )
            }
        })
        hooked++
    }

    if (rejectMethod != null && resolveMethod != null) {
        XposedBridge.hookMethod(rejectMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val promiseId = param.args.getOrNull(0) as? String ?: return
                val reason = param.args.drop(1).joinToString(" ") { it?.toString().orEmpty() }
                if (gameAdPromiseSnapshots.containsKey(promiseId) || isRecentGameAdDiagnosticFlow() || reason.hasGameAdSignal()) {
                    markGameAdDiagnosticFlow("reject ${rejectMethod.declaringClass.name}.${rejectMethod.name}")
                    logGameAdDiagnostic(
                        "reject.before",
                        "${methodSignature(rejectMethod)} promise=$promiseId snapshotType=${gameAdPromiseSnapshots[promiseId]?.messageType} args=${formatDiagArgs(param.args)}"
                    )
                }
                if (!ENABLE_GAME_AD_AUTOFIX) return
                if (!shouldConvertGameAdRejectToSuccess(promiseId, reason)) return

                val snapshot = gameAdPromiseSnapshots[promiseId]
                val success = forceGameAdSuccessResult(
                    promiseId = promiseId,
                    original = null,
                    payload = snapshot?.payload,
                    messageType = snapshot?.messageType ?: gameAdPromiseTypeFromReason(reason)
                )
                runCatching {
                    XposedBridge.invokeOriginalMethod(resolveMethod, param.thisObject, arrayOf(promiseId, success))
                    param.result = null
                    Logger.i(
                        TAG,
                        "Converted game ad reject to success promise=$promiseId type=${snapshot?.messageType} reason=$reason"
                    )
                }.onFailure {
                    Logger.w(TAG, "Failed to convert game ad reject to success promise=$promiseId", it)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val promiseId = param.args.getOrNull(0) as? String ?: return
                val reason = param.args.drop(1).joinToString(" ") { it?.toString().orEmpty() }
                if (gameAdPromiseSnapshots.containsKey(promiseId) || isRecentGameAdDiagnosticFlow() || reason.hasGameAdSignal()) {
                    logGameAdDiagnostic(
                        "reject.after",
                        "${methodSignature(rejectMethod)} promise=$promiseId result=${formatDiagValue(param.result)} throwable=${formatDiagThrowable(param.throwable)}"
                    )
                }
            }
        })
        hooked++
    }

    if (bridgeRejectMethod != null && resolveMethod != null && bridgeRejectMethod != rejectMethod) {
        XposedBridge.hookMethod(bridgeRejectMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val payload = param.args.getOrNull(2) as? JSONObject ?: return
                val promiseId = extractPromiseId(payload) ?: return
                val reason = param.args.take(2).joinToString(" ") { it?.toString().orEmpty() }
                if (gameAdPromiseSnapshots.containsKey(promiseId) || isRecentGameAdDiagnosticFlow() || reason.hasGameAdSignal()) {
                    markGameAdDiagnosticFlow("bridgeReject ${bridgeRejectMethod.declaringClass.name}.${bridgeRejectMethod.name}")
                    logGameAdDiagnostic(
                        "bridgeReject.before",
                        "${methodSignature(bridgeRejectMethod)} promise=$promiseId snapshotType=${gameAdPromiseSnapshots[promiseId]?.messageType} args=${formatDiagArgs(param.args)}"
                    )
                }
                if (!ENABLE_GAME_AD_AUTOFIX) return
                if (!shouldConvertGameAdRejectToSuccess(promiseId, reason)) return

                val snapshot = gameAdPromiseSnapshots[promiseId]
                val success = forceGameAdSuccessResult(
                    promiseId = promiseId,
                    original = null,
                    payload = snapshot?.payload ?: payload,
                    messageType = snapshot?.messageType ?: gameAdPromiseTypeFromReason(reason)
                )
                runCatching {
                    XposedBridge.invokeOriginalMethod(resolveMethod, param.thisObject, arrayOf(promiseId, success))
                    param.result = null
                    Logger.i(
                        TAG,
                        "Converted game ad bridge reject to success promise=$promiseId type=${snapshot?.messageType} reason=$reason"
                    )
                }.onFailure {
                    Logger.w(TAG, "Failed to convert game ad bridge reject to success promise=$promiseId", it)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val payload = param.args.getOrNull(2) as? JSONObject ?: return
                val promiseId = extractPromiseId(payload) ?: return
                val reason = param.args.take(2).joinToString(" ") { it?.toString().orEmpty() }
                if (gameAdPromiseSnapshots.containsKey(promiseId) || isRecentGameAdDiagnosticFlow() || reason.hasGameAdSignal()) {
                    logGameAdDiagnostic(
                        "bridgeReject.after",
                        "${methodSignature(bridgeRejectMethod)} promise=$promiseId result=${formatDiagValue(param.result)} throwable=${formatDiagThrowable(param.throwable)}"
                    )
                }
            }
        })
        hooked++
    }

    Logger.i(TAG, "Hooked $hooked game ad result helper method(s) in ${bridgeClass.name}")
}

internal fun hookGameAdServiceDispatchMethods(bridgeClass: Class<*>) {
    if (!gameAdServiceDispatchHooksInstalled.compareAndSet(0, 1)) return

    val methods = (bridgeClass.declaredMethods + bridgeClass.methods)
        .filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterCount == 2 &&
                method.parameterTypes[0] == Bundle::class.java
        }
        .distinctBy { method ->
            method.name + method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
        }

    var hooked = 0
    methods.forEach { method ->
        method.isAccessible = true
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val bundle = param.args.getOrNull(0) as? Bundle ?: return
                val messageType = param.args.getOrNull(1)?.toString()?.lowercase()
                    ?.takeIf { it in GAME_AD_MESSAGE_TYPES } ?: return
                val payload = buildGameAdPayloadFromServiceBundle(bundle, messageType)

                markGameAdDiagnosticFlow("serviceDispatch ${method.declaringClass.name}.${method.name}")
                logGameAdDiagnostic(
                    "serviceDispatch.before",
                    "${methodSignature(method)} type=$messageType args=${formatDiagArgs(param.args)}"
                )
                rememberGameAdPayload(param.thisObject, payload, messageType)
                if (!ENABLE_GAME_AD_AUTOFIX) return
                if (rejectUnavailableGameAdPayloadIfNeeded(param.thisObject, payload, messageType, "service dispatch ${method.declaringClass.name}.${method.name}")) {
                    param.result = null
                    return
                }
                if (!shouldAutofixGameAdMessage(messageType)) return

                if (resolveGameAdPayload(param.thisObject, payload, messageType)) {
                    dispatchPostResolveGameAdSignals(param.thisObject, payload, messageType)
                    param.result = null
                    Logger.i(
                        TAG,
                        "Resolved game ad service dispatch type=$messageType in ${method.declaringClass.name}.${method.name}"
                    )
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.args.getOrNull(0) !is Bundle) return
                val messageType = param.args.getOrNull(1)?.toString()?.lowercase()
                    ?.takeIf { it in GAME_AD_MESSAGE_TYPES } ?: return
                logGameAdDiagnostic(
                    "serviceDispatch.after",
                    "${methodSignature(method)} type=$messageType result=${formatDiagValue(param.result)} throwable=${formatDiagThrowable(param.throwable)}"
                )
            }
        })
        hooked++
    }

    Logger.i(TAG, "Hooked $hooked game ad service dispatch method(s) in ${bridgeClass.name}")
}

internal fun hookGameAdSystemDiagnostics(classLoader: ClassLoader) {
    if (!ENABLE_GAME_AD_DIAGNOSTICS || !gameAdSystemDiagnosticsInstalled.compareAndSet(0, 1)) return

    hookMessengerSendDiagnostics()
    hookHandlerMessageDiagnostics(classLoader)
    hookActivityResultDiagnostics()
    hookAudienceNetworkViewDiagnostics()
    hookDynamicGameAdClassDiagnostics(classLoader)

    Logger.i(
        TAG,
        "Hooked passive game ad diagnostic probes: marker=$BUILD_MARKER " +
            "broadHandler=$ENABLE_BROAD_HANDLER_GAME_AD_DIAGNOSTICS " +
            "anView=$ENABLE_AUDIENCE_NETWORK_VIEW_DIAGNOSTICS cap=$GAME_AD_DIAG_LOG_LIMIT"
    )
}

internal fun resolveGameAdResolveMethod(type: Class<*>?): Method? {
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

internal fun resolveGameAdBridgeRejectMethod(type: Class<*>?): Method? {
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

internal fun resolveGameAdRejectMethod(type: Class<*>?): Method? {
    if (type == null) return null
    return (type.declaredMethods + type.methods).firstOrNull { method ->
        !Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            method.parameterCount == 3 &&
            method.parameterTypes.all { it == String::class.java }
    }?.apply { isAccessible = true }
}

internal fun resolveGameAdInstanceId(
    placementId: String,
    messageType: String?,
    bannerPosition: String?
): String {
    val key = listOf(messageType.orEmpty(), placementId, bannerPosition.orEmpty()).joinToString("|")
    return gameAdInstanceIds.computeIfAbsent(key) {
        val suffix = key.hashCode().toLong() and 0xffffffffL
        "${GAME_AD_SUCCESS_INSTANCE_PREFIX}_$suffix"
    }
}

internal fun inferGameAdMessageType(method: Method, payload: Any?): String? {
    val payloadType = (payload as? JSONObject)?.optString("type")?.takeIf { it.isNotBlank() }
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

internal fun dispatchPostResolveGameAdSignals(target: Any?, payload: Any?, messageType: String?) {
    when (messageType) {
        "loadbanneradasync", "hidebanneradasync" -> {
            val content = buildGameAdSuccessPayload(payload, messageType)
            if (dispatchGameEvent(target, "hidebannerad", content)) {
                Logger.i(TAG, "Dispatched hidebannerad for game banner message type=$messageType")
            }
        }
    }
}

internal fun rememberGameAdPayload(target: Any?, payload: Any?, messageType: String?) {
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

internal fun completeRecentGameAdRequests(source: String) {
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

internal fun shouldConvertGameAdRejectToSuccess(promiseId: String, reason: String): Boolean {
    val snapshot = gameAdPromiseSnapshots[promiseId]
    if (shouldAutofixGameAdMessage(snapshot?.messageType)) return true

    val normalized = reason.lowercase()
    if (!isRecentGameAdActivityClose()) return false
    return normalized.contains("banner")
}

internal fun shouldAutofixGameAdMessage(messageType: String?): Boolean {
    return messageType in GAME_AD_AUTOFIX_MESSAGE_TYPES
}

internal fun rejectUnavailableGameAdPayloadIfNeeded(
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

internal fun shouldMakeGameAdUnavailable(payload: Any?, messageType: String?): Boolean {
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

internal fun isRecentUnavailableGameAd(): Boolean {
    val rejectedAt = lastUnavailableGameAdMs.get()
    return rejectedAt > 0 && System.currentTimeMillis() - rejectedAt < GAME_AD_RECENT_WINDOW_MS
}

internal fun isRecentGameAdActivityClose(): Boolean {
    val closedAt = lastGameAdActivityCloseMs.get()
    return closedAt > 0 && System.currentTimeMillis() - closedAt < 15_000L
}

internal fun gameAdPromiseTypeFromReason(reason: String): String? {
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

internal fun hasRecentGameAdRequest(): Boolean {
    val now = System.currentTimeMillis()
    return synchronized(recentGameAdPayloads) {
        recentGameAdPayloads.removeAll { now - it.timestampMs > GAME_AD_RECENT_WINDOW_MS }
        recentGameAdPayloads.isNotEmpty()
    }
}

internal fun handleGameAdActivity(activity: Activity, source: String) {
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
            forceAudienceNetworkRewardCompletion(activity, source)
            finishGameAdActivity(activity, source)
        }
        else -> finishGameAdActivity(activity, source)
    }
}

internal fun finishGameAdActivity(activity: Activity, source: String) {
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

internal fun buildGameAdActivityResultIntent(): Intent {
    return Intent().apply {
        putExtra("success", true)
    }
}

internal fun buildGameAdPayloadFromServiceBundle(bundle: Bundle, messageType: String): JSONObject {
    return JSONObject().apply {
        put("type", messageType)
        put("content", bundleToJsonObject(bundle))
    }
}

internal fun buildGameAdSuccessPayload(payload: Any?, messageType: String? = null): JSONObject {
    val effectiveMessageType = messageType
        ?: (payload as? JSONObject)?.optString("type").orEmpty()
    val content = extractGameAdContent(payload)
    val result = JSONObject()

    val placementId = content?.optString("placementID")?.takeIf { it.isNotBlank() }
    val requestedAdInstanceId = content?.optString("adInstanceID")?.takeIf { it.isNotBlank() }
    val bannerPosition = content?.optString("bannerPosition")?.takeIf { it.isNotBlank() }

    result.put("success", true)
    if (effectiveMessageType?.contains("reward", ignoreCase = true) == true) {
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
            cleanupGameAdIdCaches()
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
            cleanupGameAdIdCaches()
            gameAdInstanceTypes.putIfAbsent(adInstanceId, type)
        }
    }

    return result
}

internal fun cleanupGameAdIdCaches() {
    if (gameAdInstanceIds.size > 200) {
        gameAdInstanceIds.clear()
    }
    if (gameAdInstanceTypes.size > 200) {
        gameAdInstanceTypes.clear()
    }
}

internal fun forceGameAdSuccessResult(
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

internal fun extractPromiseId(payload: Any?): String? {
    val jsonObjectClass = payload?.javaClass ?: return null
    if (jsonObjectClass.name != "org.json.JSONObject") return null
    val getJSONObject = (jsonObjectClass.declaredMethods + jsonObjectClass.methods).firstOrNull { method ->
        method.name == "getJSONObject" &&
            method.parameterCount == 1 &&
            method.parameterTypes[0] == String::class.java
    }?.apply { isAccessible = true } ?: return null
    val getString = (jsonObjectClass.declaredMethods + jsonObjectClass.methods).firstOrNull { method ->
        method.name == "getString" &&
            method.parameterCount == 1 &&
            method.parameterTypes[0] == String::class.java
    }?.apply { isAccessible = true } ?: return null

    val content = runCatching { getJSONObject.invoke(payload, "content") }.getOrNull() ?: return null
    return runCatching { getString.invoke(content, "promiseID") as? String }.getOrNull()
}

internal fun rejectGameAdPayload(
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

internal fun resolveGameAdPayload(target: Any?, payload: Any?, messageType: String? = null): Boolean {
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

internal fun dispatchGameEvent(target: Any?, eventType: String, content: Any?): Boolean {
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

internal fun extractGameAdContent(payload: Any?): JSONObject? {
    val json = payload as? JSONObject ?: return null
    return json.optJSONObject("content")
}
