package tn.loukious.facebookappadsremover

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.Messenger
import android.util.Log as AndroidLog
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.Properties
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.Optional
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal val gameAdInstanceIds = ConcurrentHashMap<String, String>()
internal val gameAdInstanceTypes = ConcurrentHashMap<String, String>()
internal val gameAdPromiseSnapshots = ConcurrentHashMap<String, GameAdPromiseSnapshot>()
internal val recentGameAdTargets = Collections.synchronizedMap(WeakHashMap<Any, Long>())
internal val recentGameAdPayloads = Collections.synchronizedList(ArrayList<GameAdPayloadSnapshot>())
internal val gameAdSurfaceHooksInstalled = AtomicInteger(0)
internal val gameAdActivityLifecycleHookInstalled = AtomicBoolean(false)
internal val gameAdResultHookedClasses = ConcurrentHashMap.newKeySet<String>()
internal val gameAdServiceDispatchHookedClasses = ConcurrentHashMap.newKeySet<String>()
internal val gameAdSystemDiagnosticsInstalled = AtomicInteger(0)
internal val gameAdDynamicDiagnosticsInstalled = AtomicInteger(0)
internal val audienceNetworkViewDiagnosticsInstalled = AtomicInteger(0)
internal val audienceNetworkRewardHooksInstalled = AtomicInteger(0)
internal val lastGameAdActivityCloseMs = AtomicLong(0L)
internal val lastUnavailableGameAdMs = AtomicLong(0L)
internal val lastGameAdDiagnosticFlowMs = AtomicLong(0L)
internal val gameAdDiagnosticLogCount = AtomicInteger(0)
internal val scheduledGameAdActivityCloses = Collections.synchronizedMap(WeakHashMap<Activity, Long>())
internal val audienceNetworkRewardClassesHooked = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
internal val audienceNetworkRewardAdListeners = Collections.synchronizedMap(WeakHashMap<Any, Any>())
internal val gameAdDiagnosticClassesHooked = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
internal val gameAdDiagnosticClassesLogged = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
internal val audienceNetworkViewListenerClassesHooked = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
internal val audienceNetworkActivityStateDumps = Collections.synchronizedMap(WeakHashMap<Activity, Long>())
internal fun findViewAncestor(view: View, className: String): View? {
    var current: View? = view
    repeat(80) {
        val value = current ?: return null
        if (value.javaClass.name == className) return value
        current = value.parent as? View
    }
    return null
}

internal fun hookVoidMethodsByString(
    classLoader: ClassLoader,
    bridge: DexKitBridge,
    usingString: String,
    label: String
) {
    runCatching {
        bridge.findMethod {
            matcher {
                usingStrings(usingString)
            }
        }.forEach { methodData ->
            val method = runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
                ?: return@forEach
            if (Modifier.isStatic(method.modifiers) || method.returnType != Void.TYPE) return@forEach
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                }
            })
            Log.i(TAG, "$label: ${method.declaringClass.name}.${method.name}")
        }
    }.onFailure { Log.w(TAG, "$label resolution failed", it) }
}

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
            if (!shouldForceGameAdSuccess(payload, messageType)) return

            if (resolveGameAdPayload(param.thisObject, payload, messageType)) {
                dispatchPostResolveGameAdSignals(param.thisObject, payload, messageType)
                param.result = null
                Log.i(
                    TAG,
                    "Resolved game ad request as success in ${method.declaringClass.name}.${method.name}"
                )
            } else if (rejectGameAdPayload(param.thisObject, payload)) {
                param.result = null
                Log.i(
                    TAG,
                    "Rejected game ad request in ${method.declaringClass.name}.${method.name}"
                )
            } else {
                Log.w(
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
            if (!shouldForceGameAdSuccess(payload, messageType)) return

            if (resolveGameAdPayload(param.thisObject, payload, messageType)) {
                dispatchPostResolveGameAdSignals(param.thisObject, payload, messageType)
                param.result = null
                Log.i(
                    TAG,
                    "Resolved game ad bridge message type=$messageType in ${method.declaringClass.name}.${method.name}"
                )
            } else if (rejectGameAdPayload(param.thisObject, payload)) {
                param.result = null
                Log.i(
                    TAG,
                    "Rejected game ad bridge message type=$messageType in ${method.declaringClass.name}.${method.name}"
                )
            } else {
                Log.w(
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

// The instant-games delegate that receives ad messages is not the DexKit
// bridge (X.gJA) on all builds — the game webview can register a delegate
// from a lazily loaded plugin dex whose obfuscated name is unknowable in
// advance. WebView.addJavascriptInterface is a stable framework seam that
// observes every delegate object at registration time, whatever dex it
// comes from; its JS entry methods are then hooked with the same
// game-ad bridge logic used for the statically discovered bridge.
internal val gameAdJavascriptInterfaceHookInstalled = AtomicInteger(0)
internal val gameAdBridgeEntryMethodsHooked = ConcurrentHashMap.newKeySet<String>()

fun installGameAdJavascriptInterfaceBridgeHook() {
    if (!gameAdJavascriptInterfaceHookInstalled.compareAndSet(0, 1)) return
    runCatching {
        val addInterfaceMethod = WebView::class.java.getDeclaredMethod(
            "addJavascriptInterface",
            Any::class.java,
            String::class.java
        )
        addInterfaceMethod.isAccessible = true
        XposedBridge.hookMethod(addInterfaceMethod, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val bridgeObject = param.args.getOrNull(0) ?: return
                runCatching { hookGameAdBridgeObject(bridgeObject, "addJavascriptInterface") }
                    .onFailure {
                        Log.w(TAG, "Failed to hook game bridge object ${bridgeObject.javaClass.name}", it)
                    }
            }
        })
        Log.i(TAG, "Waiting for game webview Javascript bridges")
    }.onFailure {
        Log.w(TAG, "Failed to hook WebView.addJavascriptInterface", it)
    }

    // Promise deliveries can happen before the DexKit scan, so the webview
    // script seams are installed alongside the bridge watcher.
    installGameAdScriptResultHooks()
}

internal fun hookGameAdBridgeObject(bridgeObject: Any, source: String) {
    val bridgeClass = bridgeObject.javaClass

    val entryMethods = (bridgeClass.declaredMethods + bridgeClass.methods).filter { method ->
        !Modifier.isStatic(method.modifiers) &&
            method.parameterCount in 1..2 &&
            method.parameterTypes[0] == String::class.java &&
            method.isAnnotationPresent(JavascriptInterface::class.java)
    }.ifEmpty {
        (bridgeClass.declaredMethods + bridgeClass.methods).filter { method ->
            method.name == "postMessage" && method.parameterTypes.firstOrNull() == String::class.java
        }
    }

    entryMethods.forEach { method ->
        if (!gameAdBridgeEntryMethodsHooked.add(methodHookKey(method))) return@forEach
        method.isAccessible = true
        hookGameAdBridge(method)
        Log.i(TAG, "Hooked game bridge entry ${bridgeClass.name}.${method.name} via $source")
    }

    hookGameAdResultMethods(bridgeClass)
    hookGameAdServiceDispatchMethods(bridgeClass)
}

// The promise result is delivered back into the game webview as generated
// JavaScript (or a WebMessage) by plumbing whose shape varies per delegate —
// the same-process delegate (576: X.q10) has no promise helper on its class at
// all. These framework-level seams see every delivery regardless of the
// internal plumbing. A JS promise settles once, so the original failure call
// is rewritten in place, never shadowed by an extra call.
internal val gameAdScriptHooksInstalled = AtomicInteger(0)
internal val gameAdScriptDiagnostics = AtomicInteger(0)

fun installGameAdScriptResultHooks() {
    if (!gameAdScriptHooksInstalled.compareAndSet(0, 1)) return

    hookWebViewScriptDelivery("evaluateJavascript", String::class.java, ValueCallback::class.java)
    hookWebViewScriptDelivery("loadUrl", String::class.java)
    runCatching {
        val webMessageClass = Class.forName("android.webkit.WebMessage")
        val getData = webMessageClass.getDeclaredMethod("getData")
        val constructor = webMessageClass.getConstructor(String::class.java)
        val postWebMessage = WebView::class.java.getDeclaredMethod(
            "postWebMessage",
            webMessageClass,
            android.net.Uri::class.java
        )
        postWebMessage.isAccessible = true
        XposedBridge.hookMethod(postWebMessage, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val message = param.args.getOrNull(0) ?: return
                val data = runCatching { getData.invoke(message) as? String }.getOrNull() ?: return
                val rewritten = rewriteGameAdDeliveryIfNeeded(data, "postWebMessage") ?: return
                runCatching {
                    param.args[0] = constructor.newInstance(rewritten)
                    Log.i(TAG, "Rewrote game ad web message promise delivery")
                }
            }
        })
        Log.i(TAG, "Watching WebView.postWebMessage for game ad promise results")
    }
}

internal fun hookWebViewScriptDelivery(name: String, vararg parameterTypes: Class<*>) {
    runCatching {
        val method = WebView::class.java.getDeclaredMethod(name, *parameterTypes)
        method.isAccessible = true
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val script = param.args.getOrNull(0) as? String ?: return
                val rewritten = rewriteGameAdDeliveryIfNeeded(script, name) ?: return
                param.args[0] = rewritten
            }
        })
        Log.i(TAG, "Watching WebView.$name for game ad promise results")
    }
}

internal fun rewriteGameAdDeliveryIfNeeded(delivery: String, source: String): String? {
    if (gameAdPromiseSnapshots.isEmpty()) return null
    val promiseIds = gameAdPromiseSnapshots.keys.filter { delivery.contains(it) }
    if (promiseIds.isEmpty()) return null

    var result: String? = null
    promiseIds.forEach { promiseId ->
        val snapshot = gameAdPromiseSnapshots[promiseId] ?: return@forEach
        logGameAdDeliveryDiagnostic(delivery, promiseId, snapshot.messageType, source)
        if (!shouldForceGameAdSuccess(snapshot.payload, snapshot.messageType)) return@forEach

        val rewritten = rewritePromiseJsonInDelivery(delivery, promiseId, snapshot) ?: return@forEach
        if (rewritten != null && rewritten != delivery) {
            result = rewritten
        }
    }
    return result
}

// Logs the raw delivery for tracked promises so an unexpected response shape
// can be diagnosed from a single repro.
internal fun logGameAdDeliveryDiagnostic(delivery: String, promiseId: String, messageType: String?, source: String) {
    val seen = gameAdScriptDiagnostics.incrementAndGet()
    if (seen > 40) return
    Log.i(
        TAG,
        "Game ad promise delivery source=$source type=$messageType promise=$promiseId " +
            "script=${delivery.take(900)}"
    )
}

// Rewrites the JSON object containing the promiseId inside the delivery
// script: error fields are dropped, success/reward outcome fields are forced.
internal fun rewritePromiseJsonInDelivery(
    delivery: String,
    promiseId: String,
    snapshot: GameAdPromiseSnapshot
): String? {
    return runCatching {
        val index = delivery.indexOf(promiseId)
        if (index < 0) return null

        var start = delivery.lastIndexOf('{', index)
        if (start < 0) return null

        // Expand to an enclosing object when the promiseId is nested (e.g.
        // inside "content") — the rewrite should cover the whole response.
        while (start > 0) {
            val outerStart = delivery.lastIndexOf('{', start - 1)
            if (outerStart < 0) break
            val outer = extractBalancedJson(delivery, outerStart) ?: break
            if (!outer.contains(promiseId)) break
            start = outerStart
        }

        val balanced = extractBalancedJson(delivery, start) ?: return null
        val end = start + balanced.length - 1

        val originalJson = runCatching { JSONObject(delivery.substring(start, end + 1)) }.getOrNull()
            ?: return null
        val success = forceGameAdSuccessResult(
            promiseId = promiseId,
            original = originalJson,
            payload = snapshot.payload,
            messageType = snapshot.messageType
        )
        forceSuccessDeep(success, hasRewardGameAdSignal(snapshot.payload, snapshot.messageType))

        Log.i(
            TAG,
            "Rewrote game ad promise delivery promise=$promiseId type=${snapshot.messageType} " +
                "result=${success.toString().take(400)}"
        )
        delivery.substring(0, start) + success + delivery.substring(end + 1)
    }.getOrNull()
}

internal fun extractBalancedJson(text: String, start: Int): String? {
    var depth = 0
    var inString = false
    var escaped = false
    var cursor = start
    while (cursor < text.length) {
        val c = text[cursor]
        if (escaped) {
            escaped = false
        } else if (c == '\\') {
            escaped = true
        } else if (c == '"') {
            inString = !inString
        } else if (!inString) {
            if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) return text.substring(start, cursor + 1)
            }
        }
        cursor++
    }
    return null
}

// Outcome fields can live at the top level or inside "content"; error payloads
// at any level are dropped so the game cannot read a failure reason.
internal fun forceSuccessDeep(json: JSONObject, reward: Boolean) {
    val keys = ArrayList<String>()
    val keyIterator = json.keys()
    while (keyIterator.hasNext()) {
        keys.add(keyIterator.next() as String)
    }
    keys.forEach { key ->
        val value = json.opt(key)
        when {
            key == "error" || key == "errorMessage" ||
                (key == "code" && json.opt(key) is String) ->
                json.remove(key)
            value is JSONObject -> forceSuccessDeep(value, reward)
            else -> Unit
        }
    }
    if (json.has("success") || json.has("error") || reward || json.has("completed")) {
        json.put("success", true)
        if (reward) {
            json.put("completed", true)
            json.put("didComplete", true)
            json.put("watched", true)
            json.put("rewarded", true)
            json.put("completionGesture", "post")
        }
    }
}

internal fun hookGameAdResultMethods(bridgeClass: Class<*>) {
    if (!gameAdResultHookedClasses.add(bridgeClass.name)) return

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
                if (!shouldForceGameAdSuccess(snapshot.payload, snapshot.messageType)) return

                val original = param.args.getOrNull(1)
                param.args[1] = forceGameAdSuccessResult(
                    promiseId = promiseId,
                    original = original,
                    payload = snapshot.payload,
                    messageType = snapshot.messageType
                )
                Log.i(TAG, "Forced successful game ad resolve promise=$promiseId type=${snapshot.messageType}")
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
                    Log.i(
                        TAG,
                        "Converted game ad reject to success promise=$promiseId type=${snapshot?.messageType} reason=$reason"
                    )
                }.onFailure {
                    Log.w(TAG, "Failed to convert game ad reject to success promise=$promiseId", it)
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
                    Log.i(
                        TAG,
                        "Converted game ad bridge reject to success promise=$promiseId type=${snapshot?.messageType} reason=$reason"
                    )
                }.onFailure {
                    Log.w(TAG, "Failed to convert game ad bridge reject to success promise=$promiseId", it)
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

    Log.i(TAG, "Hooked $hooked game ad result helper method(s) in ${bridgeClass.name}")
}

internal fun hookGameAdServiceDispatchMethods(bridgeClass: Class<*>) {
    if (!gameAdServiceDispatchHookedClasses.add(bridgeClass.name)) return

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
                if (!shouldForceGameAdSuccess(payload, messageType)) return

                if (resolveGameAdPayload(param.thisObject, payload, messageType)) {
                    dispatchPostResolveGameAdSignals(param.thisObject, payload, messageType)
                    param.result = null
                    Log.i(
                        TAG,
                        "Resolved game ad service dispatch type=$messageType in ${method.declaringClass.name}.${method.name}"
                    )
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val bundle = param.args.getOrNull(0) as? Bundle ?: return
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

    Log.i(TAG, "Hooked $hooked game ad service dispatch method(s) in ${bridgeClass.name}")
}

internal fun hookGameAdSystemDiagnostics(classLoader: ClassLoader) {
    if (!ENABLE_GAME_AD_DIAGNOSTICS || !gameAdSystemDiagnosticsInstalled.compareAndSet(0, 1)) return

    hookMessengerSendDiagnostics()
    hookHandlerMessageDiagnostics(classLoader)
    hookActivityResultDiagnostics()
    hookAudienceNetworkViewDiagnostics()
    hookDynamicGameAdClassDiagnostics(classLoader)

    Log.i(
        TAG,
        "Hooked passive game ad diagnostic probes: marker=$BUILD_MARKER " +
            "broadHandler=$ENABLE_BROAD_HANDLER_GAME_AD_DIAGNOSTICS " +
            "anView=$ENABLE_AUDIENCE_NETWORK_VIEW_DIAGNOSTICS cap=$GAME_AD_DIAG_LOG_LIMIT"
    )
}

internal fun hookMessengerSendDiagnostics() {
    val sendMethods = (Messenger::class.java.declaredMethods + Messenger::class.java.methods)
        .filter { method ->
            method.name == "send" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == Message::class.java
        }
        .distinctBy { methodSignature(it) }

    sendMethods.forEach { method ->
        method.isAccessible = true
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val message = param.args.getOrNull(0) as? Message ?: return
                if (!shouldLogGameAdMessage(message)) return
                markGameAdDiagnosticFlow("messenger.send")
                logGameAdDiagnostic(
                    "messenger.send.before",
                    "${methodSignature(method)} this=${formatDiagValue(param.thisObject)} message=${formatDiagValue(message)}"
                )
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val message = param.args.getOrNull(0) as? Message ?: return
                if (!shouldLogGameAdMessage(message)) return
                logGameAdDiagnostic(
                    "messenger.send.after",
                    "${methodSignature(method)} result=${formatDiagValue(param.result)} throwable=${formatDiagThrowable(param.throwable)}"
                )
            }
        })
    }
}

internal fun hookHandlerMessageDiagnostics(classLoader: ClassLoader) {
    if (ENABLE_BROAD_HANDLER_GAME_AD_DIAGNOSTICS) {
        (Handler::class.java.declaredMethods + Handler::class.java.methods)
            .filter { method ->
                method.name == "dispatchMessage" &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0] == Message::class.java
            }
            .distinctBy { methodSignature(it) }
            .forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val message = param.args.getOrNull(0) as? Message ?: return
                        val handlerName = param.thisObject?.javaClass?.name.orEmpty()
                        if (!shouldLogGameAdMessage(message) && !handlerName.contains("C95084edO") && !handlerName.contains("HandlerC95084edO")) {
                            return
                        }
                        markGameAdDiagnosticFlow("handler.dispatch $handlerName")
                        logGameAdDiagnostic(
                            "handler.dispatch.before",
                            "handler=$handlerName ${methodSignature(method)} message=${formatDiagValue(message)}"
                        )
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val message = param.args.getOrNull(0) as? Message ?: return
                        val handlerName = param.thisObject?.javaClass?.name.orEmpty()
                        if (!shouldLogGameAdMessage(message)) {
                            return
                        }
                        logGameAdDiagnostic(
                            "handler.dispatch.after",
                            "handler=$handlerName result=${formatDiagValue(param.result)} throwable=${formatDiagThrowable(param.throwable)}"
                        )
                    }
                })
            }
    }
}

internal fun hookActivityResultDiagnostics() {
    (Activity::class.java.declaredMethods + Activity::class.java.methods)
        .filter { method ->
            (method.name == "setResult" && method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType) ||
                (method.name in setOf("finish", "onPause", "onStop", "onDestroy") && method.parameterCount == 0) ||
                (method.name == "onActivityResult" &&
                    method.parameterCount == 3 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    method.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    method.parameterTypes[2] == Intent::class.java)
        }
        .distinctBy { methodSignature(it) }
        .forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (!shouldLogGameAdActivityDiagnostic(activity, param.args)) return
                    markGameAdDiagnosticFlow("activity.${method.name} ${activity.javaClass.name}")
                    logGameAdDiagnostic(
                        "activity.${method.name}.before",
                        "${activity.javaClass.name} ${methodSignature(method)} args=${formatDiagArgs(param.args)} intent=${formatDiagValue(activity.intent)}"
                    )
                    if (method.name == "finish") {
                        dumpAudienceNetworkActivityState(activity, "activity.finish.before")
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (!shouldLogGameAdActivityDiagnostic(activity, param.args)) return
                    logGameAdDiagnostic(
                        "activity.${method.name}.after",
                        "${activity.javaClass.name} result=${formatDiagValue(param.result)} throwable=${formatDiagThrowable(param.throwable)}"
                    )
                }
            })
        }

    (Instrumentation::class.java.declaredMethods + Instrumentation::class.java.methods)
        .filter { method ->
            method.name == "callActivityOnActivityResult" &&
                method.parameterCount == 4 &&
                Activity::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                method.parameterTypes[1] == Int::class.javaPrimitiveType &&
                method.parameterTypes[2] == Int::class.javaPrimitiveType &&
                method.parameterTypes[3] == Intent::class.java
        }
        .distinctBy { methodSignature(it) }
        .forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.args.getOrNull(0) as? Activity ?: return
                    if (!shouldLogGameAdActivityDiagnostic(activity, param.args)) return
                    markGameAdDiagnosticFlow("instrumentation.activityResult ${activity.javaClass.name}")
                    logGameAdDiagnostic(
                        "instrumentation.activityResult.before",
                        "${methodSignature(method)} args=${formatDiagArgs(param.args)}"
                    )
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.args.getOrNull(0) as? Activity ?: return
                    if (!shouldLogGameAdActivityDiagnostic(activity, param.args)) return
                    logGameAdDiagnostic(
                        "instrumentation.activityResult.after",
                        "${methodSignature(method)} result=${formatDiagValue(param.result)} throwable=${formatDiagThrowable(param.throwable)}"
                    )
                }
            })
        }
}

internal fun hookAudienceNetworkViewDiagnostics() {
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
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val shouldLogView = shouldLogAudienceNetworkViewDiagnostic(view, param.args)
                    param.args.getOrNull(0)?.takeIf {
                        shouldLogView || shouldHookAudienceNetworkListenerClass(it.javaClass.name)
                    }?.let { listener ->
                        tryHookAudienceNetworkViewListenerClass(listener.javaClass, "View.${method.name}")
                    }
                    findViewOnClickListener(view)?.takeIf {
                        shouldLogView || shouldHookAudienceNetworkListenerClass(it.javaClass.name)
                    }?.let { listener ->
                        tryHookAudienceNetworkViewListenerClass(listener.javaClass, "View.${method.name}.existingClick")
                    }
                    findViewOnTouchListener(view)?.takeIf {
                        shouldLogView || shouldHookAudienceNetworkListenerClass(it.javaClass.name)
                    }?.let { listener ->
                        tryHookAudienceNetworkViewListenerClass(listener.javaClass, "View.${method.name}.existingTouch")
                    }
                    if (!shouldLogView) return

                    markGameAdDiagnosticFlow("anView.${method.name} ${view.javaClass.name}")
                    logGameAdDiagnostic(
                        "anView.${method.name}.before",
                        "${methodSignature(method)} ${describeAudienceNetworkView(view)} args=${formatDiagArgs(param.args)}"
                    )
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (!shouldLogAudienceNetworkViewDiagnostic(view, param.args) && !isRecentGameAdDiagnosticFlow()) return

                    logGameAdDiagnostic(
                        "anView.${method.name}.after",
                        "${methodSignature(method)} result=${formatDiagValue(param.result)} throwable=${formatDiagThrowable(param.throwable)} ${describeAudienceNetworkView(view)}"
                    )
                }
            })
        }.onFailure {
            Log.w(TAG, "Failed to hook Audience Network view diagnostic ${method.declaringClass.name}.${method.name}", it)
        }
    }

    Log.i(TAG, "Hooked ${viewMethods.size} Audience Network view diagnostic method(s)")
}

internal fun dumpAudienceNetworkActivityState(activity: Activity, source: String) {
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
    dumpAudienceNetworkObjectGraph(activity, source)
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
                tryHookAudienceNetworkViewListenerClass(listener.javaClass, "viewTree.clickListener")
            }
            findViewOnTouchListener(view)?.let { listener ->
                tryHookAudienceNetworkViewListenerClass(listener.javaClass, "viewTree.touchListener")
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

internal fun dumpAudienceNetworkObjectGraph(activity: Activity, source: String) {
    val seen = IdentityHashMap<Any, Boolean>()
    val queue = java.util.ArrayDeque<AudienceNetworkGraphNode>()
    queue.add(AudienceNetworkGraphNode(activity, "activity", 0))

    var inspected = 0
    var logged = 0
    while (!queue.isEmpty() && inspected < AUDIENCE_NETWORK_STATE_DUMP_LIMIT && logged < AUDIENCE_NETWORK_STATE_DUMP_LIMIT) {
        val node = queue.removeFirst()
        val value = node.value
        if (seen.put(value, true) != null) continue
        inspected++

        if (value !== activity) {
            logged++
            tryHookAudienceNetworkDiagnosticObjectClass(value.javaClass, "graph ${node.path}")
            logGameAdDiagnostic(
                "anActivity.object",
                "$source ${node.path}=${formatDiagValue(value)} methods=${audienceNetworkInterestingMethodsSummary(value.javaClass)}"
            )
        }

        if (node.depth >= 4) continue

        audienceNetworkFieldsFor(value.javaClass).forEach { field ->
            val fieldValue = runCatching { field.get(value) }.getOrNull() ?: return@forEach
            val fieldPath = "${node.path}.${field.name}"
            when (fieldValue) {
                is View -> {
                    tryHookAudienceNetworkDiagnosticObjectClass(fieldValue.javaClass, "graph view $fieldPath")
                    logGameAdDiagnostic(
                        "anActivity.field",
                        "$source $fieldPath=${describeAudienceNetworkView(fieldValue)}"
                    )
                }
                is Iterable<*> -> fieldValue.take(12).forEachIndexed { index, item ->
                    if (item != null && shouldQueueAudienceNetworkDiagnosticObject(item)) {
                        queue.add(AudienceNetworkGraphNode(item, "$fieldPath[$index]", node.depth + 1))
                    }
                }
                is Array<*> -> fieldValue.take(12).forEachIndexed { index, item ->
                    if (item != null && shouldQueueAudienceNetworkDiagnosticObject(item)) {
                        queue.add(AudienceNetworkGraphNode(item, "$fieldPath[$index]", node.depth + 1))
                    }
                }
                else -> if (shouldQueueAudienceNetworkDiagnosticObject(fieldValue)) {
                    queue.add(AudienceNetworkGraphNode(fieldValue, fieldPath, node.depth + 1))
                } else if (isGameAdDiagnosticValue(fieldValue)) {
                    logGameAdDiagnostic(
                        "anActivity.field",
                        "$source $fieldPath=${formatDiagValue(fieldValue)}"
                    )
                }
            }
        }
    }

    logGameAdDiagnostic("anActivity.dumpDone", "$source inspected=$inspected logged=$logged")
}

internal fun tryHookAudienceNetworkDiagnosticObjectClass(clazz: Class<*>, source: String) {
    if (isGameAdDiagnosticClassName(clazz.name)) {
        tryHookGameAdDiagnosticClass(clazz)
    }
    if (isPotentialAudienceNetworkAppClass(clazz.name)) {
        tryHookAudienceNetworkViewListenerClass(clazz, source)
    }
}

internal fun tryHookAudienceNetworkViewListenerClass(clazz: Class<*>, source: String) {
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
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!shouldLogAudienceNetworkListenerCall(method, param.args)) return
                        markGameAdDiagnosticFlow("anListener.${method.name} ${method.declaringClass.name}")
                        logGameAdDiagnostic(
                            "anListener.${method.name}.before",
                            "${methodSignature(method)} this=${formatDiagValue(param.thisObject)} args=${formatDiagArgs(param.args)}"
                        )
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!shouldLogAudienceNetworkListenerCall(method, param.args) && !isRecentGameAdDiagnosticFlow()) return
                        logGameAdDiagnostic(
                            "anListener.${method.name}.after",
                            "${methodSignature(method)} result=${formatDiagValue(param.result)} throwable=${formatDiagThrowable(param.throwable)}"
                        )
                    }
                })
                hooked++
            }.onFailure {
                Log.w(TAG, "Failed to hook Audience Network listener diagnostic ${clazz.name}.${method.name}", it)
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

internal fun viewIdLabel(view: View): String {
    if (view.id == View.NO_ID) return "NO_ID"
    return runCatching { view.resources.getResourceName(view.id) }.getOrDefault(view.id.toString())
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

internal fun contextActivityForView(view: View): Activity? {
    var context = view.context
    var depth = 0
    while (depth < 8) {
        if (context is Activity) return context
        context = (context as? ContextWrapper)?.baseContext ?: return null
        depth++
    }
    return null
}

internal fun findViewOnClickListener(view: View): Any? {
    return findViewListenerInfoField(view, "mOnClickListener")
}

internal fun findViewOnTouchListener(view: View): Any? {
    return findViewListenerInfoField(view, "mOnTouchListener")
}

internal fun findViewListenerInfoField(view: View, fieldName: String): Any? {
    return runCatching {
        val listenerInfoField = View::class.java.getDeclaredField("mListenerInfo").apply {
            isAccessible = true
        }
        val listenerInfo = listenerInfoField.get(view) ?: return@runCatching null
        val listenerField = listenerInfo.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
        }
        listenerField.get(listenerInfo)
    }.getOrNull()
}

internal fun shouldQueueAudienceNetworkDiagnosticObject(value: Any): Boolean {
    if (value is View ||
        value is Activity ||
        value is String ||
        value is Number ||
        value is Boolean ||
        value is CharSequence
    ) {
        return false
    }

    val type = value.javaClass
    if (type.isPrimitive || type.isEnum) return false
    val className = type.name
    if (className.startsWith("android.") ||
        className.startsWith("java.") ||
        className.startsWith("javax.") ||
        className.startsWith("kotlin.") ||
        className.startsWith("dalvik.") ||
        className.startsWith("libcore.")
    ) {
        return false
    }

    return shouldTraverseAudienceNetworkObject(value, false) ||
        isPotentialAudienceNetworkAppClass(className) ||
        className.hasGameAdSignal()
}

internal fun isPotentialAudienceNetworkAppClass(className: String): Boolean {
    return className.startsWith("com.facebook.") ||
        className.startsWith("X.") ||
        className.startsWith("p000X.") ||
        className.hasGameAdSignal()
}

internal fun shouldHookAudienceNetworkListenerClass(className: String): Boolean {
    return className.startsWith("com.facebook.ads.") ||
        className.contains("audiencenetwork", ignoreCase = true)
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

internal fun hookDynamicGameAdClassDiagnostics(classLoader: ClassLoader) {
    if (!gameAdDynamicDiagnosticsInstalled.compareAndSet(0, 1)) return

    listOf(
        AUDIENCE_NETWORK_ACTIVITY_CLASS,
        AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS,
        "com.facebook.quicksilver.webviewprocess.QuicksilverSeparateProcessAdsLoader"
    ).forEach { className ->
        runCatching { tryHookGameAdDiagnosticClass(classLoader.loadClass(className)) }
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
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val className = param.args.getOrNull(0) as? String ?: return
                    val clazz = param.result as? Class<*> ?: return
                    if (!isGameAdDiagnosticClassName(className) && !isGameAdDiagnosticClassName(clazz.name)) return
                    logGameAdDiagnosticClass(clazz)
                    tryHookGameAdDiagnosticClass(clazz)
                }
            })
        }
}

internal fun tryHookGameAdDiagnosticClass(clazz: Class<*>) {
    if (!ENABLE_GAME_AD_DIAGNOSTICS) return
    val className = clazz.name
    if (!isGameAdDiagnosticClassName(className) || !gameAdDiagnosticClassesHooked.add(className)) return

    val methods = runCatching { clazz.declaredMethods + clazz.methods }.getOrDefault(emptyArray())
    var hooked = 0
    methods
        .filter { method -> isGameAdDiagnosticMethod(clazz, method) }
        .distinctBy { methodSignature(it) }
        .take(28)
        .forEach { method ->
            runCatching {
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!shouldLogGameAdDiagnosticCall(method, param.args)) return
                        markGameAdDiagnosticFlow("dynamic ${method.declaringClass.name}.${method.name}")
                        logGameAdDiagnostic("dynamic.before") {
                            "${methodSignature(method)} this=${formatDiagValue(param.thisObject)} args=${formatDiagArgs(param.args)}"
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!shouldLogGameAdDiagnosticCall(method, param.args) && !isRecentGameAdDiagnosticFlow()) return
                        logGameAdDiagnostic("dynamic.after") {
                            "${methodSignature(method)} result=${formatDiagValue(param.result)} throwable=${formatDiagThrowable(param.throwable)}"
                        }
                    }
                })
                hooked++
            }.onFailure {
                Log.w(TAG, "Failed to hook game ad diagnostic method ${clazz.name}.${method.name}", it)
            }
        }

    if (hooked > 0) {
        Log.i(TAG, "Hooked $hooked passive game ad diagnostic method(s) in $className")
    }
}

internal fun logGameAdDiagnosticClass(clazz: Class<*>) {
    if (!gameAdDiagnosticClassesLogged.add(clazz.name)) return

    logGameAdDiagnostic("class.loaded") {
        val methodSummary = runCatching {
            (clazz.declaredMethods + clazz.methods)
                .asSequence()
                .filter { isGameAdDiagnosticMethod(clazz, it) }
                .distinctBy { methodSignature(it) }
                .take(16)
                .joinToString(";") { method -> "${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})>${method.returnType.simpleName}" }
        }.getOrDefault("")

        "${clazz.name} super=${clazz.superclass?.name} interfaces=${clazz.interfaces.joinToString { it.name }} methods=$methodSummary"
    }
}

internal fun markGameAdDiagnosticFlow(reason: String) {
    if (!ENABLE_GAME_AD_DIAGNOSTICS) return
    lastGameAdDiagnosticFlowMs.set(System.currentTimeMillis())
    logGameAdDiagnostic("flow.mark", reason)
}

internal fun isRecentGameAdDiagnosticFlow(): Boolean {
    val timestamp = lastGameAdDiagnosticFlowMs.get()
    return timestamp > 0 && System.currentTimeMillis() - timestamp < GAME_AD_DIAG_FLOW_WINDOW_MS
}

// Opt 1.3: Lazy evaluation overload to avoid constructing diagnostic string payloads when diagnostics are disabled or throttled
internal inline fun logGameAdDiagnostic(event: String, crossinline detailSupplier: () -> String) {
    if (!ENABLE_GAME_AD_DIAGNOSTICS) return

    val count = gameAdDiagnosticLogCount.incrementAndGet()
    when {
        count <= GAME_AD_DIAG_LOG_LIMIT -> Log.i(TAG, "GADIAG[$count] $event ${truncateDiag(detailSupplier())}")
        count == GAME_AD_DIAG_LOG_LIMIT + 1 -> Log.i(TAG, "GADIAG log limit reached; suppressing further diagnostics")
    }
}

internal fun logGameAdDiagnostic(event: String, detail: String) {
    if (!ENABLE_GAME_AD_DIAGNOSTICS) return
    logGameAdDiagnostic(event) { detail }
}

internal fun formatDiagArgs(args: Array<Any?>?): String {
    if (args == null) return "[]"
    return args.mapIndexed { index, value -> "$index=${formatDiagValue(value)}" }
        .joinToString(prefix = "[", postfix = "]")
}

internal fun formatDiagThrowable(throwable: Throwable?): String {
    return throwable?.let { "${it.javaClass.name}:${it.message}" } ?: "none"
}

@Suppress("DEPRECATION")
internal fun formatDiagValue(value: Any?, depth: Int = 0): String {
    if (value == null) return "null"
    if (depth >= 3) return shortObjectLabel(value)

    val formatted = when (value) {
        JSONObject.NULL -> "JSONObject.NULL"
        is String -> "\"${truncateDiag(value, 260)}\""
        is Number, is Boolean -> value.toString()
        is JSONObject -> truncateDiag(value.toString(), 620)
        is JSONArray -> truncateDiag(value.toString(), 620)
        is Bundle -> {
            val entries = runCatching { value.keySet().toList() }.getOrDefault(emptyList())
                .take(24)
                .joinToString(",") { key ->
                    "$key=${formatDiagValue(runCatching { value.get(key) }.getOrNull(), depth + 1)}"
                }
            "Bundle{$entries}"
        }
        is Intent -> {
            val extras = value.extras?.let { formatDiagValue(it, depth + 1) } ?: "null"
            "Intent{action=${value.action}, component=${value.component?.flattenToShortString()}, data=${value.data}, flags=0x${value.flags.toString(16)}, extras=$extras}"
        }
        is Message -> {
            val obj = formatDiagValue(value.obj, depth + 1)
            val data = formatDiagValue(runCatching { value.peekData() }.getOrNull(), depth + 1)
            "Message{what=${value.what}, arg1=${value.arg1}, arg2=${value.arg2}, obj=$obj, data=$data, replyTo=${value.replyTo}}"
        }
        is Activity -> "Activity{${value.javaClass.name} intent=${formatDiagValue(value.intent, depth + 1)}}"
        is View -> "View{${value.javaClass.name} shown=${value.isShown} size=${value.width}x${value.height} id=${value.id}}"
        is ByteArray -> "ByteArray{len=${value.size}, hex=${byteArrayHexPreview(value)}, ascii=\"${byteArrayAsciiPreview(value)}\"}"
        is Array<*> -> value.take(12).joinToString(prefix = "Array[", postfix = "]") { formatDiagValue(it, depth + 1) }
        is Iterable<*> -> value.take(12).joinToString(prefix = "${value.javaClass.name}[", postfix = "]") {
            formatDiagValue(it, depth + 1)
        }
        else -> "${value.javaClass.name}{${truncateDiag(runCatching { value.toString() }.getOrDefault(""), 360)}}"
    }

    return truncateDiag(formatted)
}

internal fun truncateDiag(text: String, limit: Int = GAME_AD_DIAG_TEXT_LIMIT): String {
    return if (text.length <= limit) text else text.take(limit) + "...<truncated ${text.length - limit}>"
}

internal fun shouldLogGameAdMessage(message: Message): Boolean {
    if (isGameAdDiagnosticValue(message.obj)) return true
    if (isGameAdDiagnosticValue(runCatching { message.peekData() }.getOrNull())) return true
    return isRecentGameAdDiagnosticFlow() && (message.obj is Bundle || runCatching { message.peekData() }.getOrNull() != null)
}

internal fun shouldLogGameAdActivityDiagnostic(activity: Activity, args: Array<Any?>?): Boolean {
    return isGameAdInterestingActivity(activity) ||
        args.orEmpty().any { isGameAdDiagnosticValue(it) } ||
        (isRecentGameAdDiagnosticFlow() && activity.javaClass.name.lowercase().contains("quicksilver"))
}

internal fun shouldLogGameAdDiagnosticCall(method: Method, args: Array<Any?>?): Boolean {
    return isRecentGameAdDiagnosticFlow() ||
        isGameAdDiagnosticClassName(method.declaringClass.name) ||
        method.name.hasGameAdSignal() ||
        args.orEmpty().any { isGameAdDiagnosticValue(it) }
}

internal fun isGameAdDiagnosticMethod(clazz: Class<*>, method: Method): Boolean {
    if (method.declaringClass == Any::class.java || method.isSynthetic || method.isBridge) return false
    if (method.name in setOf("wait", "notify", "notifyAll", "hashCode", "equals", "toString")) return false
    if (method.parameterCount > 8) return false

    val strongClass = isGameAdDiagnosticClassName(clazz.name)
    val methodName = method.name.lowercase()
    val signatureSignal = method.parameterTypes.any { isGameAdDiagnosticClassName(it.name) || it == Bundle::class.java || it == Intent::class.java || it == Message::class.java || it == JSONObject::class.java } ||
        isGameAdDiagnosticClassName(method.returnType.name)

    return (strongClass && method.parameterCount <= 6) ||
        methodName.hasGameAdSignal() ||
        signatureSignal
}

internal fun isGameAdDiagnosticClassName(className: String): Boolean {
    val normalized = className.lowercase()
    val simple = normalized.substringAfterLast('.')
    return normalized.startsWith("com.facebook.ads.") ||
        normalized.contains("audiencenetwork") ||
        normalized.contains("instantgamesads") ||
        normalized.contains("neko.playables") ||
        (normalized.contains("quicksilver") && normalized.contains("ad")) ||
        simple in setOf(
            "adsregistry",
            "adsregistry\$adrecord",
            "audiencenetworkremoteserviceapiimpl",
            "audiencenetworkexportedactivityapiimpl",
            "clientmessagedispatchhelper"
        )
}

internal fun isGameAdInterestingActivity(activity: Activity): Boolean {
    val className = activity.javaClass.name.lowercase()
    return activity.javaClass.name in GAME_AD_ACTIVITY_CLASS_NAMES ||
        className.contains("audiencenetwork") ||
        className.contains("neko.playables") ||
        className.contains("quicksilver")
}

@Suppress("DEPRECATION")
internal fun isGameAdDiagnosticValue(value: Any?, depth: Int = 0): Boolean {
    if (value == null || depth > 3) return false
    return when (value) {
        is String -> value.hasGameAdSignal()
        is JSONObject -> value.toString().hasGameAdSignal()
        is JSONArray -> value.toString().hasGameAdSignal()
        is Bundle -> runCatching {
            value.keySet().any { key ->
                key.hasGameAdSignal() || isGameAdDiagnosticValue(value.get(key), depth + 1)
            }
        }.getOrDefault(false)
        is Intent -> {
            val className = value.component?.className.orEmpty()
            className in GAME_AD_ACTIVITY_CLASS_NAMES ||
                className.hasGameAdSignal() ||
                value.action.orEmpty().hasGameAdSignal() ||
                isGameAdDiagnosticValue(value.extras, depth + 1)
        }
        is Message -> isGameAdDiagnosticValue(value.obj, depth + 1) ||
            isGameAdDiagnosticValue(runCatching { value.peekData() }.getOrNull(), depth + 1)
        is Array<*> -> value.take(16).any { isGameAdDiagnosticValue(it, depth + 1) }
        is Iterable<*> -> value.take(16).any { isGameAdDiagnosticValue(it, depth + 1) }
        else -> value.javaClass.name.hasGameAdSignal() ||
            runCatching { value.toString().hasGameAdSignal() }.getOrDefault(false)
    }
}

internal fun String.hasGameAdSignal(): Boolean {
    val normalized = lowercase()
    return normalized.contains("audiencenetwork") ||
        normalized.contains("instantgame") ||
        normalized.contains("quicksilver") ||
        normalized.contains("reward") ||
        normalized.contains("interstitial") ||
        normalized.contains("adinstance") ||
        normalized.contains("placementid") ||
        normalized.contains("showadasync") ||
        normalized.contains("loadadasync") ||
        normalized.contains("getrewarded") ||
        normalized.contains("getinterstitial") ||
        normalized.contains("bannerad") ||
        normalized.contains("didcomplete") ||
        normalized.contains("completiongesture") ||
        normalized.contains("com.facebook.ads") ||
        normalized.contains("neko.playables") ||
        normalized.contains("adsregistry") ||
        normalized.contains("clientmessagedispatchhelper")
}

internal fun hookAudienceNetworkRewardFallbacks(classLoader: ClassLoader) {
    if (!audienceNetworkRewardHooksInstalled.compareAndSet(0, 1)) return

    listOf(
        "com.facebook.ads.RewardedVideoAd",
        "com.facebook.ads.RewardedInterstitialAd",
        "com.facebook.ads.RewardedVideoAdListener",
        "com.facebook.ads.RewardedInterstitialAdListener",
        "com.facebook.ads.RewardedVideoAd\$RewardedVideoAdLoadConfigBuilder",
        "com.facebook.ads.RewardedInterstitialAd\$RewardedInterstitialAdLoadConfigBuilder"
    ).forEach { className ->
        runCatching { tryHookAudienceNetworkRewardClass(classLoader.loadClass(className)) }
    }

    (ClassLoader::class.java.declaredMethods + ClassLoader::class.java.methods)
        .filter { method ->
            method.name == "loadClass" &&
                method.parameterTypes.isNotEmpty() &&
                method.parameterTypes[0] == String::class.java
        }
        .distinctBy { method ->
            method.name + method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
        }
        .forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val clazz = param.result as? Class<*> ?: return
                    if (isAudienceNetworkRewardRelevantClass(clazz.name)) {
                        tryHookAudienceNetworkRewardClass(clazz)
                    }
                }
            })
        }

    Log.i(TAG, "Hooked Audience Network reward dynamic class fallback")
}

internal fun tryHookAudienceNetworkRewardClass(clazz: Class<*>) {
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
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val adObject = param.thisObject ?: return
                        markGameAdDiagnosticFlow("anReward.show ${method.declaringClass.name}.${method.name}")
                        logGameAdDiagnostic(
                            "anReward.show.before",
                            "${methodSignature(method)} this=${formatDiagValue(adObject)} args=${formatDiagArgs(param.args)}"
                        )
                        if (!ENABLE_GAME_AD_AUTOFIX) return

                        if (!completeAudienceNetworkRewardObject(
                                adObject,
                                "show ${method.declaringClass.name}.${method.name}"
                            )
                        ) {
                            return
                        }

                        param.result = when (method.returnType) {
                            Boolean::class.javaPrimitiveType, Boolean::class.java -> true
                            else -> null
                        }
                        Log.i(TAG, "Skipped Audience Network rewarded show via ${method.declaringClass.name}.${method.name}")
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        logGameAdDiagnostic(
                            "anReward.show.after",
                            "${methodSignature(method)} result=${formatDiagValue(param.result)} throwable=${formatDiagThrowable(param.throwable)}"
                        )
                    }
                })
                hooked++
            } else if (isAudienceNetworkRewardListenerRegistrationMethod(method)) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        logGameAdDiagnostic(
                            "anReward.listener.before",
                            "${methodSignature(method)} this=${formatDiagValue(param.thisObject)} args=${formatDiagArgs(param.args)}"
                        )
                        rememberAudienceNetworkRewardListeners(param.thisObject, param.args, method)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        rememberAudienceNetworkRewardListeners(param.thisObject, param.args, method)
                        rememberAudienceNetworkRewardListeners(param.result, param.args, method)
                        logGameAdDiagnostic(
                            "anReward.listener.after",
                            "${methodSignature(method)} result=${formatDiagValue(param.result)} throwable=${formatDiagThrowable(param.throwable)}"
                        )
                    }
                })
                hooked++
            } else if (isAudienceNetworkRewardLoadMethod(clazz, method)) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        markGameAdDiagnosticFlow("anReward.load ${method.declaringClass.name}.${method.name}")
                        logGameAdDiagnostic(
                            "anReward.load.before",
                            "${methodSignature(method)} this=${formatDiagValue(param.thisObject)} args=${formatDiagArgs(param.args)}"
                        )
                        rememberAudienceNetworkRewardListeners(param.thisObject, param.args, method)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        logGameAdDiagnostic(
                            "anReward.load.after",
                            "${methodSignature(method)} result=${formatDiagValue(param.result)} throwable=${formatDiagThrowable(param.throwable)}"
                        )
                    }
                })
                hooked++
            }
        }.onFailure {
            Log.w(TAG, "Failed to hook Audience Network reward method ${clazz.name}.${method.name}", it)
        }
    }

    if (hooked > 0) {
        Log.i(TAG, "Hooked $hooked Audience Network reward method(s) in $className")
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
            Log.i(
                TAG,
                "Remembered Audience Network reward listener ${arg.javaClass.name} from ${method.declaringClass.name}.${method.name}"
            )
        } else {
            findAudienceNetworkRewardListeners(arg).firstOrNull()?.let { listener ->
                audienceNetworkRewardAdListeners[owner] = listener
                Log.i(
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
        Log.i(TAG, "Completed Audience Network reward callbacks invoked=$invoked listeners=${listeners.size} via $source")
        completeRecentGameAdRequests(source)
        return true
    }

    Log.w(TAG, "No Audience Network reward listener completed for ${adObject.javaClass.name} via $source")
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
                    Log.i(
                        TAG,
                        "Invoked Audience Network callback ${listener.javaClass.name}.${method.name} via $source"
                    )
                }.onFailure {
                    Log.w(TAG, "Failed Audience Network callback ${listener.javaClass.name}.${method.name}", it)
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
                Log.i(TAG, "Dispatched hidebannerad for game banner message type=$messageType")
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
        Log.i(TAG, "Re-resolved $resolved recent game ad request(s) via $source")
    }
}

internal fun shouldConvertGameAdRejectToSuccess(promiseId: String, reason: String): Boolean {
    val snapshot = gameAdPromiseSnapshots[promiseId]
    if (shouldAutofixGameAdMessage(snapshot?.messageType)) return true
    if (snapshot != null && shouldForceGameAdSuccess(snapshot.payload, snapshot.messageType)) return true

    val normalized = reason.lowercase()
    if (!isRecentGameAdActivityClose()) return false
    return normalized.contains("banner")
}

internal fun shouldAutofixGameAdMessage(messageType: String?): Boolean {
    return messageType in GAME_AD_AUTOFIX_MESSAGE_TYPES
}

// True when the message should be intercepted and resolved as success on the
// spot: banner lifecycle messages, rewarded requests, and reward-flavored
// load/show calls. The game then grants the reward without any ad rendering.
internal fun shouldForceGameAdSuccess(payload: Any?, messageType: String?): Boolean {
    if (shouldAutofixGameAdMessage(messageType)) return true
    if (messageType !in setOf("loadadasync", "showadasync")) return false
    return hasRewardGameAdSignal(payload, messageType)
}

internal fun hasRewardGameAdSignal(payload: Any?, messageType: String?): Boolean {
    if (messageType in GAME_AD_REWARD_MESSAGE_TYPES) return true

    val content = extractGameAdContent(payload)
    val adInstanceId = content?.optString("adInstanceID")?.takeIf { it.isNotBlank() }
    val knownType = adInstanceId?.let { gameAdInstanceTypes[it] }
    if (knownType in GAME_AD_REWARD_MESSAGE_TYPES) return true

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

internal fun hookPlayableAdActivity(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val activity = param.thisObject as? Activity ?: return
            if (activity.javaClass.name != method.declaringClass.name) return
            handleGameAdActivity(activity, "direct hook ${method.declaringClass.name}.${method.name}")
        }
    })
}

internal fun hookGlobalGameAdActivityLifecycleFallback() {
    if (!gameAdActivityLifecycleHookInstalled.compareAndSet(false, true)) return
    val onResume = (Activity::class.java.declaredMethods + Activity::class.java.methods).firstOrNull { method ->
        method.name == "onResume" && method.parameterCount == 0
    }?.apply { isAccessible = true } ?: return

    XposedBridge.hookMethod(onResume, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val activity = param.thisObject as? Activity ?: return
            val isGameAdActivity = activity.javaClass.name in GAME_AD_ACTIVITY_CLASS_NAMES
            if (!(ENABLE_GAME_AD_DIAGNOSTICS && isGameAdActivity)) {
                scheduleGameAdSurfaceSweep(activity.window?.decorView, "activity resume ${activity.javaClass.name}")
            }
            if (activity.javaClass.name !in GAME_AD_ACTIVITY_CLASS_NAMES) return
            markGameAdDiagnosticFlow("activity.onResume ${activity.javaClass.name}")
            logGameAdDiagnostic(
                "activity.onResume",
                "${activity.javaClass.name} intent=${formatDiagValue(activity.intent)}"
            )
            handleGameAdActivity(activity, "global lifecycle fallback")
        }
    })

    Log.i(TAG, "Hooked global game ad activity lifecycle fallback")
}

internal fun hookGameAdActivityLaunchFallbacks() {
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
            hookGameAdActivityLaunchMethod(method)
            hooked++
        }.onFailure {
            Log.w(TAG, "Failed to hook game ad launch fallback ${method.declaringClass.name}.${method.name}", it)
        }
    }
    Log.i(TAG, "Hooked $hooked game ad activity launch fallback method(s)")
}

internal fun hookGameAdActivityLaunchMethod(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return
            val blockedClassName = resolveBlockedGameAdActivity(intent) ?: return
            markGameAdDiagnosticFlow("activity.launch $blockedClassName")
            logGameAdDiagnostic(
                "activity.launch.before",
                "${methodSignature(method)} target=$blockedClassName args=${formatDiagArgs(param.args)}"
            )
            if (!ENABLE_GAME_AD_AUTOFIX) return
            if (!shouldBlockGameAdActivityLaunch(blockedClassName)) return
            completeRecentGameAdRequests("launch fallback $blockedClassName")
            if (method.returnType == Boolean::class.javaPrimitiveType) {
                param.result = false
            } else {
                param.result = null
            }
            Log.i(
                TAG,
                "Blocked game ad activity launch to $blockedClassName via ${method.declaringClass.name}.${method.name}"
            )
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return
            val blockedClassName = resolveBlockedGameAdActivity(intent) ?: return
            logGameAdDiagnostic(
                "activity.launch.after",
                "${methodSignature(method)} target=$blockedClassName result=${formatDiagValue(param.result)} throwable=${formatDiagThrowable(param.throwable)}"
            )
        }
    })
}

internal fun shouldBlockGameAdActivityLaunch(className: String): Boolean {
    return className in HARD_BLOCKED_GAME_AD_ACTIVITY_CLASS_NAMES ||
        (className in setOf(AUDIENCE_NETWORK_ACTIVITY_CLASS, AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS) &&
            isRecentUnavailableGameAd())
}

internal fun resolveBlockedGameAdActivity(intent: Intent): String? {
    val explicitTarget = intent.component?.className
    if (explicitTarget != null && explicitTarget in GAME_AD_ACTIVITY_CLASS_NAMES) {
        return explicitTarget
    }
    return null
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
        Log.i(TAG, "Audience Network reward close skipped; missing decor via $source")
        return
    }

    Log.i(
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
                Log.i(TAG, "Audience Network close button not ready after ${delayMs}ms via $source")
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
            Log.i(TAG, "Clicked Audience Network close candidate ${view.javaClass.name} via $source")
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
    if (marker.contains("fullscreenadtoolbar") && marker.contains("close")) return 230
    if ((view.id == 33 || view.id == 34) && isTopRightSmallControl(view, root) && marker.contains("imageview")) return 160
    if (marker.contains("close") || marker.contains("dismiss") || marker.contains("skip") || marker.contains("done")) {
        return 120
    }
    if (!view.isClickable) return 0
    if (className.contains("close") || className.contains("dismiss")) return 70
    return 0
}

internal fun isTopRightSmallControl(view: View, root: View): Boolean {
    val rootWidth = root.width.takeIf { it > 0 } ?: return false
    val rootHeight = root.height.takeIf { it > 0 } ?: return false
    val width = view.width
    val height = view.height
    if (width !in 1..260 || height !in 1..260) return false

    val location = IntArray(2)
    return runCatching {
        view.getLocationOnScreen(location)
        location[0] > rootWidth * 0.55f && location[1] < rootHeight * 0.32f
    }.getOrDefault(false)
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
    Log.i(TAG, "Closed game ad activity ${activity.javaClass.name} via $source")
}

internal fun buildGameAdActivityResultIntent(): Intent {
    return Intent().apply {
        putExtra("success", true)
    }
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

    Log.i(TAG, "Forced Audience Network reward callbacks invoked=$invoked inspected=$inspected via $source")
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
                Log.w(TAG, "Failed to invoke Audience Network reward callback ${target.javaClass.name}.${method.name}", it)
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

// Framework-only view-level safety net (View.addView/setText/setContentDescription/
// setVisibility, WebView, Activity.onResume). None of it needs DexKit, so it is
// installed right after Application.attach — before any feed, reels, or
// marketplace content mounts — closing the cold-start race in which the first
// sponsored tiles render before the full DexKit pass finishes seconds later.
// Both installers are idempotent, so the later DexKit-time invocation is a
// no-op.
fun installGlobalAdSurfaceFallbacksEarly() {
    runCatching { hookGlobalGameAdSurfaceFallbacks() }
        .onFailure { Log.w(TAG, "Failed early global ad surface fallbacks", it) }
    runCatching { hookGlobalGameAdActivityLifecycleFallback() }
        .onFailure { Log.w(TAG, "Failed early global ad activity lifecycle fallback", it) }
}

internal fun hookGlobalGameAdSurfaceFallbacks() {
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
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val parent = param.thisObject as? ViewGroup
                    val child = param.args.firstOrNull { it is View } as? View ?: return
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
            })
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
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val textView = param.thisObject as? TextView ?: return
                    if (isExplicitFeedAdMarkerText(textView.text)) {
                        hideLikelyAdContainer(textView, "explicit feed ad text")
                        return
                    }
                    if (!ENABLE_FEED_UI_MARKER_FALLBACKS) return
                    if (isAnyAdMarkerText(textView.text)) {
                        hideLikelyAdContainer(textView, "ad marker text")
                    } else if (isFeedReelCtaAdMarkerText(textView.text)) {
                        hideLikelyFeedReelCtaAdContainer(textView, "feed reel CTA text")
                    }
                }
            })
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
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (isExplicitFeedAdMarkerText(view.contentDescription)) {
                        hideLikelyAdContainer(view, "explicit feed ad content description")
                        return
                    }
                    // Litho ComponentHost overrides getContentDescription and can
                    // return an aggregated/different value mid-mount, so match on
                    // the value actually being set.
                    val descArg = param.args.getOrNull(0) as? CharSequence
                    if (isReelsShoppingStickerMarkerText(descArg ?: view.contentDescription)) {
                        hideReelsShoppingSticker(view, "reels shopping sticker content description")
                        return
                    }
                    if (!ENABLE_FEED_UI_MARKER_FALLBACKS) return
                    if (isFeedAdMarkerText(view.contentDescription)) {
                        hideLikelyAdContainer(view, "feed ad content description")
                    } else if (isFeedReelCtaAdMarkerText(view.contentDescription)) {
                        hideLikelyFeedReelCtaAdContainer(view, "feed reel CTA content description")
                    }
                }
            })
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
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val webView = param.thisObject as? WebView ?: return
                    injectGameAdHidingScript(webView)
                    scheduleGameAdSurfaceSweep(webView, "webview ${method.name}")
                }
            })
            hooked++
        }

    Log.i(TAG, "Hooked $hooked global ad surface fallback method(s)")
}

internal fun scheduleGameAdSurfaceSweep(view: View?, reason: String) {
    val root = view?.rootView ?: view ?: return
    longArrayOf(0L, 250L, 1_000L, 2_500L, 5_000L).forEach { delayMs ->
        root.postDelayed({
            sweepGameAdSurface(root, reason)
        }, delayMs)
    }
}

internal fun sweepGameAdSurface(view: View?, reason: String): Boolean {
    if (view == null) return false

    var hidden = false
    if (view is WebView) {
        injectGameAdHidingScript(view)
    }
    if (isLikelyExplicitFeedAdCardContainer(view)) {
        hidden = hideLikelyExplicitFeedAdCardContainer(view, reason) || hidden
    }
    if (isPotentialNativeGameAdView(view) || isPotentialExplicitFeedAdMarkerView(view) || (ENABLE_FEED_UI_MARKER_FALLBACKS && (isPotentialFeedAdMarkerView(view) || (view is TextView && isAnyAdMarkerText(view.text))))) {
        hidden = hideLikelyAdContainer(view, reason) || hidden
    }
    if (ENABLE_FEED_UI_MARKER_FALLBACKS && isPotentialFeedReelCtaAdMarkerView(view)) {
        hidden = hideLikelyFeedReelCtaAdContainer(view, reason) || hidden
    }
    if (isReelsShoppingStickerMarkerText(view.contentDescription)) {
        hidden = hideReelsShoppingSticker(view, reason) || hidden
    }

    val group = view as? ViewGroup ?: return hidden
    for (index in 0 until group.childCount) {
        hidden = sweepGameAdSurface(group.getChildAt(index), reason) || hidden
    }
    return hidden
}

internal fun injectGameAdHidingScript(webView: WebView) {
    webView.post {
        runCatching {
            webView.evaluateJavascript(GAME_AD_WEBVIEW_HIDE_SCRIPT, null)
        }
    }
}

internal fun hideLikelyAdContainer(view: View, reason: String): Boolean {
    val root = view.rootView
    val target =
        if (shouldUseExplicitFeedMarkerCardTarget(view)) {
            if (BuildConfig.DEBUG && visibleAdTraceInstalled.get() > 0) {
                traceVisibleFacebookFeedAd(
                    view,
                    view.contentDescription?.toString() ?: reason,
                    view.context.classLoader,
                    0
                )
            }
            resolveLikelyExplicitFeedAdCardTarget(view) ?: run {
                Log.i(
                    TAG,
                    "Skipped explicit feed ad hide via $reason because no safe full-card target was found"
                )
                return false
            }
        } else if (shouldUseFeedMarkerCardTarget(view)) {
            resolveLikelyFeedMarkerCardTarget(view) ?: run {
                Log.i(
                    TAG,
                    "Skipped feed marker hide via $reason because no safe full-card target was found"
                )
                return false
            }
        } else {
            resolveLikelyAdContainerTarget(view)
        }
    return hideResolvedAdSurfaceTarget(
        target = target,
        source = view,
        root = root,
        reason = reason,
        forceCollapseHeight = false
    )
}

internal fun hideResolvedAdSurfaceTarget(
    target: View,
    source: View,
    root: View?,
    reason: String,
    forceCollapseHeight: Boolean
): Boolean {
    var hidden = false
    if (target.visibility != View.GONE) {
        target.visibility = View.GONE
        hidden = true
    }
    target.minimumHeight = 0
    target.layoutParams?.let { params ->
        if (
            forceCollapseHeight ||
            target !== source ||
            isLikelyBannerSized(target, root) ||
            isPotentialNativeGameAdView(target) ||
            isPotentialFeedAdMarkerView(source) ||
            isPotentialExplicitFeedAdMarkerView(source)
        ) {
            params.height = 0
            target.layoutParams = params
            hidden = true
        }
    }
    target.requestLayout()

    if (hidden) {
        Log.i(
            TAG,
            "Hid ad surface via $reason target=${target.javaClass.name} bounds=${target.left},${target.top},${target.right},${target.bottom}"
        )
    }
    return hidden
}

internal fun resolveLikelyAdContainerTarget(view: View): View {
    val root = view.rootView ?: return view
    var current = view
    var selected = view
    val rootWidth = root.width.takeIf { it > 0 } ?: 0
    val rootHeight = root.height.takeIf { it > 0 } ?: 0

    while (true) {
        val parentView = current.parent as? View ?: break
        val parentClassName = parentView.javaClass.name
        if (parentClassName.contains("RecyclerView")) {
            break
        }

        val parentWidth = parentView.width
        val parentHeight = parentView.height
        val looksLikePostContainer =
            rootWidth > 0 &&
                rootHeight > 0 &&
                parentWidth >= (rootWidth * 0.82f).toInt() &&
                parentHeight > 0 &&
                parentHeight < (rootHeight * 0.72f).toInt()

        if (!looksLikePostContainer) {
            break
        }

        val currentHeight = current.height.takeIf { it > 0 } ?: parentHeight
        if (
            currentHeight > 0 &&
            parentHeight > maxOf((currentHeight * 1.25f).toInt(), currentHeight + 180)
        ) {
            break
        }

        selected = parentView
        current = parentView
    }

    return selected
}

internal fun resolveLikelyExplicitFeedAdCardTarget(view: View): View? {
    val root = view.rootView ?: return null
    val rootWidth = root.width.takeIf { it > 0 } ?: return null
    val rootHeight = root.height.takeIf { it > 0 } ?: return null

    var current: View? = view
    var best: View? = null
    var bestHeight = -1

    while (current != null) {
        if (isLikelyExplicitFeedAdCardContainer(current, rootWidth, rootHeight)) {
            val candidateHeight = current.height
            if (candidateHeight > bestHeight) {
                best = current
                bestHeight = candidateHeight
            }
        }

        val parentView = current.parent as? View ?: break
        current = parentView
    }

    return best
}

internal fun resolveLikelyFeedMarkerCardTarget(view: View): View? {
    val root = view.rootView ?: return null
    val rootWidth = root.width.takeIf { it > 0 } ?: return null
    val rootHeight = root.height.takeIf { it > 0 } ?: return null

    var current: View? = view
    var best: View? = null
    var bestHeight = -1

    while (current != null) {
        if (isSafeFeedMarkerCardCandidate(current, rootWidth, rootHeight)) {
            val candidateHeight = current.height
            if (candidateHeight > bestHeight) {
                best = current
                bestHeight = candidateHeight
            }
        }

        val parentView = current.parent as? View ?: break
        current = parentView
    }

    return best
}

internal fun resolveLikelyFeedReelCtaAdContainerTarget(view: View): View? {
    val root = view.rootView ?: return null
    val rootWidth = root.width.takeIf { it > 0 } ?: return null
    val rootHeight = root.height.takeIf { it > 0 } ?: return null

    var current: View? = view
    while (current != null) {
        if (isLikelyFeedReelCtaAdContainer(current, rootWidth, rootHeight)) {
            return current
        }
        val parentView = current.parent as? View ?: break
        current = parentView
    }
    return null
}

internal fun isPotentialNativeGameAdView(view: View?): Boolean {
    val className = view?.javaClass?.name?.lowercase() ?: return false
    return className == "com.facebook.ads.adview" ||
        (className.endsWith(".adview") &&
            (className.startsWith("com.facebook.ads.") || className.contains("audiencenetwork"))) ||
        className.contains("adchoices")
}

internal fun collectViewMarkerTexts(view: View?): List<String> {
    if (view == null) return emptyList()

    // Intentionally reads only contentDescription and text. Calling
    // createAccessibilityNodeInfo() here runs Facebook's custom view accessibility
    // code mid-mount, which blanks page-profile header text ("Sign up", "Followers",
    // "posts") after pull-to-refresh on 576, so that probe must stay out.
    val values = LinkedHashSet<String>()
    view.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(values::add)
    (view as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }?.let(values::add)
    return values.toList()
}

internal fun isGameAdMarkerText(value: CharSequence?): Boolean {
    if (value.isNullOrBlank()) return false
    val normalized = value.toString().lowercase()
    return normalized.contains("ads served by meta") ||
        normalized.contains("ad choices") ||
        normalized.contains("adchoices")
}

internal fun isLikelyBannerSized(view: View, root: View?): Boolean {
    val rootHeight = root?.height?.takeIf { it > 0 } ?: return view.height in 1..360
    val height = view.height
    if (height <= 0 || height > maxOf(360, rootHeight / 3)) return false
    val location = IntArray(2)
    return runCatching {
        view.getLocationOnScreen(location)
        location[1] + height > rootHeight / 2
    }.getOrDefault(true)
}

internal fun resolveGameAdPayload(target: Any?, payload: Any?, messageType: String? = null): Boolean {
    if (target == null || payload == null) return false

    val promiseId = extractPromiseId(payload)
    if (promiseId == null) {
        Log.w(TAG, "Unable to extract promiseID for resolved game ad payload")
        return false
    }

    val resolveMethod = resolveGameAdResolveMethod(target.javaClass)
    if (resolveMethod == null) {
        Log.w(TAG, "Unable to resolve success helper for resolved game ad payload")
        return false
    }

    val successPayload = buildGameAdSuccessPayload(payload, messageType)
    return runCatching {
        resolveMethod.invoke(target, promiseId, successPayload)
        true
    }.getOrElse {
        Log.e(TAG, "Failed to resolve game ad payload", it)
        false
    }
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
            Log.e(TAG, "Failed to reject game ad payload via bridge reject helper", it)
            false
        }
        if (success) {
            return true
        }
    }

    val promiseId = extractPromiseId(payload)
    if (promiseId == null) {
        Log.w(TAG, "Unable to extract promiseID for rejected game ad payload")
        return false
    }
    val rejectMethod = resolveGameAdRejectMethod(target.javaClass)
    if (rejectMethod == null) {
        Log.w(TAG, "Unable to resolve reject helper for rejected game ad payload")
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
        Log.e(TAG, "Failed to reject game ad payload", it)
        false
    }
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

internal fun dispatchGameEvent(target: Any?, eventType: String, content: Any?): Boolean {
    if (target == null) return false
    val dispatchMethod = resolveGameEventDispatchMethod(target.javaClass) ?: return false
    val eventValue = resolveGameEventValue(dispatchMethod.parameterTypes[0], eventType) ?: return false

    return runCatching {
        dispatchMethod.invoke(target, eventValue, content ?: JSONObject.NULL)
        true
    }.getOrElse {
        Log.w(TAG, "Failed to dispatch game event type=$eventType", it)
        false
    }
}

internal fun resolveGameEventDispatchMethod(type: Class<*>?): Method? {
    if (type == null) return null
    return (type.declaredMethods + type.methods).firstOrNull { method ->
        !Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            method.parameterCount == 2 &&
            method.parameterTypes[0] != String::class.java &&
            method.parameterTypes[1] == Any::class.java
    }?.apply { isAccessible = true }
}

internal fun resolveGameEventValue(eventType: Class<*>, eventName: String): Any? {
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

internal fun extractGameAdContent(payload: Any?): JSONObject? {
    val json = payload as? JSONObject ?: return null
    return json.optJSONObject("content")
}

internal fun buildGameAdPayloadFromServiceBundle(bundle: Bundle, messageType: String): JSONObject {
    return JSONObject().apply {
        put("type", messageType)
        put("content", bundleToJsonObject(bundle))
    }
}

@Suppress("DEPRECATION")
internal fun bundleToJsonObject(bundle: Bundle): JSONObject {
    val json = JSONObject()
    runCatching { bundle.keySet().toList() }
        .getOrDefault(emptyList())
        .forEach { key ->
            val value = runCatching { bundle.get(key) }.getOrNull()
            putJsonCompatibleValue(json, key, value)
        }
    return json
}

internal fun putJsonCompatibleValue(json: JSONObject, key: String, value: Any?) {
    when (value) {
        null -> json.put(key, JSONObject.NULL)
        is String -> json.put(key, value)
        is Boolean -> json.put(key, value)
        is Number -> json.put(key, value)
        is JSONObject -> json.put(key, value)
        is JSONArray -> json.put(key, value)
        is Bundle -> json.put(key, bundleToJsonObject(value))
        else -> json.put(key, value.toString())
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
    if (hasRewardGameAdSignal(payload, effectiveMessageType)) {
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
            gameAdInstanceTypes.putIfAbsent(adInstanceId, type)
        }
    }

    return result
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
    if (hasRewardGameAdSignal(payload, messageType)) {
        result.put("completed", true)
        result.put("didComplete", true)
        result.put("watched", true)
        result.put("rewarded", true)
        result.put("completionGesture", "post")
    }
    return result
}

internal fun copyJsonObject(source: JSONObject): JSONObject {
    val result = JSONObject()
    val keys = source.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        result.put(key, source.opt(key))
    }
    return result
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

internal fun installGameAdsHooksPipeline(
    classLoader: ClassLoader,
    hooks: ResolvedHooks
): Boolean {
    var installedAny = false

    hooks.gameAdRequestMethods.forEach { method ->
        runCatching { hookGameAdRequest(method); installedAny = true }
            .onFailure { Log.e(TAG, "Failed to hook game ad request ${method.declaringClass.name}.${method.name}", it) }
    }

    hooks.gameAdBridgePostMessageMethod?.let { method ->
        gameAdBridgeEntryMethodsHooked.add(methodHookKey(method))
        runCatching { hookGameAdBridge(method); installedAny = true }
            .onFailure { Log.e(TAG, "Failed to hook game ad bridge ${method.declaringClass.name}.${method.name}", it) }
    }

    hooks.gameAdRequestMethods.firstOrNull()?.declaringClass?.let { bridgeClass ->
        runCatching { hookGameAdResultMethods(bridgeClass); installedAny = true }
            .onFailure { Log.e(TAG, "Failed to hook game ad result helpers ${bridgeClass.name}", it) }
        runCatching { hookGameAdServiceDispatchMethods(bridgeClass); installedAny = true }
            .onFailure { Log.e(TAG, "Failed to hook game ad service dispatch ${bridgeClass.name}", it) }
    }

    if (ENABLE_AUDIENCE_NETWORK_REWARD_FALLBACKS) {
        runCatching { hookAudienceNetworkRewardFallbacks(classLoader); installedAny = true }
            .onFailure { Log.e(TAG, "Failed to hook Audience Network reward fallbacks", it) }
    } else {
        Log.i(TAG, "Skipped Audience Network reward fallback hooks for compatibility mode")
    }

    runCatching { installGameAdJavascriptInterfaceBridgeHook(); installedAny = true }
    runCatching { hookGameAdSystemDiagnostics(classLoader) }
        .onFailure { Log.e(TAG, "Failed to hook game ad diagnostics", it) }

    hooks.playableAdActivityOnCreate?.let { method ->
        runCatching { hookPlayableAdActivity(method); installedAny = true }
            .onFailure { Log.e(TAG, "Failed to hook playable ad activity ${method.declaringClass.name}.${method.name}", it) }
    }

    hooks.gameAdUiActivityMethods.forEach { method ->
        runCatching { hookPlayableAdActivity(method); installedAny = true }
            .onFailure { Log.e(TAG, "Failed to hook game ad activity ${method.declaringClass.name}.${method.name}", it) }
    }

    runCatching { hookGlobalGameAdActivityLifecycleFallback(); installedAny = true }
        .onFailure { Log.e(TAG, "Failed to hook global game ad activity lifecycle fallback", it) }
    runCatching { hookGameAdActivityLaunchFallbacks(); installedAny = true }
        .onFailure { Log.e(TAG, "Failed to hook game ad launch fallbacks", it) }
    runCatching { hookGlobalGameAdSurfaceFallbacks(); installedAny = true }
        .onFailure { Log.e(TAG, "Failed to hook global game ad surface fallbacks", it) }

    return installedAny
}

