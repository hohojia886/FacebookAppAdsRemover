package tn.loukious.facebookappadsremover

import android.app.Activity
import android.view.View
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

fun hookAudienceNetworkRewardFallbacks(module: XposedModule, classLoader: ClassLoader) {
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
        .distinctBy { method ->
            method.name + method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
        }
        .forEach { method ->
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val res = chain.proceed()
                val clazz = res as? Class<*>
                if (clazz != null && isAudienceNetworkRewardRelevantClass(clazz.name)) {
                    tryHookAudienceNetworkRewardClass(module, clazz)
                }
                res
            }
        }

    Logger.i(TAG, "Hooked Audience Network reward dynamic class fallback")
}

fun tryHookAudienceNetworkRewardClass(module: XposedModule, clazz: Class<*>) {
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
                    val adObject = chain.thisObject ?: return@intercept chain.proceed()
                    markGameAdDiagnosticFlow("anReward.show ${method.declaringClass.name}.${method.name}")
                    logGameAdDiagnostic(
                        "anReward.show.before",
                        "${methodSignature(method)} this=${formatDiagValue(adObject)} args=${formatDiagArgs(chain.args)}"
                    )
                    
                    if (ENABLE_GAME_AD_AUTOFIX) {
                        if (completeAudienceNetworkRewardObject(
                                adObject,
                                "show ${method.declaringClass.name}.${method.name}"
                            )
                        ) {
                            Logger.i(TAG, "Skipped Audience Network rewarded show via ${method.declaringClass.name}.${method.name}")
                            return@intercept when (method.returnType) {
                                Boolean::class.javaPrimitiveType, Boolean::class.java -> true
                                else -> null
                            }
                        }
                    }

                    val res = runCatching { chain.proceed() }
                    logGameAdDiagnostic(
                        "anReward.show.after",
                        "${methodSignature(method)} result=${formatDiagValue(res.getOrNull())} throwable=${formatDiagThrowable(res.exceptionOrNull())}"
                    )
                    res.getOrThrow()
                }
                hooked++
            } else if (isAudienceNetworkRewardListenerRegistrationMethod(method)) {
                module.hook(method).intercept { chain ->
                    logGameAdDiagnostic(
                        "anReward.listener.before",
                        "${methodSignature(method)} this=${formatDiagValue(chain.thisObject)} args=${formatDiagArgs(chain.args)}"
                    )
                    rememberAudienceNetworkRewardListeners(chain.thisObject, chain.args, method)
                    val res = runCatching { chain.proceed() }
                    rememberAudienceNetworkRewardListeners(chain.thisObject, chain.args, method)
                    rememberAudienceNetworkRewardListeners(res.getOrNull(), chain.args, method)
                    logGameAdDiagnostic(
                        "anReward.listener.after",
                        "${methodSignature(method)} result=${formatDiagValue(res.getOrNull())} throwable=${formatDiagThrowable(res.exceptionOrNull())}"
                    )
                    res.getOrThrow()
                }
                hooked++
            } else if (isAudienceNetworkRewardLoadMethod(clazz, method)) {
                module.hook(method).intercept { chain ->
                    markGameAdDiagnosticFlow("anReward.load ${method.declaringClass.name}.${method.name}")
                    logGameAdDiagnostic(
                        "anReward.load.before",
                        "${methodSignature(method)} this=${formatDiagValue(chain.thisObject)} args=${formatDiagArgs(chain.args)}"
                    )
                    rememberAudienceNetworkRewardListeners(chain.thisObject, chain.args, method)
                    val res = runCatching { chain.proceed() }
                    logGameAdDiagnostic(
                        "anReward.load.after",
                        "${methodSignature(method)} result=${formatDiagValue(res.getOrNull())} throwable=${formatDiagThrowable(res.exceptionOrNull())}"
                    )
                    res.getOrThrow()
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

fun isAudienceNetworkRewardRelevantClass(className: String): Boolean {
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

fun isAudienceNetworkRewardShowMethod(clazz: Class<*>, method: Method): Boolean {
    val className = clazz.name.lowercase()
    return className.contains("reward") &&
        method.name == "show" &&
        !Modifier.isStatic(method.modifiers) &&
        method.parameterCount <= 1 &&
        (method.returnType == Void.TYPE ||
            method.returnType == Boolean::class.javaPrimitiveType ||
            method.returnType == Boolean::class.java)
}

fun isAudienceNetworkRewardLoadMethod(clazz: Class<*>, method: Method): Boolean {
    return clazz.name.lowercase().contains("reward") &&
        method.name.lowercase().contains("load") &&
        !Modifier.isStatic(method.modifiers) &&
        method.parameterCount >= 1
}

fun isAudienceNetworkRewardListenerRegistrationMethod(method: Method): Boolean {
    if (Modifier.isStatic(method.modifiers) || method.parameterCount == 0) return false
    val name = method.name.lowercase()
    if (name.contains("listener")) return true
    return method.parameterTypes.any { type ->
        val typeName = type.name.lowercase()
        typeName.contains("listener") &&
            (typeName.contains("reward") || typeName.contains("ad"))
    }
}

fun rememberAudienceNetworkRewardListeners(owner: Any?, args: List<Any?>?, method: Method) {
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

fun isAudienceNetworkRewardListenerObject(value: Any?): Boolean {
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

fun audienceNetworkInterfacesFor(type: Class<*>): List<Class<*>> {
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

fun completeAudienceNetworkRewardObject(adObject: Any, source: String): Boolean {
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

fun findAudienceNetworkRewardListeners(root: Any?): List<Any> {
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

fun invokeAudienceNetworkRewardListenerCallbacks(listener: Any, adObject: Any, source: String): Int {
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

fun audienceNetworkCallbackArgs(method: Method, adObject: Any): Array<Any?>? {
    return when (method.parameterCount) {
        0 -> emptyArray()
        1 -> {
            val paramType = method.parameterTypes[0]
            if (paramType.isAssignableFrom(adObject.javaClass)) arrayOf(adObject) else null
        }
        else -> null
    }
}

fun audienceNetworkRewardMethodsFor(type: Class<*>): List<Method> {
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

fun audienceNetworkFieldsFor(type: Class<*>): List<Field> {
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

fun audienceNetworkMethodsFor(type: Class<*>): List<Method> {
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

fun shouldQueueAudienceNetworkObject(value: Any): Boolean {
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

fun shouldTraverseAudienceNetworkObject(value: Any, isRootActivity: Boolean): Boolean {
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

fun findViewOnClickListener(view: View): Any? {
    return runCatching {
        val getListenerInfo = View::class.java.getDeclaredMethod("getListenerInfo").apply { isAccessible = true }
        val listenerInfo = getListenerInfo.invoke(view)
        val mOnClickListener = listenerInfo?.javaClass?.getDeclaredField("mOnClickListener")?.apply { isAccessible = true }
        mOnClickListener?.get(listenerInfo)
    }.getOrNull()
}

fun audienceNetworkParentPath(view: View): String {
    val path = StringBuilder()
    var current: Any? = view.parent
    repeat(6) {
        val p = current as? View ?: return@repeat
        path.append(p.javaClass.simpleName).append("/")
        current = p.parent
    }
    return path.toString()
}

fun isAudienceNetworkFinalExitListener(className: String): Boolean {
    val normalized = className.lowercase()
    return normalized in AUDIENCE_NETWORK_CLOSE_LISTENER_CLASS_NAMES ||
        (normalized.startsWith("com.facebook.ads.") &&
            (normalized.contains("close") || normalized.contains("exit") || normalized.contains("dismiss")))
}

fun isAudienceNetworkClosePromptListener(className: String): Boolean {
    val normalized = className.lowercase()
    return normalized.contains("reward") &&
        (normalized.contains("close") || normalized.contains("exit") || normalized.contains("prompt"))
}
