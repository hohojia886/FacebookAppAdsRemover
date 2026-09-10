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

internal val feedCsrMethodsHooked = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
internal val lateFeedMethodsHooked = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
internal val feedComponentMethodsHooked = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
internal val feedComponentCandidates = ConcurrentHashMap<String, Class<*>>()
internal val feedGuardResolvedComponentNames = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
internal val feedGuardResolvedWrapperNames = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
internal val visibleAdTraceInstalled = AtomicInteger(0)
internal val visibleAdViewsTraced = ConcurrentHashMap<Int, Boolean>()
internal val survivingFeedAdTraceCount = AtomicInteger(0)
internal val survivingFeedTypeContractsLogged = AtomicInteger(0)

internal fun survivingFeedTypeClassNames(): List<String> {
    return (feedComponentCandidates.keys + feedWrapperCandidates.keys + GRAPHQL_FEED_UNIT_EDGE_CLASS).toList()
}

// Debug-only: records which Litho component classes the wrapper renders besides
// the feed unit component, to spot ads rendering through other components.
internal val feedWrapperChildClassesLogged = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

internal fun logWrapperChildClass(owner: Any, wrapperChildField: Field) {
    val child = runCatching { wrapperChildField.get(owner) }.getOrNull() ?: return
    val key = "${owner.javaClass.name} -> ${child.javaClass.name}"
    if (feedWrapperChildClassesLogged.add(key)) {
        Log.i(TAG, "Feed wrapper child=$key")
    }
}

fun installFacebookFeedComponentGuard(classLoader: ClassLoader): Boolean {
    // The cached names may fail Class.forName at attach time (secondary dex not
    // yet configured) but resolve on a later attempt; retry on every call.
    registerCachedGuardClasses(classLoader, feedGuardCachedComponentNames, feedComponentCandidates)
    registerCachedGuardClasses(classLoader, feedGuardCachedWrapperNames, feedWrapperCandidates)
    val inspector = FeedItemInspector(emptyList())
    var resolvedTargets = 0
    var installed = 0
    val resolvedMethods = ArrayList<Method>()

    feedComponentCandidates.values.forEach { componentClass ->
        val edgeField = resolveFeedEdgeField(componentClass) ?: return@forEach
        feedWrapperCandidates.values.forEach { wrapperClass ->
            if (wrapperClass == componentClass) return@forEach
            val wrapperChildField = resolveWrapperChildField(wrapperClass, componentClass) ?: return@forEach
            val renderMethods = FEED_RENDER_PARAMETER_COUNTS.firstNotNullOfOrNull { parameterCount ->
                val layoutContextType =
                    resolveLithoLayoutContextType(componentClass, wrapperClass, parameterCount)
                        ?: return@firstNotNullOfOrNull null
                val methods = listOf(componentClass, wrapperClass).flatMap { type ->
                    lithoLayoutMethods(type, layoutContextType, parameterCount)
                }
                methods.ifEmpty { null }
            } ?: return@forEach

            resolvedTargets++
            resolvedMethods.addAll(renderMethods)
            feedGuardResolvedComponentNames.add(componentClass.name)
            feedGuardResolvedWrapperNames.add(wrapperClass.name)
            renderMethods.forEach { method ->
                val key = methodHookKey(method)
                if (!feedComponentMethodsHooked.add(key)) return@forEach

                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val owner = param.thisObject ?: return
                        val component = when {
                            componentClass.isInstance(owner) -> owner
                            wrapperClass.isInstance(owner) -> runCatching {
                                wrapperChildField.get(owner)
                            }.getOrNull()?.takeIf(componentClass::isInstance)
                            else -> null
                        }
                        if (component == null) {
                            if (wrapperClass.isInstance(owner) && BuildConfig.DEBUG) {
                                logWrapperChildClass(owner, wrapperChildField)
                            }
                            return
                        }
                        val edge = runCatching { edgeField.get(component) }.getOrNull()
                        if (edge == null) {
                            logHookHitThrottled(
                                "sponsoredFeedComponentNoEdge",
                                method,
                                "component=${componentClass.name}"
                            )
                            return
                        }
                        if (!inspector.isDefinitelySponsoredFeedItem(edge)) {
                            logHookHitThrottled(
                                "sponsoredFeedComponentPass",
                                method,
                                inspector.describe(edge)
                            )
                            return
                        }

                        param.result = null
                        logHookHitThrottled(
                            "sponsoredFeedComponentBlock",
                            method,
                            inspector.describe(edge)
                        )
                    }
                })
                installed++
            }
        }
    }
    if (installed > 0) {
        Log.i(
            TAG,
            "Installed sponsored feed component guards=" +
                resolvedMethods.joinToString { "${it.declaringClass.name}.${it.name}" }
        )
    }
    return installed > 0
}

// Litho components carry their spec name ("NewsFeedFeedUnitComponent",
// "LoggingComponent") in a final String field of their generated base class,
// filled by a String constructor. Reflection cannot read the constant without
// running the constructor, so this instantiates the class through its no-arg
// constructor and reads the name. The generated component constructors are
// trivial (super(name) plus field defaults), and any class whose construction
// fails is simply skipped.
internal val lithoComponentNameFields = ConcurrentHashMap<String, Optional<Field>>()

fun lithoComponentNameOf(type: Class<*>): String? {
    return runCatching {
        if (type.isInterface || type.isPrimitive || type.isArray || type.isAnnotation) return null
        var base = type.superclass
        var hops = 0
        while (base != null && base != Any::class.java && hops < 4) {
            val nameField = lithoComponentNameFields.computeIfAbsent(base.name) {
                Optional.ofNullable(
                    base.declaredFields.firstOrNull { declared ->
                        !Modifier.isStatic(declared.modifiers) &&
                            Modifier.isFinal(declared.modifiers) &&
                            declared.type == String::class.java
                    }?.takeIf {
                        base.declaredConstructors.any { constructor ->
                            constructor.parameterCount == 1 &&
                                constructor.parameterTypes[0] == String::class.java
                        }
                    }?.apply { isAccessible = true }
                )
            }.orElse(null)
            if (nameField != null) {
                val constructor = type.declaredConstructors.firstOrNull { it.parameterCount == 0 }
                    ?: return null
                constructor.isAccessible = true
                return nameField.get(constructor.newInstance()) as? String
            }
            base = base.superclass
            hops++
        }
        null
    }.getOrNull()
}

fun registerFeedGuardCandidate(type: Class<*>, componentName: String): Boolean {
    return when (componentName) {
        FEED_UNIT_COMPONENT_NAME -> {
            feedComponentCandidates.putIfAbsent(type.name, type) == null
            true
        }
        FEED_WRAPPER_COMPONENT_NAME -> {
            feedWrapperCandidates.putIfAbsent(type.name, type) == null
            true
        }
        else -> false
    }
}

// Backstop for the class-load notifier: once the secondary dex is scanned,
// DexKit finds the component classes by their stable name strings directly.
internal fun registerLithoComponentClasses(
    bridge: DexKitBridge,
    classLoader: ClassLoader,
    componentName: String,
    registry: ConcurrentHashMap<String, Class<*>>
) {
    val classes = runCatching {
        bridge.findClass {
            matcher {
                usingStrings(listOf(componentName), StringMatchType.Equals)
            }
        }.mapNotNull { classData ->
            runCatching { classData.getInstance(classLoader) }.getOrNull()
        }
    }.getOrDefault(emptyList())
    classes.forEach { discovered -> registry.putIfAbsent(discovered.name, discovered) }
    Log.i(
        TAG,
        "Discovered Litho component name=$componentName classes=" +
            classes.joinToString { it.name }
    )
}

fun discoverFeedComponentGuardCandidates(bridge: DexKitBridge, classLoader: ClassLoader) {
    registerLithoComponentClasses(bridge, classLoader, FEED_UNIT_COMPONENT_NAME, feedComponentCandidates)
    registerLithoComponentClasses(bridge, classLoader, FEED_WRAPPER_COMPONENT_NAME, feedWrapperCandidates)
}

internal fun feedGuardCacheModuleKey(): String {
    return "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
}

internal fun registerCachedGuardClasses(
    classLoader: ClassLoader,
    classNames: List<String>,
    registry: ConcurrentHashMap<String, Class<*>>
): Int {
    var registered = 0
    classNames.forEach { className ->
        runCatching { Class.forName(className, false, classLoader) }.getOrNull()?.let { loaded ->
            registry.putIfAbsent(loaded.name, loaded)
            registered++
        }
    }
    return registered
}

// Parsed once from the cache file; every guard install attempt retries these
// names because the secondary dex is often not configurable yet at
// Application.attach, when the cache is first read.
@Volatile
internal var feedGuardCachedComponentNames: List<String> = emptyList()

@Volatile
internal var feedGuardCachedWrapperNames: List<String> = emptyList()

fun loadCachedFeedGuardCandidates(
    context: Context,
    classLoader: ClassLoader,
    hostVersionName: String
): Int {
    if (hostVersionName.isBlank()) return 0
    return runCatching {
        val file = File(context.cacheDir, FEED_GUARD_CACHE_FILE)
        if (!file.exists()) {
            Log.i(TAG, "Feed guard cache missing; re-discovering")
            return 0
        }
        val properties = Properties()
        file.inputStream().use { properties.load(it) }
        if (hostVersionName != properties.getProperty("version")) {
            Log.i(TAG, "Feed guard cache stale for version=$hostVersionName; re-discovering")
            return 0
        }
        if (feedGuardCacheModuleKey() != properties.getProperty("moduleVersion")) {
            Log.i(TAG, "Feed guard cache stale for moduleVersion=${feedGuardCacheModuleKey()}; re-discovering")
            return 0
        }
        feedGuardCachedComponentNames = properties.getProperty("components").orEmpty()
            .split(',').filter { it.isNotBlank() }
        feedGuardCachedWrapperNames = properties.getProperty("wrappers").orEmpty()
            .split(',').filter { it.isNotBlank() }
        val registered = registerCachedGuardClasses(classLoader, feedGuardCachedComponentNames, feedComponentCandidates) +
            registerCachedGuardClasses(classLoader, feedGuardCachedWrapperNames, feedWrapperCandidates)
        lastSavedFeedGuardCachePayload = "$hostVersionName|${feedGuardCacheModuleKey()}|${feedGuardCachedComponentNames.joinToString(",")}|${feedGuardCachedWrapperNames.joinToString(",")}"
        Log.i(
            TAG,
            "Loaded feed guard cache components=$feedGuardCachedComponentNames " +
                "wrappers=$feedGuardCachedWrapperNames registeredNow=$registered"
        )
        registered
    }.onFailure {
        Log.w(TAG, "Failed to load feed guard cache", it)
    }.getOrDefault(0)
}

@Volatile
private var lastSavedFeedGuardCachePayload: String? = null

// Opt 3.1 & 3.2: Asynchronous background cache serialization and redundant write suppression
fun saveFeedGuardCandidateCache(context: Context, hostVersionName: String) {
    if (hostVersionName.isBlank()) return
    val components = feedGuardResolvedComponentNames.toList()
    val wrappers = feedGuardResolvedWrapperNames.toList()
    if (components.isEmpty() || wrappers.isEmpty()) return

    val payload = "$hostVersionName|${feedGuardCacheModuleKey()}|${components.joinToString(",")}|${wrappers.joinToString(",")}"
    if (payload == lastSavedFeedGuardCachePayload) {
        if (BuildConfig.DEBUG) Log.i(TAG, "Feed guard cache payload unchanged; suppressing write")
        return
    }

    cacheIoExecutor.execute {
        runCatching {
            val file = File(context.cacheDir, FEED_GUARD_CACHE_FILE)
            val properties = Properties()
            properties.setProperty("version", hostVersionName)
            properties.setProperty("moduleVersion", feedGuardCacheModuleKey())
            properties.setProperty("components", components.joinToString(","))
            properties.setProperty("wrappers", wrappers.joinToString(","))
            file.outputStream().use { properties.store(it, null) }
            lastSavedFeedGuardCachePayload = payload
            Log.i(TAG, "Saved feed guard cache components=$components wrappers=$wrappers")
        }.onFailure {
            Log.w(TAG, "Failed to save feed guard cache", it)
        }
    }
}

// Litho layout entry points are obfuscated differently in every build (A1H on 571,
// both A1F and A1H on 576), so they are matched by shape instead of by name.
// Static builder factories share the same shape and must stay unhooked.
internal fun lithoLayoutMethods(
    type: Class<*>,
    contextType: Class<*>,
    parameterCount: Int
): List<Method> {
    return type.declaredMethods.filter { method ->
        !Modifier.isStatic(method.modifiers) &&
            method.parameterCount == parameterCount &&
            !method.returnType.isPrimitive &&
            method.parameterTypes[0] == contextType
    }.onEach { it.isAccessible = true }
}

internal fun logFacebookSurvivingFeedTypeContracts(classLoader: ClassLoader) {
    if (!BuildConfig.DEBUG || survivingFeedTypeContractsLogged.getAndIncrement() != 0) return

    survivingFeedTypeClassNames().forEach { className ->
        logSurvivingFeedTypeContract(classLoader, className)
    }
}

internal fun logSurvivingFeedTypeContract(classLoader: ClassLoader, className: String) {
    val type = runCatching {
        Class.forName(className, false, classLoader)
    }.getOrElse {
        Log.w(TAG, "SurvivingFeedType class unavailable=$className")
        return
    }
    Log.i(
        TAG,
        "SurvivingFeedType class=${type.name} super=${type.superclass?.name} " +
            "interfaces=${type.interfaces.joinToString { it.name }}"
    )
    type.declaredFields.forEach { field ->
        Log.i(
            TAG,
            "SurvivingFeedType field=${type.name}.${field.name}:${field.type.name} " +
                "static=${Modifier.isStatic(field.modifiers)}"
        )
    }
    type.declaredConstructors.forEach { constructor ->
        Log.i(
            TAG,
            "SurvivingFeedType ctor=${type.name}(" +
                constructor.parameterTypes.joinToString { it.name } + ")"
        )
    }
    type.declaredMethods.forEach { method ->
        Log.i(
            TAG,
            "SurvivingFeedType method=${type.name}.${method.name}(" +
                method.parameterTypes.joinToString { it.name } + "):${method.returnType.name} " +
                "static=${Modifier.isStatic(method.modifiers)}"
        )
    }
}

fun installFacebookVisibleAdTrace(classLoader: ClassLoader) {
    if (!BuildConfig.DEBUG || visibleAdTraceInstalled.getAndIncrement() != 0) return

    val method = View::class.java.getDeclaredMethod(
        "setContentDescription",
        CharSequence::class.java
    ).apply { isAccessible = true }
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val marker = param.args.getOrNull(0)?.toString().orEmpty()
            if (
                !marker.equals("Hide ad", ignoreCase = true) &&
                !marker.contains("Ad\u2022Shared with", ignoreCase = true)
            ) {
                return
            }
            val view = param.thisObject as? View ?: return
            view.postDelayed(
                { traceVisibleFacebookFeedAd(view, marker, classLoader, 0) },
                150L
            )
        }
    })
    Log.i(TAG, "Installed visible-ad holder tracer")
}

internal fun traceVisibleFacebookFeedAd(
    markerView: View,
    marker: String,
    classLoader: ClassLoader,
    attempt: Int
) {
    val recycler = findRecyclerViewAncestor(markerView)
    val lithoView = findViewAncestor(markerView, "com.facebook.litho.LithoView")
    if (recycler == null && lithoView == null) {
        if (attempt < 5) {
            markerView.postDelayed(
                {
                    traceVisibleFacebookFeedAd(
                        markerView,
                        marker,
                        classLoader,
                        attempt + 1
                    )
                },
                250L
            )
        }
        return
    }

    val traceKey = System.identityHashCode(markerView)
    if (visibleAdViewsTraced.putIfAbsent(traceKey, true) != null) return

    val holder = invokeMethodByName(recycler, "findContainingViewHolder", markerView)
    val adapter = invokeMethodByName(recycler, "getAdapter")
    val bindingPosition = invokeMethodByName(holder, "getBindingAdapterPosition")
    val absolutePosition = invokeMethodByName(holder, "getAbsoluteAdapterPosition")
    Log.i(
        TAG,
        "VisibleAdTrace marker=$marker recycler=${recycler?.javaClass?.name ?: "null"} " +
            "lithoView=${lithoView?.javaClass?.name ?: "null"} " +
            "holder=${holder?.javaClass?.name ?: "null"} adapter=${adapter?.javaClass?.name ?: "null"} " +
            "bindingPosition=$bindingPosition absolutePosition=$absolutePosition"
    )

    val inspector = FeedItemInspector(emptyList())
    lithoView?.let { traceVisibleAdObjectGraph(it, "lithoView", inspector) }
    holder?.let { traceVisibleAdObjectGraph(it, "holder", inspector) }
    adapter?.let { traceVisibleAdObjectGraph(it, "adapter", inspector) }
}

internal fun findRecyclerViewAncestor(view: View): Any? {
    var current: Any? = view
    repeat(80) {
        val value = current ?: return null
        if (
            allMethodsInHierarchy(value.javaClass).any { method ->
                method.name == "findContainingViewHolder" && method.parameterCount == 1
            }
        ) {
            return value
        }
        current = runCatching {
            (value as? View)?.parent
        }.getOrNull()
    }
    return null
}

internal fun traceVisibleAdObjectGraph(
    root: Any,
    rootPath: String,
    inspector: FeedItemInspector
) {
    val queue = ArrayDeque<VisibleAdGraphNode>()
    val seen = IdentityHashMap<Any, Boolean>()
    queue.add(VisibleAdGraphNode(root, rootPath, 0))
    var visited = 0
    var matches = 0

    while (queue.isNotEmpty() && visited < 2_000 && matches < 80) {
        val node = queue.removeFirst()
        val value = node.value
        if (seen.put(value, true) != null) continue
        visited++

        val type = value.javaClass
        val typeName = type.name
        if (isTraceableFeedObject(type)) {
            matches++
            Log.i(
                TAG,
                "VisibleAdTrace feedObject path=${node.path} class=$typeName " +
                    inspector.describe(value)
            )
        }

        if (value is CharSequence) {
            val text = value.toString()
            if (isVisibleAdTraceString(text)) {
                matches++
                Log.i(TAG, "VisibleAdTrace string path=${node.path} value=${text.take(300)}")
            }
            continue
        }
        if (node.depth >= 7 || shouldSkipVisibleAdTraceType(type)) continue

        if (value is Iterable<*>) {
            var index = 0
            for (item in value) {
                if (item != null && index < 300) {
                    queue.add(VisibleAdGraphNode(item, "${node.path}[$index]", node.depth + 1))
                }
                index++
                if (index >= 300) break
            }
        } else if (type.isArray && !type.componentType.isPrimitive) {
            val length = java.lang.reflect.Array.getLength(value).coerceAtMost(300)
            for (index in 0 until length) {
                java.lang.reflect.Array.get(value, index)?.let { item ->
                    queue.add(VisibleAdGraphNode(item, "${node.path}[$index]", node.depth + 1))
                }
            }
        }

        allFieldsInHierarchy(type).forEach { field ->
            if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) return@forEach
            field.isAccessible = true
            val child = runCatching { field.get(value) }.getOrNull() ?: return@forEach
            queue.add(
                VisibleAdGraphNode(
                    child,
                    "${node.path}.${type.simpleName}.${field.name}",
                    node.depth + 1
                )
            )
        }
    }
    Log.i(TAG, "VisibleAdTrace graph root=$rootPath visited=$visited matches=$matches")
}

internal fun isTraceableFeedObject(type: Class<*>): Boolean {
    return type.name == GRAPHQL_FEED_UNIT_EDGE_CLASS ||
        (type.name.contains("GraphQL") && type.name.contains("Feed"))
}

internal fun shouldSkipVisibleAdTraceType(type: Class<*>): Boolean {
    val name = type.name
    return type.isEnum ||
        Number::class.java.isAssignableFrom(type) ||
        type == java.lang.Boolean::class.java ||
        name.startsWith("java.lang.Class") ||
        name.startsWith("java.lang.reflect.") ||
        name.startsWith("android.graphics.") ||
        name.startsWith("android.content.res.")
}

// Opt 1.2: Direct CharSequence matching without intermediate String lowercasing allocations
internal fun isVisibleAdTraceString(value: String): Boolean {
    if (value.isBlank()) return false
    return value.contains("xtreme-pc", ignoreCase = true) ||
        value.contains("book now", ignoreCase = true) ||
        value.contains("hide ad", ignoreCase = true) ||
        value.contains("samurai", ignoreCase = true) ||
        value.contains("sponsored", ignoreCase = true) ||
        value.contains("ad choices", ignoreCase = true) ||
        value.contains("adchoices", ignoreCase = true) ||
        value.contains("apply now", ignoreCase = true) ||
        value.contains("send message", ignoreCase = true) ||
        value.contains("learn more", ignoreCase = true) ||
        value.contains("shop now", ignoreCase = true) ||
        value.contains("contact us", ignoreCase = true) ||
        value.contains("get quote", ignoreCase = true) ||
        value.contains("call now", ignoreCase = true) ||
        value.contains("sign up", ignoreCase = true)
}

internal val wrapperListFieldCache = ConcurrentHashMap<Class<*>, Optional<Field>>()

internal fun hookListResultFilter(method: Method, source: String, inspector: AdStoryInspector) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val result = param.result as? MutableList<Any?> ?: return
            // Opt 1.1: Fast-path return on empty result list
            if (result.isEmpty()) return
            val removed = filterAdItems(result, inspector)
            if (removed > 0) {
                Log.i(TAG, "Removed $removed ad item(s) from $source")
            }
        }
    })
}

private data class FilterSponsoredResult(val keptItems: List<Any?>?, val removedCount: Int)

// Opt 1.1: Deferred list allocation helper to achieve zero-allocation filtering when no ads exist
private fun filterSponsoredFeedItems(
    items: Iterable<*>,
    feedItemInspector: FeedItemInspector
): FilterSponsoredResult {
    if (items is Collection<*> && items.isEmpty()) {
        return FilterSponsoredResult(null, 0)
    }
    var keptItems: ArrayList<Any?>? = null
    var removed = 0
    var index = 0

    for (item in items) {
        if (feedItemInspector.isDefinitelySponsoredFeedItem(item)) {
            if (keptItems == null) {
                // Instantiate ArrayList only on encountering the first sponsored ad item
                keptItems = ArrayList<Any?>().apply {
                    if (items is List<*>) {
                        for (i in 0 until index) {
                            add(items[i])
                        }
                    } else {
                        var count = 0
                        for (prev in items) {
                            if (count >= index) break
                            add(prev)
                            count++
                        }
                    }
                }
            }
            removed++
        } else {
            keptItems?.add(item)
        }
        index++
    }
    return FilterSponsoredResult(keptItems, removed)
}

internal fun hookFeedCsrFilterInput(
    hook: FeedCsrFilterHook,
    feedItemInspector: FeedItemInspector
): Boolean {
    if (!feedCsrMethodsHooked.add(methodHookKey(hook.method))) {
        return false
    }
    XposedBridge.hookMethod(hook.method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val filterName = hook.method.declaringClass.name
            val originalList = param.args.getOrNull(hook.listArgIndex) as? Iterable<*> ?: return
            logFeedItems("$filterName IN", originalList, feedItemInspector)
            val (keptItems, removed) = filterSponsoredFeedItems(originalList, feedItemInspector)

            if (removed <= 0 || keptItems == null) return

            val rebuilt = buildImmutableListLike(param.args.getOrNull(hook.listArgIndex), keptItems) ?: return
            param.args[hook.listArgIndex] = rebuilt
            Log.i(TAG, "Removed $removed sponsored feed item(s) before ${hook.method.declaringClass.name}.${hook.method.name}")
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            val filterName = hook.method.declaringClass.name
            val resultItems = extractFeedItemsFromResult(param.result)
            if (resultItems != null) {
                logFeedItems("$filterName OUT", resultItems, feedItemInspector)
                val (keptItems, removed) = filterSponsoredFeedItems(resultItems, feedItemInspector)
                if (removed > 0 && keptItems != null && replaceFeedItemsInResult(param, keptItems)) {
                    Log.i(TAG, "Removed $removed sponsored feed item(s) from result of ${hook.method.declaringClass.name}.${hook.method.name}")
                }
            }
        }
    })
    return true
}

internal fun hookLateFeedListSanitizer(
    hook: FeedListSanitizerHook,
    feedItemInspector: FeedItemInspector
): Boolean {
    if (!lateFeedMethodsHooked.add(methodHookKey(hook.method))) {
        return false
    }
    XposedBridge.hookMethod(hook.method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val originalList = param.args.getOrNull(hook.listArgIndex) as? Iterable<*> ?: return
            val (keptItems, removed) = filterSponsoredFeedItems(originalList, feedItemInspector)

            if (removed <= 0 || keptItems == null) return

            val rebuilt = buildImmutableListLike(param.args.getOrNull(hook.listArgIndex), keptItems) ?: return
            param.args[hook.listArgIndex] = rebuilt
            Log.i(
                TAG,
                "Late-stage removed $removed sponsored feed item(s) before ${hook.method.declaringClass.name}.${hook.method.name}"
            )
        }
    })
    return true
}

internal fun shouldScheduleFeedRowSweep(parent: ViewGroup?, child: View?): Boolean {
    if (parent == null || child !is ViewGroup) return false
    return parent.javaClass.name.contains("RecyclerView")
}

internal fun scheduleFeedRowSweep(view: View?, reason: String) {
    val subtree = view ?: return
    longArrayOf(60L, 500L, 1_500L, 3_000L).forEach { delayMs ->
        subtree.postDelayed({
            sweepGameAdSurface(subtree, reason)
        }, delayMs)
    }
}

internal fun hideLikelyExplicitFeedAdCardContainer(view: View, reason: String): Boolean {
    val target = resolveLikelyExplicitFeedAdCardTarget(view) ?: return false
    return hideResolvedAdSurfaceTarget(
        target = target,
        source = view,
        root = view.rootView,
        reason = "$reason explicit feed card",
        forceCollapseHeight = true
    )
}

internal fun traceSurvivingFeedAdSourceOnce(source: View, target: View, reason: String) {
    if (!BuildConfig.DEBUG || survivingFeedAdTraceCount.getAndIncrement() != 0) return

    val markerTexts = collectViewMarkerTexts(source)
        .plus(collectViewMarkerTexts(target))
        .distinct()
        .joinToString(" | ") { it.take(240) }
    Log.i(
        TAG,
        "SurvivingFeedAdTrace reason=$reason source=${source.javaClass.name} " +
            "target=${target.javaClass.name} markers=$markerTexts"
    )
    Throwable().stackTrace
        .asSequence()
        .filterNot { frame ->
            frame.className.startsWith("java.") ||
                frame.className.startsWith("kotlin.") ||
                frame.className.startsWith("de.robv.android.xposed.") ||
                frame.className.startsWith("org.lsposed.")
        }
        .take(48)
        .forEachIndexed { index, frame ->
            Log.i(TAG, "SurvivingFeedAdTrace stack[$index]=$frame")
        }

    val classLoader = target.javaClass.classLoader ?: source.javaClass.classLoader ?: return
    survivingFeedTypeClassNames().forEach { className ->
        logSurvivingFeedTypeContract(classLoader, className)
    }
    traceVisibleAdObjectGraph(
        target,
        "survivingCard",
        FeedItemInspector(emptyList())
    )
}

internal fun hideLikelyFeedReelCtaAdContainer(view: View, reason: String): Boolean {
    val target = resolveLikelyFeedReelCtaAdContainerTarget(view) ?: return false
    var hidden = false
    if (target.visibility != View.GONE) {
        target.visibility = View.GONE
        hidden = true
    }
    target.minimumHeight = 0
    target.layoutParams?.let { params ->
        params.height = 0
        target.layoutParams = params
        hidden = true
    }
    target.requestLayout()

    if (hidden) {
        Log.i(
            TAG,
            "Hid ad surface via $reason reelCtaTarget=${target.javaClass.name} bounds=${target.left},${target.top},${target.right},${target.bottom}"
        )
    }
    return hidden
}

internal fun shouldUseFeedMarkerCardTarget(view: View): Boolean {
    return isPotentialFeedAdMarkerView(view) || (view is TextView && isFeedAdMarkerText(view.text))
}

internal fun shouldUseExplicitFeedMarkerCardTarget(view: View): Boolean {
    return isPotentialExplicitFeedAdMarkerView(view) || (view is TextView && isExplicitFeedAdMarkerText(view.text))
}

internal fun isSafeFeedMarkerCardCandidate(view: View, rootWidth: Int, rootHeight: Int): Boolean {
    val width = view.width
    val height = view.height
    if (width < (rootWidth * 0.82f).toInt()) return false
    if (height < maxOf(360, (rootHeight * 0.18f).toInt())) return false
    if (height > (rootHeight * 0.82f).toInt()) return false

    val location = IntArray(2)
    val topOnScreen = runCatching {
        view.getLocationOnScreen(location)
        location[1]
    }.getOrDefault(view.top)
    val bottomOnScreen = topOnScreen + height

    if (topOnScreen < (rootHeight * 0.04f).toInt()) return false
    if (bottomOnScreen > (rootHeight * 0.96f).toInt()) return false

    return true
}

internal fun isLikelyExplicitFeedAdCardContainer(view: View): Boolean {
    val root = view.rootView ?: return false
    val rootWidth = root.width.takeIf { it > 0 } ?: return false
    val rootHeight = root.height.takeIf { it > 0 } ?: return false
    return isLikelyExplicitFeedAdCardContainer(view, rootWidth, rootHeight)
}

internal fun isLikelyExplicitFeedAdCardContainer(view: View, rootWidth: Int, rootHeight: Int): Boolean {
    if (view !is ViewGroup) return false

    val width = view.width
    val height = view.height
    if (width < (rootWidth * 0.82f).toInt()) return false
    if (height < maxOf(420, (rootHeight * 0.18f).toInt())) return false
    if (height > (rootHeight * 0.96f).toInt()) return false

    val location = IntArray(2)
    val topOnScreen = runCatching {
        view.getLocationOnScreen(location)
        location[1]
    }.getOrDefault(view.top)
    val bottomOnScreen = topOnScreen + height

    if (topOnScreen < (rootHeight * 0.04f).toInt()) return false
    if (bottomOnScreen > (rootHeight * 0.98f).toInt()) return false

    val signals = collectExplicitFeedAdCardSignals(view)
    return signals.hasHideAd &&
        (signals.hasAdLabel || signals.hasSharedLink || signals.hasStrongCta)
}

internal fun collectExplicitFeedAdCardSignals(root: View): ExplicitFeedAdCardSignals {
    val queue = java.util.ArrayDeque<View>()
    queue.add(root)

    var visited = 0
    var hasHideAd = false
    var hasAdLabel = false
    var hasSharedLink = false
    var hasStrongCta = false

    while (queue.isNotEmpty() && visited < 192 && !(hasHideAd && (hasAdLabel || hasSharedLink || hasStrongCta))) {
        val view = queue.removeFirst()
        visited++

        for (marker in collectViewMarkerTexts(view)) {
            val normalized = marker.lowercase()
            if (!hasHideAd && normalized.contains("hide ad")) hasHideAd = true
            if (!hasAdLabel && isExplicitFeedAdMarkerText(normalized)) hasAdLabel = true
            if (!hasSharedLink && normalized.contains("shared link:")) hasSharedLink = true
            if (!hasStrongCta && isExplicitFeedAdCtaText(normalized)) hasStrongCta = true
        }

        val group = view as? ViewGroup ?: continue
        for (index in 0 until group.childCount) {
            queue.addLast(group.getChildAt(index))
        }
    }

    return ExplicitFeedAdCardSignals(
        hasHideAd = hasHideAd,
        hasAdLabel = hasAdLabel,
        hasSharedLink = hasSharedLink,
        hasStrongCta = hasStrongCta
    )
}

internal fun isLikelyFeedReelCtaAdContainer(view: View, rootWidth: Int, rootHeight: Int): Boolean {
    val width = view.width
    val height = view.height
    if (width < (rootWidth * 0.82f).toInt()) return false
    if (height < (rootHeight * 0.45f).toInt() || height > (rootHeight * 0.92f).toInt()) return false

    val location = IntArray(2)
    val topOnScreen = runCatching {
        view.getLocationOnScreen(location)
        location[1]
    }.getOrDefault(view.top)
    if (topOnScreen < (rootHeight * 0.08f).toInt()) return false

    val signals = collectFeedReelCtaAdSignals(view)
    return signals.hasSharedLink &&
        signals.hasSendMessageCta &&
        (signals.hasReelSurface || signals.hasLeadGenPrompt)
}

internal fun collectFeedReelCtaAdSignals(root: View): FeedReelCtaAdSignals {
    val queue = java.util.ArrayDeque<View>()
    queue.add(root)

    var visited = 0
    var hasSharedLink = false
    var hasSendMessageCta = false
    var hasReelSurface = false
    var hasLeadGenPrompt = false

    while (queue.isNotEmpty() && visited < 128 && !(hasSharedLink && hasSendMessageCta && (hasReelSurface || hasLeadGenPrompt))) {
        val view = queue.removeFirst()
        visited++

        val className = view.javaClass.name.lowercase()
        val contentDescription = view.contentDescription?.toString().orEmpty().lowercase()
        val text = (view as? TextView)?.text?.toString().orEmpty().lowercase()
        val marker = "$className $contentDescription $text"

        if (!hasSharedLink && marker.contains("shared link:")) hasSharedLink = true
        if (!hasSendMessageCta && marker.contains("send message")) hasSendMessageCta = true
        if (!hasLeadGenPrompt &&
            (
                marker.contains("your business") ||
                    marker.contains("your ad")
                )
        ) {
            hasLeadGenPrompt = true
        }
        if (!hasReelSurface &&
            (
                marker.contains("reel") ||
                    className.contains("surfaceview") ||
                    className.contains("textureview") ||
                    className.contains("videoview")
                )
        ) {
            hasReelSurface = true
        }

        val group = view as? ViewGroup ?: continue
        for (index in 0 until group.childCount) {
            queue.addLast(group.getChildAt(index))
        }
    }

    return FeedReelCtaAdSignals(
        hasSharedLink = hasSharedLink,
        hasSendMessageCta = hasSendMessageCta,
        hasReelSurface = hasReelSurface,
        hasLeadGenPrompt = hasLeadGenPrompt
    )
}

internal fun isPotentialFeedAdMarkerView(view: View?): Boolean {
    if (view == null) return false
    return collectViewMarkerTexts(view).any(::isFeedAdMarkerText)
}

internal fun isPotentialExplicitFeedAdMarkerView(view: View?): Boolean {
    if (view == null) return false
    return collectViewMarkerTexts(view).any(::isExplicitFeedAdMarkerText)
}

internal fun isPotentialFeedReelCtaAdMarkerView(view: View?): Boolean {
    if (view == null) return false
    return collectViewMarkerTexts(view).any(::isFeedReelCtaAdMarkerText)
}

internal fun isAnyAdMarkerText(value: CharSequence?): Boolean {
    return isGameAdMarkerText(value) || isFeedAdMarkerText(value)
}

internal fun isFeedAdMarkerText(value: CharSequence?): Boolean {
    if (value.isNullOrBlank()) return false
    return FEED_SURFACE_AD_MARKER_TOKENS.any { token -> value.contains(token, ignoreCase = true) }
}

internal fun isExplicitFeedAdMarkerText(value: CharSequence?): Boolean {
    if (value.isNullOrBlank()) return false
    return EXPLICIT_FEED_CARD_AD_MARKER_TOKENS.any { token -> value.contains(token, ignoreCase = true) }
}

internal fun isExplicitFeedAdCtaText(value: CharSequence?): Boolean {
    if (value.isNullOrBlank()) return false
    return EXPLICIT_FEED_AD_CTA_TOKENS.any { token -> value.contains(token, ignoreCase = true) }
}

internal fun isFeedReelCtaAdMarkerText(value: CharSequence?): Boolean {
    if (value.isNullOrBlank()) return false
    return FEED_REEL_CTA_AD_MARKER_TOKENS.any { token -> value.contains(token, ignoreCase = true) }
}

internal fun filterAdItems(list: MutableList<Any?>, inspector: AdStoryInspector): Int {
    if (list.isEmpty()) return 0
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

internal fun buildImmutableListLike(
    sample: Any?,
    items: List<Any?>,
    loaderHint: ClassLoader? = null
): Any? {
    if (sample == null) return null
    // The sample's own classloader can be the bootstrap one (e.g. a
    // java.util.Collections singleton list), which cannot see the host's
    // Guava — hence the hint, plus a fallback on the first element.
    val loaders = sequenceOf(
        loaderHint,
        sample.javaClass.classLoader,
        items.firstOrNull { it != null }?.javaClass?.classLoader
    ).filterNotNull().distinct()
    for (loader in loaders) {
        val rebuilt = runCatching {
            val immutableListClass = Class.forName(
                "com.google.common.collect.ImmutableList",
                false,
                loader
            )
            val copyOf = immutableListClass.getDeclaredMethod("copyOf", Iterable::class.java)
            copyOf.invoke(null, items)
        }.getOrNull()
        if (rebuilt != null) return rebuilt
    }
    return null
}

internal fun replaceFeedItemsInResult(param: XC_MethodHook.MethodHookParam, items: List<Any?>): Boolean {
    val result = param.result ?: return false
    val rebuiltResult = rebuildFeedResult(result, items) ?: return false
    param.result = rebuiltResult
    return true
}

internal fun rebuildFeedResult(result: Any, items: List<Any?>): Any? {
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

internal fun extractFeedItemsFromResult(result: Any?): Iterable<*>? {
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

// Opt 1.3: Bypass item iteration and heavy describe() string formatting in production release builds
internal fun logFeedItems(source: String, items: Iterable<*>, feedItemInspector: FeedItemInspector) {
    if (!BuildConfig.DEBUG) return
    var index = 0
    for (item in items) {
        Log.i(TAG, "FeedItem $source[$index] ${feedItemInspector.describe(item)}")
        index++
    }
    Log.i(TAG, "FeedItem $source count=$index")
}

internal fun installFeedHooksPipeline(
    classLoader: ClassLoader,
    bridge: DexKitBridge,
    hooks: ResolvedHooks,
    feedItemInspector: FeedItemInspector
): Boolean {
    var installedAny = false
    runCatching {
        installFacebookVisibleAdTrace(classLoader)
        discoverFeedComponentGuardCandidates(bridge, classLoader)
        if (installFacebookFeedComponentGuard(classLoader)) {
            installedAny = true
        }
    }.onFailure { Log.e(TAG, "Failed feed component guard / visible ad trace", it) }

    if (ENABLE_FEED_CSR_FILTER_HOOKS) {
        hooks.feedCsrFilterHooks.forEach { hook ->
            runCatching {
                hookFeedCsrFilterInput(hook, feedItemInspector)
                installedAny = true
            }.onFailure {
                Log.e(TAG, "Failed to hook feed CSR filter ${hook.method.declaringClass.name}.${hook.method.name}", it)
            }
        }
    } else {
        Log.i(TAG, "Skipped feed CSR filter hooks to isolate feed Reels carousel loading")
    }

    if (ENABLE_LATE_FEED_LIST_HOOKS) {
        hooks.lateFeedListHooks.forEach { hook ->
            runCatching {
                hookLateFeedListSanitizer(hook, feedItemInspector)
                installedAny = true
            }.onFailure {
                Log.e(TAG, "Failed to hook late feed list ${hook.method.declaringClass.name}.${hook.method.name}", it)
            }
        }
    } else {
        Log.i(TAG, "Skipped late feed list hooks to isolate feed Reels carousel loading")
    }

    if (ENABLE_FEED_SPONSORED_POOL_HOOKS) {
        hooks.sponsoredPoolAddMethod?.let {
            runCatching { hookSponsoredPoolAdd(it); installedAny = true }
        }
        hooks.sponsoredStoryNextMethod?.let {
            runCatching { hookSponsoredStoryNext(it); installedAny = true }
        }
        hooks.sponsoredPoolClass?.let {
            runCatching {
                hookSponsoredPoolListMethods(it)
                hookSponsoredPoolResultMethods(it)
                installedAny = true
            }
        }
        hooks.sponsoredStoryManagerClass?.let {
            runCatching {
                hookSponsoredStoryListMethods(it)
                installedAny = true
            }
        }
    } else {
        Log.i(TAG, "Skipped feed sponsored pool hooks to isolate feed Reels carousel loading")
    }
    return installedAny
}
