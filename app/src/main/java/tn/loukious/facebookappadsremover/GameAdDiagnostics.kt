package tn.loukious.facebookappadsremover

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.Messenger
import android.view.View
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.result.ClassData
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

internal val hookHitCounters = ConcurrentHashMap<String, AtomicInteger>()
internal val gameAdDiagnosticLogCount = AtomicInteger(0)
internal val gameAdDiagnosticClassesHooked = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
internal val gameAdDiagnosticClassesLogged = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

internal fun markGameAdDiagnosticFlow(reason: String) {
    if (!ENABLE_GAME_AD_DIAGNOSTICS) return
    lastGameAdDiagnosticFlowMs.set(System.currentTimeMillis())
    logGameAdDiagnostic("flow.mark", reason)
}

internal fun isRecentGameAdDiagnosticFlow(): Boolean {
    val timestamp = lastGameAdDiagnosticFlowMs.get()
    return timestamp > 0 && System.currentTimeMillis() - timestamp < GAME_AD_DIAG_FLOW_WINDOW_MS
}

internal fun shouldLogGameAdDiagnosticCall(method: Method, args: Array<Any?>?): Boolean {
    return isRecentGameAdDiagnosticFlow() ||
        isGameAdDiagnosticClassName(method.declaringClass.name) ||
        method.name.hasGameAdSignal() ||
        args.orEmpty().any { isGameAdDiagnosticValue(it) }
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

internal fun isGameAdInterestingActivity(activity: Activity): Boolean {
    val name = activity.javaClass.name
    return name in GAME_AD_ACTIVITY_CLASS_NAMES ||
        name.hasGameAdSignal() ||
        activity.intent?.component?.className?.hasGameAdSignal() == true
}

internal fun tryHookGameAdDiagnosticClass(module: XposedModule, clazz: Class<*>) {
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
                module.hook(method).intercept { chain ->
                    if (shouldLogGameAdDiagnosticCall(method, chain.args.toTypedArray())) {
                        markGameAdDiagnosticFlow("dynamic ${method.declaringClass.name}.${method.name}")
                        logGameAdDiagnostic(
                            "dynamic.before",
                            "${methodSignature(method)} this=${formatDiagValue(chain.thisObject)} args=${formatDiagArgs(chain.args.toTypedArray())}"
                        )
                    }
                    
                    val result = chain.proceed()
                    
                    if (shouldLogGameAdDiagnosticCall(method, chain.args.toTypedArray()) || isRecentGameAdDiagnosticFlow()) {
                        logGameAdDiagnostic(
                            "dynamic.after",
                            "${methodSignature(method)} result=${formatDiagValue(result)} throwable=none"
                        )
                    }
                    result
                }
                hooked++
            }.onFailure {
                Logger.w(TAG, "Failed to hook game ad diagnostic method ${clazz.name}.${method.name}", it)
            }
        }

    if (hooked > 0) {
        Logger.i(TAG, "Hooked $hooked passive game ad diagnostic method(s) in $className")
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

internal fun logMissingHooks(
    pluginPackClasses: List<ClassData>,
    factoryMethod: Method?,
    pluginMethods: List<Method>,
    instreamBannerEligibilityMethod: Method?,
    indicatorPillAdEligibilityMethod: Method?,
    reelsBannerRenderMethods: List<Method>,
    feedCsrFilterHooks: List<tn.loukious.facebookappadsremover.FeedCsrFilterHook>,
    lateFeedListHooks: List<tn.loukious.facebookappadsremover.FeedListSanitizerHook>,
    storyPoolAddMethods: List<Method>,
    sponsoredPoolClass: ClassData?,
    poolAddMethod: Method?,
    sponsoredStoryManagerClass: ClassData?,
    sponsoredStoryNextMethod: Method?,
    storyAdProviderClasses: List<ClassData>,
    storyAdProviders: List<tn.loukious.facebookappadsremover.StoryAdProviderHooks>,
    gameAdRequestMethods: List<Method>,
    gameAdBridgePostMessageMethod: Method?,
    playableAdActivityOnCreate: Method?,
    gameAdUiActivityMethods: List<Method>
) {
    if (factoryMethod == null) Logger.missing(TAG, "Reels list factory method")
    if (pluginPackClasses.isEmpty()) {
        Logger.missing(TAG, "PluginPack classes")
    } else if (pluginMethods.isEmpty()) {
        Logger.missing(TAG, "PluginPack build methods")
    }
    if (instreamBannerEligibilityMethod == null) Logger.missing(TAG, "Instream banner eligibility method")
    if (indicatorPillAdEligibilityMethod == null) Logger.missing(TAG, "Reels indicator pill eligibility method")
    if (reelsBannerRenderMethods.isEmpty()) Logger.missing(TAG, "Reels banner render methods")
    if (feedCsrFilterHooks.isEmpty()) Logger.missing(TAG, "Feed CSR filter methods")
    if (lateFeedListHooks.isEmpty()) Logger.missing(TAG, "Late feed list sanitizer methods")
    if (storyPoolAddMethods.isEmpty()) Logger.missing(TAG, "Story pool add methods")
    if (sponsoredPoolClass == null) {
        Logger.missing(TAG, "Sponsored pool class")
    } else if (poolAddMethod == null) {
        Logger.missing(TAG, "Sponsored pool add method")
    }
    if (sponsoredStoryManagerClass == null) {
        Logger.missing(TAG, "Sponsored story manager class")
    } else if (sponsoredStoryNextMethod == null) {
        Logger.missing(TAG, "Sponsored story next method")
    }
    if (storyAdProviderClasses.isEmpty()) Logger.missing(TAG, "Story ad source classes")
    if (storyAdProviders.isEmpty()) Logger.missing(TAG, "Story ad provider methods")
    if (gameAdRequestMethods.isEmpty()) Logger.missing(TAG, "Game ad request methods")
    if (gameAdBridgePostMessageMethod == null) Logger.missing(TAG, "Game ad bridge postMessage method")
    if (playableAdActivityOnCreate == null) Logger.missing(TAG, "Playable ad activity lifecycle method")
    if (gameAdUiActivityMethods.isEmpty()) Logger.missing(TAG, "Game ad UI activity lifecycle methods")
}

internal fun logHookHitThrottled(hookName: String, method: Method, detail: String? = null) {
    val hits = hookHitCounters.computeIfAbsent(hookName) { AtomicInteger(0) }.incrementAndGet()
    if (hits <= 3 || hits % HOOK_HIT_LOG_EVERY == 0) {
        val extra = detail?.let { " $it" } ?: ""
        Logger.i(TAG, "Hook hit $hookName count=$hits at ${method.declaringClass.name}.${method.name}$extra")
    }
}

internal fun hookMessengerSendDiagnostics(module: XposedModule) {
    val sendMethods = (Messenger::class.java.declaredMethods + Messenger::class.java.methods)
        .filter { method ->
            method.name == "send" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == Message::class.java
        }
        .distinctBy { methodSignature(it) }

    sendMethods.forEach { method ->
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            val message = chain.args.getOrNull(0) as? Message
            if (message != null && shouldLogGameAdMessage(message)) {
                markGameAdDiagnosticFlow("messenger.send")
                logGameAdDiagnostic(
                    "messenger.send.before",
                    "${methodSignature(method)} this=${formatDiagValue(chain.thisObject)} message=${formatDiagValue(message)}"
                )
            }
            
            val result = chain.proceed()
            
            if (message != null && shouldLogGameAdMessage(message)) {
                logGameAdDiagnostic(
                    "messenger.send.after",
                    "${methodSignature(method)} result=${formatDiagValue(result)} throwable=none"
                )
            }
            result
        }
    }
}

internal fun hookHandlerMessageDiagnostics(module: XposedModule, classLoader: ClassLoader) {
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
                module.hook(method).intercept { chain ->
                    val message = chain.args.getOrNull(0) as? Message
                    val handlerName = chain.thisObject?.javaClass?.name.orEmpty()
                    val interesting = message != null && (shouldLogGameAdMessage(message) || handlerName.contains("C95084edO") || handlerName.contains("HandlerC95084edO"))
                    
                    if (interesting) {
                        markGameAdDiagnosticFlow("handler.dispatch $handlerName")
                        logGameAdDiagnostic(
                            "handler.dispatch.before",
                            "handler=$handlerName ${methodSignature(method)} message=${formatDiagValue(message)}"
                        )
                    }
                    
                    val result = chain.proceed()
                    
                    if (interesting) {
                        logGameAdDiagnostic(
                            "handler.dispatch.after",
                            "handler=$handlerName result=${formatDiagValue(result)} throwable=none"
                        )
                    }
                    result
                }
            }
    }

    listOf("p000X.HandlerC95084edO", "X.edO").forEach { className ->
        val handlerClass = runCatching { classLoader.loadClass(className) }.getOrNull() ?: return@forEach
        (handlerClass.declaredMethods + handlerClass.methods)
            .filter { method ->
                method.name == "handleMessage" &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0] == Message::class.java
            }
            .distinctBy { methodSignature(it) }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    val message = chain.args.getOrNull(0) as? Message
                    markGameAdDiagnosticFlow("quicksilver.handleMessage")
                    logGameAdDiagnostic(
                        "quicksilver.handleMessage.before",
                        "${methodSignature(method)} this=${formatDiagValue(chain.thisObject)} message=${formatDiagValue(message)}"
                    )
                    
                    val result = chain.proceed()
                    
                    logGameAdDiagnostic(
                        "quicksilver.handleMessage.after",
                        "${methodSignature(method)} result=${formatDiagValue(result)} throwable=none"
                    )
                    result
                }
            }
    }
}

internal fun hookActivityResultDiagnostics(module: XposedModule) {
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
            module.hook(method).intercept { chain ->
                val activity = chain.thisObject as? Activity
                if (activity != null && shouldLogGameAdActivityDiagnostic(activity, chain.args.toTypedArray())) {
                    markGameAdDiagnosticFlow("activity.${method.name} ${activity.javaClass.name}")
                    logGameAdDiagnostic(
                        "activity.${method.name}.before",
                        "${activity.javaClass.name} ${methodSignature(method)} args=${formatDiagArgs(chain.args.toTypedArray())} intent=${formatDiagValue(activity.intent)}"
                    )
                    if (method.name == "finish") {
                        dumpAudienceNetworkActivityState(activity, "activity.finish.before")
                    }
                }
                
                val result = chain.proceed()
                
                if (activity != null && shouldLogGameAdActivityDiagnostic(activity, chain.args.toTypedArray())) {
                    logGameAdDiagnostic(
                        "activity.${method.name}.after",
                        "${activity.javaClass.name} result=${formatDiagValue(result)} throwable=none"
                    )
                }
                result
            }
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
            module.hook(method).intercept { chain ->
                val activity = chain.args.getOrNull(0) as? Activity
                if (activity != null && shouldLogGameAdActivityDiagnostic(activity, chain.args.toTypedArray())) {
                    markGameAdDiagnosticFlow("instrumentation.activityResult ${activity.javaClass.name}")
                    logGameAdDiagnostic(
                        "instrumentation.activityResult.before",
                        "${methodSignature(method)} args=${formatDiagArgs(chain.args.toTypedArray())}"
                    )
                }
                
                val result = chain.proceed()
                
                if (activity != null && shouldLogGameAdActivityDiagnostic(activity, chain.args.toTypedArray())) {
                    logGameAdDiagnostic(
                        "instrumentation.activityResult.after",
                        "${methodSignature(method)} result=${formatDiagValue(result)} throwable=none"
                    )
                }
                result
            }
        }
}

internal fun hookDynamicGameAdClassDiagnostics(module: XposedModule, classLoader: ClassLoader) {
    if (!gameAdDynamicDiagnosticsInstalled.compareAndSet(0, 1)) return

    listOf(
        AUDIENCE_NETWORK_ACTIVITY_CLASS,
        AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS,
        "p000X.HandlerC95084edO",
        "com.facebook.quicksilver.webviewprocess.QuicksilverSeparateProcessAdsLoader"
    ).forEach { className ->
        runCatching { tryHookGameAdDiagnosticClass(module, classLoader.loadClass(className)) }
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
                val className = chain.args.getOrNull(0) as? String
                val clazz = result as? Class<*>
                if (className != null && clazz != null) {
                    if (isGameAdDiagnosticClassName(className) || isGameAdDiagnosticClassName(clazz.name)) {
                        logGameAdDiagnosticClass(clazz)
                        tryHookGameAdDiagnosticClass(module, clazz)
                    }
                }
                result
            }
        }
}

internal fun logGameAdDiagnosticClass(clazz: Class<*>) {
    if (!gameAdDiagnosticClassesLogged.add(clazz.name)) return

    val methodSummary = runCatching {
        (clazz.declaredMethods + clazz.methods)
            .asSequence()
            .filter { isGameAdDiagnosticMethod(clazz, it) }
            .distinctBy { methodSignature(it) }
            .take(16)
            .joinToString(";") { method -> "${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})>${method.returnType.simpleName}" }
    }.getOrDefault("")

    logGameAdDiagnostic(
        "class.loaded",
        "${clazz.name} super=${clazz.superclass?.name} interfaces=${clazz.interfaces.joinToString { it.name }} methods=$methodSummary"
    )
}

internal fun logGameAdDiagnostic(event: String, detail: String) {
    if (!ENABLE_GAME_AD_DIAGNOSTICS) return

    val count = gameAdDiagnosticLogCount.incrementAndGet()
    when {
        count <= GAME_AD_DIAG_LOG_LIMIT -> Logger.i(TAG, "GADIAG[$count] $event ${truncateDiag(detail)}")
        count == GAME_AD_DIAG_LOG_LIMIT + 1 -> Logger.i(TAG, "GADIAG log limit reached; suppressing further diagnostics")
    }
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

internal fun shortObjectLabel(value: Any): String {
    return "${value.javaClass.name}@${Integer.toHexString(System.identityHashCode(value))}"
}

internal fun byteArrayHexPreview(value: ByteArray): String {
    return value.take(48).joinToString(" ") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal fun byteArrayAsciiPreview(value: ByteArray): String {
    return value.take(96).joinToString("") { byte ->
        val code = byte.toInt() and 0xff
        if (code in 32..126) code.toChar().toString() else "."
    }
}

internal fun truncateDiag(text: String, limit: Int = GAME_AD_DIAG_TEXT_LIMIT): String {
    return if (text.length <= limit) text else text.take(limit) + "...<truncated ${text.length - limit}>"
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
        simple in AUDIENCE_NETWORK_FOCUSED_DIAGNOSTIC_CLASS_NAMES ||
        simple in setOf(
            "adsregistry",
            "adsregistry\$adrecord",
            "audiencenetworkremoteserviceapiimpl",
            "audiencenetworkexportedactivityapiimpl",
            "clientmessagedispatchhelper",
            "handlerc95084edo"
        )
}

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
            isGameAdDiagnosticValue(runCatching { messagePeekData(value) }.getOrNull(), depth + 1)
        is Array<*> -> value.take(16).any { isGameAdDiagnosticValue(it, depth + 1) }
        is Iterable<*> -> value.take(16).any { isGameAdDiagnosticValue(it, depth + 1) }
        else -> value.javaClass.name.hasGameAdSignal() ||
            runCatching { value.toString().hasGameAdSignal() }.getOrDefault(false)
    }
}

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

internal fun copyJsonObject(source: JSONObject): JSONObject {
    val result = JSONObject()
    val keys = source.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        result.put(key, source.opt(key))
    }
    return result
}
