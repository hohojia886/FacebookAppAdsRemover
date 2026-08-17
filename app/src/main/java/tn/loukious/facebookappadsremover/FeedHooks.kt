package tn.loukious.facebookappadsremover

import tn.loukious.facebookappadsremover.BuildConfig
import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger

fun installFacebook571FeedSourceFastPath(module: XposedModule, classLoader: ClassLoader): Boolean {
    val feedClass = runCatching { Class.forName(FB571_NETWORK_FEED_CLASS, false, classLoader) }.getOrNull()
    val poolClass = runCatching { Class.forName(FB571_SPONSORED_POOL_CLASS, false, classLoader) }.getOrNull()
    if (feedClass == null && poolClass == null) return false

    var installed = 0
    feedClass?.let { clazz ->
        val method = clazz.declaredMethods.firstOrNull { it.name == FB571_NETWORK_FEED_METHOD }
        if (method != null) {
            module.hook(method).intercept { chain ->
                logHookHitThrottled("feedSourceBlock", method)
                null
            }
            installed++
        }
    }
    poolClass?.let { clazz ->
        val method = clazz.declaredMethods.firstOrNull { it.name == FB571_SPONSORED_POOL_ADD_METHOD }
        if (method != null) {
            hookSponsoredPoolAdd(module, method)
            installed++
        }
        hookSponsoredPoolListMethods(module, clazz)
        hookSponsoredPoolResultMethods(module, clazz)
    }
    return installed > 0
}

fun installFacebook571FeedComponentGuard(module: XposedModule, classLoader: ClassLoader): Boolean {
    val componentClass = runCatching {
        Class.forName("X.2OT", false, classLoader)
    }.getOrNull() ?: return false
    val wrapperClass = runCatching {
        Class.forName("X.2Oc", false, classLoader)
    }.getOrNull() ?: return false
    val edgeField = runCatching {
        componentClass.getDeclaredField("A05").apply { isAccessible = true }
    }.getOrNull() ?: return false
    val wrapperChildField = runCatching {
        wrapperClass.getDeclaredField("A03").apply { isAccessible = true }
    }.getOrNull() ?: return false
    val contractTypes = FB571_FEED_ITEM_CONTRACT_CLASSES.mapNotNull { className ->
        runCatching { Class.forName(className, false, classLoader) }.getOrNull()
    }
    val inspector = FeedItemInspector(contractTypes)
    val renderMethods = listOf(componentClass, wrapperClass).mapNotNull { type ->
        type.declaredMethods.firstOrNull { method ->
            method.name == "A1H" &&
                method.parameterCount == 1 &&
                method.returnType.name == "X.3OF"
        }?.apply { isAccessible = true }
    }
    if (renderMethods.size != 2) return false

    var installed = 0
    renderMethods.forEach { method ->
        val key = methodHookKey(method)
        if (!feedComponentMethodsHooked.add(key)) return@forEach

        module.hook(method).intercept { chain ->
            val owner = chain.thisObject ?: return@intercept chain.proceed()
            val component = when {
                componentClass.isInstance(owner) -> owner
                wrapperClass.isInstance(owner) -> runCatching {
                    wrapperChildField.get(owner)
                }.getOrNull()?.takeIf(componentClass::isInstance)
                else -> null
            } ?: return@intercept chain.proceed()
            val edge = runCatching { edgeField.get(component) }.getOrNull() ?: return@intercept chain.proceed()
            if (!inspector.isDefinitelySponsoredFeedItem(edge)) return@intercept chain.proceed()

            logHookHitThrottled(
                "sponsoredFeedComponentBlock",
                method,
                inspector.describe(edge)
            )
            null
        }
        installed++
    }
    if (installed > 0) {
        Logger.i(
            TAG,
            "Installed FB 571 sponsored feed component guards=" +
                renderMethods.joinToString { "${it.declaringClass.name}.${it.name}" }
        )
    }
    return true
}

fun logFacebook571SurvivingFeedTypeContracts(classLoader: ClassLoader) {
    if (!BuildConfig.DEBUG || survivingFeedTypeContractsLogged.getAndIncrement() != 0) return

    FB571_SURVIVING_FEED_TYPE_CLASSES.forEach { className ->
        logSurvivingFeedTypeContract(classLoader, className)
    }
}

fun logSurvivingFeedTypeContract(classLoader: ClassLoader, className: String) {
    val type = runCatching {
        Class.forName(className, false, classLoader)
    }.getOrElse {
        Logger.w(TAG, "SurvivingFeedType class unavailable=$className")
        return
    }
    Logger.i(
        TAG,
        "SurvivingFeedType class=${type.name} super=${type.superclass?.name} " +
            "interfaces=${type.interfaces.joinToString { it.name }}"
    )
    type.declaredFields.forEach { field ->
        Logger.i(
            TAG,
            "SurvivingFeedType field=${type.name}.${field.name}:${field.type.name} " +
                "static=${Modifier.isStatic(field.modifiers)}"
        )
    }
    type.declaredConstructors.forEach { constructor ->
        Logger.i(
            TAG,
            "SurvivingFeedType ctor=${type.name}(" +
                constructor.parameterTypes.joinToString { it.name } + ")"
        )
    }
    type.declaredMethods.forEach { method ->
        Logger.i(
            TAG,
            "SurvivingFeedType method=${type.name}.${method.name}(" +
                method.parameterTypes.joinToString { it.name } + "):${method.returnType.name} " +
                "static=${Modifier.isStatic(method.modifiers)}"
        )
    }
}

fun methodHookKey(method: Method): String {
    return "${method.declaringClass.name}#${method.name}(" +
        method.parameterTypes.joinToString(",") { it.name } +
        "):${method.returnType.name}"
}

fun logHookHitThrottled(hookName: String, method: Method, detail: String? = null) {
    val hits = hookHitCounters.computeIfAbsent(hookName) { AtomicInteger(0) }.incrementAndGet()
    if (hits <= 3 || hits % HOOK_HIT_LOG_EVERY == 0) {
        val extra = detail?.let { " $it" } ?: ""
        Logger.i(TAG, "Hook hit $hookName count=$hits at ${method.declaringClass.name}.${method.name}$extra")
    }
}

fun hookFeedCsrFilterInput(
    module: XposedModule,
    hook: FeedCsrFilterHook,
    feedItemInspector: FeedItemInspector
): Boolean {
    if (!feedCsrMethodsHooked.add(methodHookKey(hook.method))) {
        return false
    }
    module.hook(hook.method).intercept { chain ->
        val filterName = hook.method.declaringClass.name
        val originalList = chain.args.getOrNull(hook.listArgIndex) as? Iterable<*>
        if (originalList != null) {
            logFeedItems("$filterName IN", originalList, feedItemInspector)
            val keptItems = ArrayList<Any?>()
            var removed = 0

            for (item in originalList) {
                if (feedItemInspector.isDefinitelySponsoredFeedItem(item)) {
                    removed++
                } else {
                    keptItems.add(item)
                }
            }

            if (removed > 0) {
                val rebuilt = buildImmutableListLike(chain.args.getOrNull(hook.listArgIndex), keptItems)
                if (rebuilt != null) {
                    val newArgs = chain.args.toTypedArray()
                    newArgs[hook.listArgIndex] = rebuilt
                    Logger.i(TAG, "Removed $removed sponsored feed item(s) before ${hook.method.declaringClass.name}.${hook.method.name}")
                    val res = chain.proceed(newArgs)
                    val finalResult = handleFilterOutput(res, filterName, feedItemInspector)
                    return@intercept finalResult ?: res
                }
            }
        }
        
        val res = chain.proceed()
        val finalResult = handleFilterOutput(res, filterName, feedItemInspector)
        finalResult ?: res
    }
    return true
}

fun handleFilterOutput(result: Any?, filterName: String, feedItemInspector: FeedItemInspector): Any? {
    val resultItems = extractFeedItemsFromResult(result)
    if (resultItems != null) {
        logFeedItems("$filterName OUT", resultItems, feedItemInspector)
        val keptItems = ArrayList<Any?>()
        var removed = 0
        for (item in resultItems) {
            if (feedItemInspector.isDefinitelySponsoredFeedItem(item)) {
                removed++
            } else {
                keptItems.add(item)
            }
        }
        if (removed > 0) {
            val rebuiltResult = result?.let { rebuildFeedResult(it, keptItems) }
            if (rebuiltResult != null) {
                Logger.i(TAG, "Removed $removed sponsored feed item(s) from result of $filterName")
                return rebuiltResult
            }
        }
    }
    return null
}

fun hookLateFeedListSanitizer(
    module: XposedModule,
    hook: FeedListSanitizerHook,
    feedItemInspector: FeedItemInspector
): Boolean {
    if (!lateFeedMethodsHooked.add(methodHookKey(hook.method))) {
        return false
    }
    module.hook(hook.method).intercept { chain ->
        val originalList = chain.args.getOrNull(hook.listArgIndex) as? Iterable<*>
        if (originalList != null) {
            val keptItems = ArrayList<Any?>()
            var removed = 0

            for (item in originalList) {
                if (feedItemInspector.isDefinitelySponsoredFeedItem(item)) {
                    removed++
                } else {
                    keptItems.add(item)
                }
            }

            if (removed > 0) {
                val rebuilt = buildImmutableListLike(chain.args.getOrNull(hook.listArgIndex), keptItems)
                if (rebuilt != null) {
                    val newArgs = chain.args.toTypedArray()
                    newArgs[hook.listArgIndex] = rebuilt
                    Logger.i(
                        TAG,
                        "Late-stage removed $removed sponsored feed item(s) before ${hook.method.declaringClass.name}.${hook.method.name}"
                    )
                    return@intercept chain.proceed(newArgs)
                }
            }
        }
        chain.proceed()
    }
    return true
}

fun hookSponsoredPoolAdd(module: XposedModule, method: Method): Boolean {
    if (!sponsoredPoolMethodsHooked.add(methodHookKey(method))) {
        return false
    }
    module.hook(method).intercept { chain ->
        logHookHitThrottled("sponsoredPoolBlock", method)
        false
    }
    return true
}

fun hookSponsoredStoryNext(module: XposedModule, method: Method) {
    module.hook(method).intercept { chain ->
        Logger.i(TAG, "Blocked sponsored story vending from feed manager")
        null
    }
}

fun hookSponsoredStoryListMethods(module: XposedModule, managerClass: Class<*>) {
    var hooked = 0
    managerClass.declaredMethods
        .filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                isSponsoredStoryListMethod(method)
        }
        .forEach { method ->
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                buildEmptyListReturn(method.returnType) ?: chain.proceed()
            }
            hooked++
        }
    Logger.i(TAG, "Hooked $hooked sponsored story list method(s) on ${managerClass.name}")
}

fun isSponsoredStoryListMethod(method: Method): Boolean {
    if (method.parameterCount > 2) return false
    if (!Iterable::class.java.isAssignableFrom(method.returnType) &&
        method.returnType.name != "com.google.common.collect.ImmutableList"
    ) {
        return false
    }
    return method.parameterTypes.all { type ->
        type == Int::class.javaPrimitiveType ||
            type == Long::class.javaPrimitiveType ||
            type == Boolean::class.javaPrimitiveType
    }
}

fun buildEmptyListReturn(returnType: Class<*>): Any? {
    if (returnType.name == "com.google.common.collect.ImmutableList") {
        return runCatching {
            val of = returnType.getDeclaredMethod("of")
            of.isAccessible = true
            of.invoke(null)
        }.getOrNull()
    }
    return when {
        returnType.isAssignableFrom(ArrayList::class.java) -> arrayListOf<Any?>()
        Iterable::class.java.isAssignableFrom(returnType) -> emptyList<Any?>()
        else -> null
    }
}

fun hookSponsoredPoolListMethods(module: XposedModule, poolClass: Class<*>) {
    var hooked = 0
    poolClass.declaredMethods
        .filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 0 &&
                List::class.java.isAssignableFrom(method.returnType)
        }
        .forEach { method ->
            method.isAccessible = true
            module.hook(method).intercept { arrayListOf<Any?>() }
            hooked++
        }
    Logger.i(TAG, "Hooked $hooked feed pool list method(s) on ${poolClass.name}")
}

fun hookSponsoredPoolResultMethods(module: XposedModule, poolClass: Class<*>) {
    var hooked = 0
    poolClass.declaredMethods
        .filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                isSponsoredResultCarrier(method.returnType) &&
                (
                    method.parameterCount == 0 ||
                        (method.parameterCount == 1 && method.parameterTypes[0] == Boolean::class.javaPrimitiveType)
                    )
        }
        .forEach { method ->
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                buildSponsoredEmptyResult(method.returnType) ?: chain.proceed()
            }
            hooked++
        }
    Logger.i(TAG, "Hooked $hooked feed pool result method(s) on ${poolClass.name}")
}

fun isSponsoredResultCarrier(type: Class<*>): Boolean {
    val constructor = type.declaredConstructors.firstOrNull { it.parameterCount == 2 } ?: return false
    val reasonType = constructor.parameterTypes.getOrNull(1) ?: return false
    return reasonType.enumConstants?.any { it.toString() == "SPONSORED_GET_NEXT_RETURN_NULL" } == true
}

fun buildSponsoredEmptyResult(type: Class<*>): Any? {
    val constructor = type.declaredConstructors.firstOrNull { it.parameterCount == 2 } ?: return null
    val reasonType = constructor.parameterTypes.getOrNull(1) ?: return null
    val emptyReason = reasonType.enumConstants?.firstOrNull { it.toString() == "SPONSORED_GET_NEXT_RETURN_NULL" }
        ?: reasonType.enumConstants?.firstOrNull { it.toString() == "FAIL" }
        ?: return null
    constructor.isAccessible = true
    return constructor.newInstance(null, emptyReason)
}

fun buildImmutableListLike(sample: Any?, items: List<Any?>): Any? {
    if (sample == null) return null
    return runCatching {
        val immutableListClass = Class.forName(
            "com.google.common.collect.ImmutableList",
            false,
            sample.javaClass.classLoader
        )
        val copyOf = immutableListClass.getDeclaredMethod("copyOf", Iterable::class.java)
        copyOf.invoke(null, items)
    }.getOrNull()
}

fun rebuildFeedResult(result: Any, items: List<Any?>): Any? {
    val type = result.javaClass
    val fields = runCatching {
        type.declaredFields.onEach { it.isAccessible = true }
    }.getOrNull() ?: return null

    val listField = fields.firstOrNull { candidate ->
        !Modifier.isStatic(candidate.modifiers) &&
            Iterable::class.java.isAssignableFrom(candidate.type)
    } ?: return null

    val intArrayField = fields.firstOrNull { candidate ->
        !Modifier.isStatic(candidate.modifiers) && candidate.type == IntArray::class.java
    } ?: return null

    val intFields = fields.filter { candidate ->
        !Modifier.isStatic(candidate.modifiers) && candidate.type == Int::class.javaPrimitiveType
    }
    if (intFields.size < 3) return null

    val originalList = runCatching { listField.get(result) }.getOrNull()
    val rebuiltList = buildImmutableListLike(originalList, items) ?: return null
    val stats = runCatching { intArrayField.get(result) as? IntArray }.getOrNull()?.clone() ?: return null
    val ints = intFields.map { field -> runCatching { field.getInt(result) }.getOrNull() ?: return null }

    val constructor = type.declaredConstructors.firstOrNull { constructor ->
        constructor.parameterCount == 5 &&
            constructor.parameterTypes.getOrNull(0)?.name == "com.google.common.collect.ImmutableList" &&
            constructor.parameterTypes.getOrNull(1) == IntArray::class.java &&
            constructor.parameterTypes.drop(2).all { it == Int::class.javaPrimitiveType }
    } ?: return null

    constructor.isAccessible = true
    return runCatching {
        constructor.newInstance(rebuiltList, stats, ints[0], ints[1], ints[2])
    }.getOrNull()
}

fun extractFeedItemsFromResult(result: Any?): Iterable<*>? {
    if (result == null) return null
    if (result is Iterable<*>) return result

    return runCatching {
        val field = result.javaClass.declaredFields.firstOrNull { candidate ->
            Iterable::class.java.isAssignableFrom(candidate.type)
        } ?: return null
        field.isAccessible = true
        field.get(result) as? Iterable<*>
    }.getOrNull()
}

fun logFeedItems(source: String, items: Iterable<*>, feedItemInspector: FeedItemInspector) {
    var index = 0
    for (item in items) {
        Logger.i(TAG, "FeedItem $source[$index] ${feedItemInspector.describe(item)}")
        index++
    }
    Logger.i(TAG, "FeedItem $source count=$index")
}

fun hookListBuilderAppend(module: XposedModule, method: Method, inspector: AdStoryInspector) {
    module.hook(method).intercept { chain ->
        val story = chain.args.getOrNull(chain.args.size - 2)
        if (story != null && inspector.containsAdStory(story)) {
            val newArgs = chain.args.toTypedArray()
            newArgs[chain.args.size - 2] = null
            val res = chain.proceed(newArgs)
            processListAppendResult(chain.args.lastOrNull(), inspector)
            res
        } else {
            val res = chain.proceed()
            processListAppendResult(chain.args.lastOrNull(), inspector)
            res
        }
    }
}

fun processListAppendResult(listArg: Any?, inspector: AdStoryInspector) {
    val list = listArg as? MutableList<Any?>
    if (list != null) {
        val removed = filterAdItems(list, inspector)
        if (removed > 0) {
            Logger.i(TAG, "Removed $removed Reels ad story from builder append")
        }
    }
}

fun hookListResultFilter(module: XposedModule, method: Method, source: String, inspector: AdStoryInspector) {
    module.hook(method).intercept { chain ->
        val result = chain.proceed()
        val list = result as? MutableList<Any?> ?: return@intercept result
        val removed = filterAdItems(list, inspector)
        if (removed > 0) {
            Logger.i(TAG, "Filtered $removed Reels ad stories from $source")
        }
        result
    }
}

fun hookPluginPackFallback(module: XposedModule, method: Method, inspector: AdStoryInspector) {
    module.hook(method).intercept { chain ->
        val instance = chain.thisObject ?: return@intercept chain.proceed()
        if (isMarketplaceAdsPluginPack(instance)) {
            Logger.i(TAG, "Blocking MarketplaceAdsPluginPack build for Reels consistency")
            return@intercept emptyList<Any?>()
        }

        val result = chain.proceed()
        val list = result as? MutableList<Any?> ?: return@intercept result
        val removed = filterAdItems(list, inspector)
        if (removed > 0) {
            Logger.i(TAG, "Filtered $removed ad items from plugin pack build result")
        }
        result
    }
}

fun isMarketplaceAdsPluginPack(instance: Any): Boolean {
    val className = instance.javaClass.name
    return marketplaceAdsPackCache.getOrPut(className) {
        runCatching {
            instance.javaClass.declaredMethods
                .filter { m ->
                    m.parameterCount == 0 &&
                        m.returnType == String::class.java &&
                        !Modifier.isStatic(m.modifiers)
                }
                .any { m ->
                    m.isAccessible = true
                    val name = m.invoke(instance) as? String
                    name != null && name.contains("Ads", ignoreCase = true)
                }
        }.getOrDefault(false)
    }
}

fun filterAdItems(list: MutableList<Any?>, inspector: AdStoryInspector): Int {
    var removed = 0
    val iterator = list.iterator()
    while (iterator.hasNext()) {
        if (inspector.containsAdStory(iterator.next())) {
            iterator.remove()
            removed++
        }
    }
    return removed
}
