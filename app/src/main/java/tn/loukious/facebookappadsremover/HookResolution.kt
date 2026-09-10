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

internal fun resolveHooks(classLoader: ClassLoader, bridge: DexKitBridge): ResolvedHooks {
    val classGroups = bridge.batchFindClassUsingStrings {
        groups(
            mapOf(
                "listBuilderByString" to listOf("Non ads story fall into ads rendering logic, StoryType=%s, StoryId=%s"),
                "pluginPack" to listOf("FbShortsViewerPluginPack", "MarketplaceAdsPluginPack"),
                "adKindEnum" to listOf("AD", "UGC", "PARADE", "MIDCARD"),
                "feedCsrFilters" to listOf("FeedCSRCacheFilter", "FeedCSRCacheFilter2025H1", "FeedCSRCacheFilter2026H1"),
                "sponsoredPool" to listOf("SponsoredPoolContainerAdapter", "Edge type mismatch; not added", "Sponsored Pool"),
                "sponsoredStoryManager" to listOf("FeedSponsoredStoryHolder.onPositionReset", "freshFeedStoryHolder"),
                "storyAdsInDisc" to STORY_AD_PROVIDER_TAGS
            ),
            StringMatchType.Equals
        )
    }

    Log.i(
        TAG,
        "DexKit groups: reels=${classGroups["listBuilderByString"]?.size ?: 0}, " +
            "plugin=${classGroups["pluginPack"]?.size ?: 0}, " +
            "adKind=${classGroups["adKindEnum"]?.size ?: 0}, " +
            "feedCsr=${classGroups["feedCsrFilters"]?.size ?: 0}, " +
            "feedPool=${classGroups["sponsoredPool"]?.size ?: 0}, " +
            "feedMgr=${classGroups["sponsoredStoryManager"]?.size ?: 0}, " +
            "storyAdsInDisc=${classGroups["storyAdsInDisc"]?.size ?: 0}"
    )

    val adKindEnumClass = resolveAdKindEnumClass(classLoader, classGroups["adKindEnum"].orEmpty(), bridge)
    val listBuilderClass = resolveListBuilderClass(classGroups["listBuilderByString"].orEmpty(), bridge)
    val pluginPackClasses = resolvePluginPackClasses(classGroups["pluginPack"].orEmpty(), bridge)
    val sponsoredPoolClass = resolveSponsoredPoolClass(classGroups["sponsoredPool"].orEmpty(), bridge)
    val sponsoredStoryManagerClass =
        resolveSponsoredStoryManagerClass(classGroups["sponsoredStoryManager"].orEmpty(), bridge)
    val storyAdProviderClasses =
        resolveStoryAdProviderClasses(classGroups["storyAdsInDisc"].orEmpty(), bridge)

    val appendMethod = listBuilderClass?.let { resolveAppendMethod(classLoader, it) }
    val factoryMethod = listBuilderClass?.let { resolveFactoryMethod(classLoader, it) }
    val pluginMethods = pluginPackClasses.mapNotNull { resolvePluginPackMethod(classLoader, it) }
    val instreamBannerEligibilityMethod = resolveInstreamBannerEligibilityMethod(classLoader, bridge)
    val indicatorPillAdEligibilityMethod = resolveIndicatorPillAdEligibilityMethod(classLoader, bridge)
    val reelsBannerRenderMethods = resolveReelsBannerRenderMethods(classLoader, bridge)
    val feedCsrFilterHooks =
        resolveFeedCsrFilterMethods(classLoader, classGroups["feedCsrFilters"].orEmpty(), bridge)
    val lateFeedListHooks = resolveLateFeedListHooks(classLoader, bridge)
    val storyPoolAddMethods = resolveStoryPoolAddMethods(classLoader, bridge)
    val poolClassInstance = sponsoredPoolClass?.getInstance(classLoader)
    val sponsoredStoryManagerClassInstance = sponsoredStoryManagerClass?.getInstance(classLoader)
    val poolAddMethod = sponsoredPoolClass?.let { resolveSponsoredPoolAddMethod(classLoader, it) }
    val sponsoredStoryNextMethod =
        sponsoredStoryManagerClass?.let { resolveSponsoredStoryNextMethod(classLoader, it) }
    val storyAdProviders = storyAdProviderClasses
        .mapNotNull { provider ->
            runCatching { resolveStoryAdProviderHooks(classLoader, provider) }.getOrNull()
        }
        .filter { provider ->
            provider.mergeMethod != null ||
                provider.fetchMoreAdsMethod != null ||
                provider.deferredUpdateMethod != null ||
                provider.insertionTriggerMethod != null
        }
        .distinctBy { it.providerClass.name }
    val gameAdRequestMethods = resolveGameAdRequestMethods(classLoader, bridge)
    val gameAdBridgePostMessageMethod = resolveGameAdBridgePostMessageMethod(gameAdRequestMethods)
    val playableAdActivityOnCreate = resolvePlayableAdActivityOnCreate(classLoader)
    val gameAdUiActivityMethods = resolveGameAdUiActivityMethods(classLoader)

    Log.i(TAG, "[Resolve] Resolved reels list builder=${listBuilderClass?.name ?: "none"}")
    Log.i(TAG, "[Resolve] Resolved plugin packs=${pluginPackClasses.joinToString { it.name }}")
    Log.i(TAG, "[Resolve] Resolved banner state eligibility=${instreamBannerEligibilityMethod?.declaringClass?.name ?: "none"}")
    Log.i(TAG, "[Resolve] Resolved indicator pill eligibility=${indicatorPillAdEligibilityMethod?.declaringClass?.name ?: "none"}")
    Log.i(TAG, "[Resolve] Resolved Reels banner render hooks=${reelsBannerRenderMethods.joinToString { it.declaringClass.name }}")
    Log.i(TAG, "[Resolve] Resolved feed CSR filters=${feedCsrFilterHooks.joinToString { "${it.method.declaringClass.name}[list=${it.listArgIndex}]" }}")
    Log.i(TAG, "[Resolve] Resolved late feed list hooks=${lateFeedListHooks.joinToString { it.method.declaringClass.name }}")
    Log.i(TAG, "[Resolve] Resolved story pool add hooks=${storyPoolAddMethods.joinToString { it.declaringClass.name }}")
    Log.i(TAG, "[Resolve] Resolved feed sponsored pool=${sponsoredPoolClass?.name ?: "none"}")
    Log.i(TAG, "[Resolve] Resolved feed sponsored manager=${sponsoredStoryManagerClass?.name ?: "none"}")
    Log.i(TAG, "[Resolve] Resolved feed add method=${poolAddMethod?.name ?: "none"}")
    Log.i(TAG, "[Resolve] Resolved feed next method=${sponsoredStoryNextMethod?.name ?: "none"}")
    Log.i(TAG, "[Resolve] Resolved story ad source classes=${storyAdProviderClasses.joinToString { it.name }}")
    Log.i(TAG, "[Resolve] Resolved story ad providers=${storyAdProviders.joinToString { it.providerClass.name }}")
    Log.i(TAG, "[Resolve] Resolved game ad requests=${gameAdRequestMethods.joinToString { it.declaringClass.name }}")
    Log.i(TAG, "[Resolve] Resolved game ad bridge=${gameAdBridgePostMessageMethod?.declaringClass?.name ?: "none"}")
    Log.i(TAG, "[Resolve] Resolved playable ad activity=${playableAdActivityOnCreate?.declaringClass?.name ?: "none"}")
    Log.i(TAG, "[Resolve] Resolved game ad UI activities=${gameAdUiActivityMethods.joinToString { it.declaringClass.name }}")
    logMissingHooks(
        pluginPackClasses = pluginPackClasses,
        factoryMethod = factoryMethod,
        pluginMethods = pluginMethods,
        instreamBannerEligibilityMethod = instreamBannerEligibilityMethod,
        indicatorPillAdEligibilityMethod = indicatorPillAdEligibilityMethod,
        reelsBannerRenderMethods = reelsBannerRenderMethods,
        feedCsrFilterHooks = feedCsrFilterHooks,
        lateFeedListHooks = lateFeedListHooks,
        storyPoolAddMethods = storyPoolAddMethods,
        sponsoredPoolClass = sponsoredPoolClass,
        poolAddMethod = poolAddMethod,
        sponsoredStoryManagerClass = sponsoredStoryManagerClass,
        sponsoredStoryNextMethod = sponsoredStoryNextMethod,
        storyAdProviderClasses = storyAdProviderClasses,
        storyAdProviders = storyAdProviders,
        gameAdRequestMethods = gameAdRequestMethods,
        gameAdBridgePostMessageMethod = gameAdBridgePostMessageMethod,
        playableAdActivityOnCreate = playableAdActivityOnCreate,
        gameAdUiActivityMethods = gameAdUiActivityMethods
    )

    return ResolvedHooks(
        adKindEnumClass = adKindEnumClass,
        listBuilderAppendMethod = appendMethod,
        listBuilderFactoryMethod = factoryMethod,
        pluginPackBuildMethods = pluginMethods,
        instreamBannerEligibilityMethod = instreamBannerEligibilityMethod,
        indicatorPillAdEligibilityMethod = indicatorPillAdEligibilityMethod,
        reelsBannerRenderMethods = reelsBannerRenderMethods,
        feedCsrFilterHooks = feedCsrFilterHooks,
        lateFeedListHooks = lateFeedListHooks,
        storyPoolAddMethods = storyPoolAddMethods,
        sponsoredPoolClass = poolClassInstance,
        sponsoredPoolAddMethod = poolAddMethod,
        sponsoredStoryManagerClass = sponsoredStoryManagerClassInstance,
        sponsoredStoryNextMethod = sponsoredStoryNextMethod,
        storyAdProviders = storyAdProviders,
        gameAdRequestMethods = gameAdRequestMethods,
        gameAdBridgePostMessageMethod = gameAdBridgePostMessageMethod,
        playableAdActivityOnCreate = playableAdActivityOnCreate,
        gameAdUiActivityMethods = gameAdUiActivityMethods
    )
}

internal fun logMissingHooks(
    pluginPackClasses: List<ClassData>,
    factoryMethod: Method?,
    pluginMethods: List<Method>,
    instreamBannerEligibilityMethod: Method?,
    indicatorPillAdEligibilityMethod: Method?,
    reelsBannerRenderMethods: List<Method>,
    feedCsrFilterHooks: List<FeedCsrFilterHook>,
    lateFeedListHooks: List<FeedListSanitizerHook>,
    storyPoolAddMethods: List<Method>,
    sponsoredPoolClass: ClassData?,
    poolAddMethod: Method?,
    sponsoredStoryManagerClass: ClassData?,
    sponsoredStoryNextMethod: Method?,
    storyAdProviderClasses: List<ClassData>,
    storyAdProviders: List<StoryAdProviderHooks>,
    gameAdRequestMethods: List<Method>,
    gameAdBridgePostMessageMethod: Method?,
    playableAdActivityOnCreate: Method?,
    gameAdUiActivityMethods: List<Method>
) {
    if (factoryMethod == null) Log.missing(TAG, "Reels list factory method")
    if (pluginPackClasses.isEmpty()) {
        Log.missing(TAG, "PluginPack classes")
    } else if (pluginMethods.isEmpty()) {
        Log.missing(TAG, "PluginPack build methods")
    }
    if (instreamBannerEligibilityMethod == null) Log.missing(TAG, "Instream banner eligibility method")
    if (indicatorPillAdEligibilityMethod == null) Log.missing(TAG, "Reels indicator pill eligibility method")
    if (reelsBannerRenderMethods.isEmpty()) Log.missing(TAG, "Reels banner render methods")
    if (feedCsrFilterHooks.isEmpty()) Log.missing(TAG, "Feed CSR filter methods")
    if (lateFeedListHooks.isEmpty()) Log.missing(TAG, "Late feed list sanitizer methods")
    if (storyPoolAddMethods.isEmpty()) Log.missing(TAG, "Story pool add methods")
    if (sponsoredPoolClass == null) {
        Log.missing(TAG, "Sponsored pool class")
    } else if (poolAddMethod == null) {
        Log.missing(TAG, "Sponsored pool add method")
    }
    if (sponsoredStoryManagerClass == null) {
        Log.missing(TAG, "Sponsored story manager class")
    } else if (sponsoredStoryNextMethod == null) {
        Log.missing(TAG, "Sponsored story next method")
    }
    if (storyAdProviderClasses.isEmpty()) Log.missing(TAG, "Story ad source classes")
    if (storyAdProviders.isEmpty()) Log.missing(TAG, "Story ad provider methods")
    if (gameAdRequestMethods.isEmpty()) Log.missing(TAG, "Game ad request methods")
    if (gameAdBridgePostMessageMethod == null) Log.missing(TAG, "Game ad bridge postMessage method")
    if (playableAdActivityOnCreate == null) Log.missing(TAG, "Playable ad activity lifecycle method")
    if (gameAdUiActivityMethods.isEmpty()) Log.missing(TAG, "Game ad UI activity lifecycle methods")
}

internal fun resolveAdKindEnumClass(
    classLoader: ClassLoader,
    batchCandidates: Collection<ClassData>,
    bridge: DexKitBridge
): Class<*>? {
    val directCandidates = if (batchCandidates.isNotEmpty()) {
        batchCandidates
    } else {
        bridge.findClass {
            matcher {
                usingEqStrings("AD", "UGC", "PARADE", "MIDCARD")
            }
        }
    }

    directCandidates.forEach { candidate ->
        val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull() ?: return@forEach
        val constants = clazz.enumConstants?.map { it.toString() }.orEmpty()
        if (clazz.isEnum && "AD" in constants && "UGC" in constants) {
            return clazz
        }
    }

    return null
}

internal fun resolveListBuilderClass(
    batchCandidates: Collection<ClassData>,
    bridge: DexKitBridge
): ClassData? {
    val structuralCandidates = bridge.findClass {
        matcher {
            methods {
                matchType = MatchType.Contains
                add {
                    modifiers = Modifier.STATIC
                    returnType = "void"
                    paramTypes = listOf(null, null, null, null, null, "java.util.List")
                }
                add {
                    returnType = "void"
                    paramTypes = listOf(null, null, null, null, null, "java.util.List")
                }
                add {
                    modifiers = Modifier.STATIC
                    returnType = "java.util.ArrayList"
                    paramTypes = listOf(null, null, null, null, "boolean")
                }
                add {
                    modifiers = Modifier.STATIC
                    returnType = "java.util.ArrayList"
                    paramTypes = listOf(null, null, null, null, null, "boolean")
                }
                add {
                    returnType = "java.util.ArrayList"
                    paramTypes = listOf(null, null, null, "java.lang.Iterable")
                }
                add {
                    returnType = "java.util.List"
                    paramTypes = listOf(null, null, null, "boolean")
                }
            }
        }
    }

    return structuralCandidates.singleOrNull()
        ?: batchCandidates.firstOrNull()
}

internal fun resolvePluginPackClasses(
    batchCandidates: Collection<ClassData>,
    bridge: DexKitBridge
): List<ClassData> {
    if (batchCandidates.isNotEmpty()) {
        return batchCandidates.toList()
    }

    val result = bridge.findClass {
        matcher {
            methods {
                matchType = MatchType.Contains
                add {
                    returnType = "java.lang.String"
                    paramCount = 0
                    usingStrings("FbShortsViewerPluginPack")
                }
                add {
                    returnType = "java.util.List"
                    paramCount = 0
                }
            }
        }
    }.toMutableList()
    
    result.addAll(bridge.findClass {
        matcher {
            methods {
                matchType = MatchType.Contains
                add {
                    returnType = "java.lang.String"
                    paramCount = 0
                    usingStrings("MarketplaceAdsPluginPack")
                }
                add {
                    returnType = "java.util.List"
                    paramCount = 0
                }
            }
        }
    })
    
    return result
}

internal fun resolveSponsoredPoolClass(
    batchCandidates: Collection<ClassData>,
    bridge: DexKitBridge
): ClassData? {
    val candidates = if (batchCandidates.isNotEmpty()) {
        batchCandidates
    } else {
        bridge.findClass {
            matcher {
                usingEqStrings("SponsoredPoolContainerAdapter", "Edge type mismatch; not added")
            }
        }
    }

    return candidates.firstOrNull { candidate ->
        candidate.findMethod {
            findFirst = true
            matcher {
                returnType = "boolean"
                paramTypes = listOf("com.facebook.graphql.model.GraphQLFeedUnitEdge")
            }
        }.isNotEmpty()
    }
}

internal fun resolveSponsoredStoryManagerClass(
    batchCandidates: Collection<ClassData>,
    bridge: DexKitBridge
): ClassData? {
    val candidates = if (batchCandidates.isNotEmpty()) {
        batchCandidates
    } else {
        bridge.findClass {
            matcher {
                usingEqStrings("FeedSponsoredStoryHolder.onPositionReset", "freshFeedStoryHolder")
            }
        }
    }

    return candidates.firstOrNull { candidate ->
        candidate.findMethod {
            findFirst = true
            matcher {
                returnType = "com.facebook.graphql.model.GraphQLFeedUnitEdge"
                paramCount = 0
            }
        }.isNotEmpty()
    }
}


internal fun resolveStoryAdProviderClasses(
    batchCandidates: Collection<ClassData>,
    bridge: DexKitBridge
): List<ClassData> {
    val candidates = LinkedHashMap<String, ClassData>()
    batchCandidates.forEach { candidate ->
        candidates.putIfAbsent(candidate.name, candidate)
    }
    STORY_AD_PROVIDER_TAGS.forEach { tag ->
        bridge.findClass {
            matcher {
                usingStrings(tag)
            }
        }.forEach { candidate ->
            candidates.putIfAbsent(candidate.name, candidate)
        }
    }

    return candidates.values.toList()
}

internal fun resolveStoryAdProviderHooks(
    classLoader: ClassLoader,
    providerClassData: ClassData
): StoryAdProviderHooks {
    val providerClass = providerClassData.getInstance(classLoader)
    val insertionTriggerMethod = providerClassData.findMethod {
        findFirst = true
        matcher {
            returnType = "void"
            usingStrings("ads_insertion")
        }
    }.firstMethodInstanceOrNull(classLoader)
    return resolveStoryAdProviderHooks(providerClass, insertionTriggerMethod)
}

internal fun resolveStoryAdProviderHooks(
    providerClass: Class<*>,
    insertionTriggerMethod: Method? = null
): StoryAdProviderHooks {
    val methods = (providerClass.declaredMethods + providerClass.methods)
        .distinctBy { method ->
            "${method.name}:${method.parameterTypes.joinToString { it.name }}:${method.returnType.name}"
        }

    val mergeMethod = methods.firstOrNull { method ->
        method.parameterCount == 3 &&
            method.parameterTypes[0].name == "com.facebook.auth.usersession.FbUserSession" &&
            isFeedListType(method.parameterTypes[2]) &&
            isFeedListType(method.returnType)
    }?.apply { isAccessible = true }
    val fetchMoreAdsMethod = methods.firstOrNull { method ->
        method.returnType == Void.TYPE &&
            method.parameterCount == 2 &&
            isFeedListType(method.parameterTypes[0]) &&
            method.parameterTypes[1] == Int::class.javaPrimitiveType
    }?.apply { isAccessible = true }
    val deferredUpdateMethod = methods.firstOrNull { method ->
        method.returnType == Void.TYPE &&
            method.parameterCount == 2 &&
            !method.parameterTypes[0].isPrimitive &&
            isConcreteFeedListType(method.parameterTypes[1])
    }?.apply { isAccessible = true }

    return StoryAdProviderHooks(
        providerClass = providerClass,
        mergeMethod = mergeMethod,
        fetchMoreAdsMethod = fetchMoreAdsMethod,
        deferredUpdateMethod = deferredUpdateMethod,
        insertionTriggerMethod = insertionTriggerMethod
    )
}

internal fun resolveLithoLayoutContextType(
    componentClass: Class<*>,
    wrapperClass: Class<*>,
    parameterCount: Int
): Class<*>? {
    fun contextCandidates(type: Class<*>): List<Class<*>> {
        return type.declaredMethods
            .filter { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.parameterCount == parameterCount &&
                    !method.returnType.isPrimitive &&
                    !method.parameterTypes[0].isPrimitive
            }
            .map { it.parameterTypes[0] }
    }

    val componentCandidates = contextCandidates(componentClass)
    val wrapperCandidates = contextCandidates(wrapperClass).toSet()
    return componentCandidates.firstOrNull { it in wrapperCandidates }
        ?: componentCandidates.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
}

internal fun resolveFeedEdgeField(componentClass: Class<*>): Field? {
    val declared = componentClass.declaredFields.filter { field ->
        !Modifier.isStatic(field.modifiers) && !field.type.isPrimitive
    }
    val resolved = declared.firstOrNull { it.type.name == GRAPHQL_FEED_UNIT_EDGE_CLASS }
        ?: declared.firstOrNull { declaresFeedStoryCategoryAccessor(it.type) }
    return resolved?.apply { isAccessible = true }
}

internal fun resolveWrapperChildField(
    wrapperClass: Class<*>,
    componentClass: Class<*>
): Field? {
    val resolved = wrapperClass.declaredFields.firstOrNull { field ->
        !Modifier.isStatic(field.modifiers) &&
            field.type != Any::class.java &&
            field.type.isAssignableFrom(componentClass)
    }
    return resolved?.apply { isAccessible = true }
}

internal fun resolveAppendMethod(classLoader: ClassLoader, listBuilderClass: ClassData): Method? {
    val clazz = listBuilderClass.getInstance(classLoader)
    return resolveListBuilderMethods(clazz)
        .filter { method ->
            method.returnType == Void.TYPE &&
                method.listParameterIndexes().size == 1 &&
                method.listParameterIndexes().first() == method.parameterCount - 1
        }
        .maxByOrNull { method -> scoreAppendMethod(method, clazz) }
        ?.apply { isAccessible = true }
}

internal fun resolveFactoryMethod(classLoader: ClassLoader, listBuilderClass: ClassData): Method? {
    val clazz = listBuilderClass.getInstance(classLoader)
    return resolveListBuilderMethods(clazz)
        .filter { method ->
            Modifier.isStatic(method.modifiers) &&
                method.returnType == ArrayList::class.java &&
                method.parameterTypes.lastOrNull() == Boolean::class.javaPrimitiveType &&
                (
                    method.parameterTypes.firstOrNull() == clazz ||
                        method.parameterTypes.getOrNull(1) == clazz
                    )
        }
        .maxByOrNull { method -> scoreFactoryMethod(method, clazz) }
        ?.apply { isAccessible = true }
}

internal fun resolveListBuilderMethods(clazz: Class<*>): List<Method> {
    val methods = LinkedHashMap<String, Method>()
    (clazz.declaredMethods + clazz.methods).forEach { method ->
        if (method.name != "<init>" && method.name != "<clinit>") {
            // Opt 2.1: Pre-set accessibility on candidate methods immediately upon resolution
            method.isAccessible = true
            methods.putIfAbsent("${method.name}/${method.parameterCount}/${Modifier.isStatic(method.modifiers)}", method)
        }
    }
    return methods.values.toList()
}

internal fun scoreAppendMethod(method: Method, owner: Class<*>): Int {
    val listIndex = method.listParameterIndexes().firstOrNull() ?: return Int.MIN_VALUE
    var score = 0
    if (listIndex == method.parameterCount - 1) score += 10_000
    if (method.parameterCount == 6) score += 5_000
    if (!Modifier.isStatic(method.modifiers)) score += 2_000
    if (Modifier.isStatic(method.modifiers) && method.parameterTypes.getOrNull(1) == owner) score += 1_500
    if (Modifier.isStatic(method.modifiers) && method.parameterTypes.firstOrNull() == owner) score += 750
    score -= method.parameterCount * 10
    return score
}

internal fun scoreFactoryMethod(method: Method, owner: Class<*>): Int {
    var score = 0
    if (method.parameterCount == 6) score += 4_000
    if (method.parameterCount == 5) score += 3_000
    if (method.parameterTypes.getOrNull(1) == owner) score += 2_000
    if (method.parameterTypes.firstOrNull() == owner) score += 1_000
    if (method.parameterTypes.firstOrNull()?.name == "com.facebook.auth.usersession.FbUserSession") score += 500
    score -= method.parameterCount * 10
    return score
}

internal fun resolvePluginPackMethod(classLoader: ClassLoader, pluginPackClass: ClassData): Method? {
    val method = pluginPackClass.findMethod {
        findFirst = true
        matcher {
            returnType = "java.util.List"
            paramCount = 0
        }
    }.firstOrNull() ?: return null

    return listOf(method).firstMethodInstanceOrNull(classLoader)
}

internal fun resolveFeedCsrFilterMethods(
    classLoader: ClassLoader,
    batchCandidates: Collection<ClassData>,
    bridge: DexKitBridge
): List<FeedCsrFilterHook> {
    val namedCandidates = if (batchCandidates.isNotEmpty()) {
        batchCandidates.toList()
    } else {
        findClassesByZeroArgStringTags(
            bridge,
            listOf(
                "FeedCSRCacheFilter",
                "FeedCSRCacheFilter2025H1",
                "FeedCSRCacheFilter2026H1",
                "FeedCSRCacheFilter2026H2"
            )
        )
    }

    val candidates = LinkedHashMap<String, ClassData>()
    namedCandidates.forEach { candidates.putIfAbsent(it.name, it) }

    return candidates.values.mapNotNull { candidate ->
        val fourArgMethod = candidate.findMethod {
            findFirst = true
            matcher {
                paramTypes = listOf(
                    "com.facebook.auth.usersession.FbUserSession",
                    null,
                    "com.google.common.collect.ImmutableList",
                    "int"
                )
            }
        }.firstMethodInstanceOrNull(classLoader)
        when {
            fourArgMethod != null -> FeedCsrFilterHook(fourArgMethod, 2)
            else -> candidate.findMethod {
                findFirst = true
                matcher {
                    paramTypes = listOf(
                        "com.facebook.auth.usersession.FbUserSession",
                        "com.google.common.collect.ImmutableList",
                        "int"
                    )
                }
            }.firstMethodInstanceOrNull(classLoader)?.let { method ->
                FeedCsrFilterHook(method, 1)
            }
        }
    }.filter { hook ->
        !Modifier.isAbstract(hook.method.modifiers) &&
            !hook.method.declaringClass.isInterface &&
            !Modifier.isAbstract(hook.method.declaringClass.modifiers)
    }.distinctBy { "${it.method.declaringClass.name}.${it.method.name}:${it.listArgIndex}" }
}

internal fun resolveLateFeedListHooks(
    classLoader: ClassLoader,
    bridge: DexKitBridge
): List<FeedListSanitizerHook> {
    val hooks = LinkedHashMap<String, FeedListSanitizerHook>()

    bridge.findClass {
        matcher {
            usingStrings("handleStorageStories", "Empty Storage List")
        }
    }.forEach { candidate ->
        candidate.findMethod {
            findFirst = true
            matcher {
                returnType = "void"
                paramTypes = listOf(null, "com.google.common.collect.ImmutableList", "int")
            }
        }.firstMethodInstanceOrNull(classLoader)?.let { method ->
            hooks.putIfAbsent(
                "${method.declaringClass.name}.${method.name}:1",
                FeedListSanitizerHook(method, 1)
            )
        }
    }

    bridge.findClass {
        matcher {
            usingStrings("cancelVendingTimerAndAddToPool_")
        }
    }.forEach { candidate ->
        candidate.findMethod {
            findFirst = true
            matcher {
                returnType = "void"
                paramTypes = listOf("com.google.common.collect.ImmutableList", "java.lang.String")
            }
        }.firstMethodInstanceOrNull(classLoader)?.let { method ->
            hooks.putIfAbsent(
                "${method.declaringClass.name}.${method.name}:0",
                FeedListSanitizerHook(method, 0)
            )
        }
    }

    findClassesByZeroArgStringTags(
        bridge,
        listOf(
            "CSRNoOpStorageLifecycleImpl",
            "FeedCSRStorageLifecycle",
            "FriendlyFeedCSRStorageLifecycle",
            "FbShortsCSRStorageLifecycle"
        )
    ).forEach { candidate ->
        candidate.findMethod {
            findFirst = true
            matcher {
                returnType = "void"
                paramTypes = listOf(
                    "com.facebook.auth.usersession.FbUserSession",
                    null,
                    "com.google.common.collect.ImmutableList"
                )
            }
        }.firstMethodInstanceOrNull(classLoader)?.let { method ->
            hooks.putIfAbsent(
                "${method.declaringClass.name}.${method.name}:2",
                FeedListSanitizerHook(method, 2)
            )
        }
    }

    return hooks.values.filter { hook ->
        !Modifier.isAbstract(hook.method.modifiers) &&
            !hook.method.declaringClass.isInterface &&
            !Modifier.isAbstract(hook.method.declaringClass.modifiers)
    }.toList()
}

internal fun resolveStoryPoolAddMethods(
    classLoader: ClassLoader,
    bridge: DexKitBridge
): List<Method> {
    val methods = LinkedHashMap<String, Method>()

    findClassesByZeroArgStringTags(
        bridge,
        listOf("CSRStoryPoolCoordinator", "FeedStoryPoolCoordinator")
    ).forEach { candidate ->
        // Hook every instance boolean single-arg method on the coordinator, not
        // just the first match: 576's shorts pool (X.1mn) exposes both a static
        // eligibility helper and the real pool-add ABd, and findFirst picked the
        // helper, leaving the FbShorts pool's add path unhooked.
        candidate.findMethod {
            matcher {
                returnType = "boolean"
                paramTypes = listOf(null)
            }
        }.mapNotNull { methodData ->
            runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
        }.forEach { method ->
            methods.putIfAbsent("${method.declaringClass.name}.${method.name}", method)
        }
    }

    return methods.values.filter { method ->
        !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            !method.declaringClass.isInterface &&
            !Modifier.isAbstract(method.declaringClass.modifiers)
    }.toList()
}

internal fun resolveInstreamBannerEligibilityMethod(
    classLoader: ClassLoader,
    bridge: DexKitBridge
): Method? {
    val candidates = findClassesByZeroArgStringTags(
        bridge,
        listOf("InstreamAdIdleWithBannerState")
    )

    candidates.asSequence().mapNotNull { candidate ->
        candidate.findMethod {
            findFirst = true
            matcher {
                returnType = "boolean"
                paramCount = 0
            }
        }.firstMethodInstanceOrNull(classLoader)
    }.firstOrNull { method ->
        !Modifier.isStatic(method.modifiers)
    }?.apply { isAccessible = true }?.let { return it }

    candidates.asSequence().mapNotNull { candidate ->
        val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull() ?: return@mapNotNull null
        var current: Class<*>? = clazz.superclass
        while (current != null && current != Any::class.java) {
            current.declaredMethods.firstOrNull { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterCount == 0
            }?.let { method ->
                method.isAccessible = true
                return@mapNotNull method
            }
            current = current.superclass
        }
        null
    }.firstOrNull()?.let { return it }

    return null
}

internal fun resolveIndicatorPillAdEligibilityMethod(
    classLoader: ClassLoader,
    bridge: DexKitBridge
): Method? {
    val classCandidates = bridge.findClass {
        matcher {
            usingStrings(
                "IndicatorPillComponent.render",
                "com.facebook.feedback.comments.plugins.indicatorpill.reelsadsfloatingcta.ReelsAdsFloatingCtaPlugin"
            )
        }
    }

    return classCandidates.asSequence().mapNotNull { candidate ->
        candidate.findMethod {
            findFirst = true
            matcher {
                modifiers = Modifier.STATIC
                returnType = "boolean"
                paramCount = 3
            }
        }.firstMethodInstanceOrNull(classLoader)
    }.firstOrNull()?.apply { isAccessible = true }
}

internal fun resolveReelsBannerRenderMethods(
    classLoader: ClassLoader,
    bridge: DexKitBridge
): List<Method> {
    val componentClasses = LinkedHashMap<String, Class<*>>()

    listOf("ReelsBannerAdsComponent", "ReelsBannerAdsNativeComponent").forEach { componentName ->
        bridge.findClass {
            matcher {
                usingStrings(componentName)
            }
        }.forEach { candidate ->
            val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull() ?: return@forEach
            if (resolveLithoRenderMethod(clazz) != null) {
                componentClasses.putIfAbsent(clazz.name, clazz)
            }
        }
    }

    return componentClasses.values.mapNotNull { clazz ->
        resolveLithoRenderMethod(clazz)
    }
}

internal fun resolveLithoRenderMethod(componentClass: Class<*>): Method? {
    return componentClass.declaredMethods.firstOrNull { method ->
        !Modifier.isStatic(method.modifiers) &&
            !method.isBridge &&
            !method.isSynthetic &&
            method.parameterCount == 1 &&
            !method.returnType.isPrimitive &&
            method.returnType != Void.TYPE &&
            method.returnType != Any::class.java &&
            method.returnType.isAssignableFrom(componentClass)
    }?.apply { isAccessible = true }
}

// ---------------------------------------------------------------------------
// Full-page sponsored Reels ads (576).
//
// The sponsored reel is an organic-shaped story (comments, reactions,
// follower social context) injected into the Reels feed, gated server-side
// ("reels_ads_ap_plus_newsfeed_qp"), so only some accounts see it. Its
// comment flyout is rendered by the ReelsAdsCaptionCommentComponent (found
// via the stable CallerContext string), whose construction site reveals the
// reels item model: field A04 (type C8Wm) is the ad model and is non-null
// only for ads — the structural "is this reel an ad" marker.
// ---------------------------------------------------------------------------

internal fun resolveWrapperListField(clazz: Class<*>?): Field? {
    if (clazz == null) return null
    wrapperListFieldCache[clazz]?.let { return it.orElse(null) }
    val field = runCatching {
        var current: Class<*>? = clazz
        var found: Field? = null
        while (current != null && current != Any::class.java && found == null) {
            found = current.declaredFields.firstOrNull { candidate ->
                !Modifier.isStatic(candidate.modifiers) &&
                    Iterable::class.java.isAssignableFrom(candidate.type)
            }
            current = current.superclass
        }
        found?.isAccessible = true
        found
    }.getOrNull()
    wrapperListFieldCache[clazz] = Optional.ofNullable(field)
    return field
}

internal fun resolveReelsAdClassifier(classLoader: ClassLoader, bridge: DexKitBridge): ReelsAdClassifier? {
    // 1. The classification enum via its stable server-value strings.
    val enumClass = bridge.findClass {
        matcher {
            usingStrings("ADS_MIDCARD")
        }
    }.asSequence()
        .mapNotNull { runCatching { it.getInstance(classLoader) }.getOrNull() }
        .firstOrNull { it.isEnum } ?: run {
        Log.w(TAG, "[Resolve] Reels ad classifier: classification enum not found")
        return null
    }
    val adValues = enumClass.enumConstants
        .filter { runCatching { it.toString() }.getOrNull() in AD_CLASSIFICATION_VALUES }
        .toSet()
    if (adValues.isEmpty()) {
        Log.w(TAG, "[Resolve] Reels ad classifier: enum ${enumClass.name} has no ad constants")
        return null
    }

    // 2. Model interfaces: interfaces declaring exactly one zero-arg method
    //    returning the classification enum.
    val modelInterfaces = bridge.findMethod {
        matcher {
            returnType = enumClass.name
            paramCount = 0
        }
    }.asSequence()
        .mapNotNull { methodData ->
            runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
        }
        .map { it.declaringClass }
        .filter { it.isInterface }
        .distinct()
        .mapNotNull { candidate ->
            runCatching {
                val methods = candidate.declaredMethods.filter { method ->
                    method.parameterCount == 0 && method.returnType == enumClass
                }
                if (methods.size != 1) return@mapNotNull null
                methods[0].isAccessible = true
                candidate to methods[0]
            }.getOrNull()
        }
        .toList()
    if (modelInterfaces.isEmpty()) {
        Log.w(TAG, "[Resolve] Reels ad classifier: model interfaces not found")
        return null
    }
    return ReelsAdClassifier(modelInterfaces, adValues).also {
        it.enumClassName = enumClass.name
        reelsGuardModelInterfaceSpecs = modelInterfaces.map { (iface, method) ->
            "${iface.name}#${method.name}"
        }
        reelsGuardEnumClassName = enumClass.name
    }
}

internal fun resolveReelsGuardMethodSpec(classLoader: ClassLoader, spec: String): Method? {
    val idx = spec.indexOf('#')
    if (idx <= 0) return null
    return runCatching {
        val clazz = Class.forName(spec.substring(0, idx), false, classLoader)
        val method = clazz.getDeclaredMethod(spec.substring(idx + 1))
        method.isAccessible = true
        method
    }.getOrNull()
}

internal fun resolveSponsoredPoolAddMethod(classLoader: ClassLoader, sponsoredPoolClass: ClassData): Method? {
    val method = sponsoredPoolClass.findMethod {
        findFirst = true
        matcher {
            returnType = "boolean"
            paramTypes = listOf("com.facebook.graphql.model.GraphQLFeedUnitEdge")
        }
    }.firstOrNull() ?: return null

    return listOf(method).firstMethodInstanceOrNull(classLoader)
}

internal fun resolveSponsoredStoryNextMethod(
    classLoader: ClassLoader,
    sponsoredStoryManagerClass: ClassData
): Method? {
    val method = sponsoredStoryManagerClass.findMethod {
        findFirst = true
        matcher {
            returnType = "com.facebook.graphql.model.GraphQLFeedUnitEdge"
            paramCount = 0
        }
    }.firstOrNull() ?: return null

    return listOf(method).firstMethodInstanceOrNull(classLoader)
}

internal fun resolveGameAdRequestMethods(
    classLoader: ClassLoader,
    bridge: DexKitBridge
): List<Method> {
    val methods = LinkedHashMap<String, Method>()
    GAME_AD_METHOD_TAGS.forEach { tag ->
        bridge.findMethod {
            matcher {
                returnType = "void"
                paramTypes = listOf("org.json.JSONObject")
                usingStrings(tag)
            }
        }.mapNotNull { methodData ->
            runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
        }.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name != "<init>" &&
                method.name != "<clinit>"
        }.forEach { method ->
            method.isAccessible = true
            methods.putIfAbsent("${method.declaringClass.name}.${method.name}", method)
        }
    }
    return methods.values.toList()
}

internal fun resolveGameAdBridgePostMessageMethod(gameAdRequestMethods: Collection<Method>): Method? {
    val bridgeClass = gameAdRequestMethods.firstOrNull()?.declaringClass ?: return null
    return bridgeClass.declaredMethods.firstOrNull { method ->
        method.name == "postMessage" &&
            method.parameterCount == 2 &&
            method.parameterTypes.all { it == String::class.java }
    }?.apply { isAccessible = true }
}

internal fun resolvePlayableAdActivityOnCreate(classLoader: ClassLoader): Method? {
    val activityClass = runCatching { classLoader.loadClass(NEKO_PLAYABLE_ACTIVITY_CLASS) }.getOrNull() ?: return null
    return activityClass.declaredMethods
        .firstOrNull { method ->
            method.name == "onResume" &&
                method.parameterCount == 0
        }?.apply { isAccessible = true }
}

internal fun resolveGameAdUiActivityMethods(classLoader: ClassLoader): List<Method> {
    val methods = LinkedHashMap<String, Method>()
    val classNames = listOf(
        AUDIENCE_NETWORK_ACTIVITY_CLASS,
        AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS
    )
    classNames.forEach { className ->
        val activityClass = runCatching { classLoader.loadClass(className) }.getOrNull()
        if (activityClass == null) {
            Log.w(TAG, "[Resolve] Game ad UI class not loadable: $className")
            return@forEach
        }
        (activityClass.declaredMethods + activityClass.methods)
            .firstOrNull { method ->
                (method.name == "onResume" && method.parameterCount == 0) ||
                    (method.name == "onStart" && method.parameterCount == 0) ||
                    (method.name == "onCreate" && method.parameterCount == 1 && method.parameterTypes[0] == Bundle::class.java)
            }?.apply {
                isAccessible = true
                methods.putIfAbsent("${declaringClass.name}.${name}", this)
            }
    }
    if (methods.isEmpty()) {
        resolveGameAdUiActivityMethodsFallback(classLoader, methods)
    }
    return methods.values.toList()
}

internal fun resolveGameAdUiActivityMethodsFallback(
    classLoader: ClassLoader,
    methods: LinkedHashMap<String, Method>
) {
    val activityClass = runCatching {
        classLoader.loadClass("android.app.Activity")
    }.getOrNull() ?: return
    GAME_AD_ACTIVITY_CLASS_NAMES.forEach { className ->
        val clazz = runCatching { classLoader.loadClass(className) }.getOrNull()
        if (clazz != null && activityClass.isAssignableFrom(clazz)) {
            (clazz.declaredMethods + clazz.methods)
                .firstOrNull { method ->
                    (method.name == "onResume" && method.parameterCount == 0) ||
                        (method.name == "onStart" && method.parameterCount == 0) ||
                        (method.name == "onCreate" && method.parameterCount == 1 && method.parameterTypes[0] == Bundle::class.java)
                }?.apply {
                    isAccessible = true
                    methods.putIfAbsent("${declaringClass.name}.${name}", this)
                }
        }
    }
}

internal fun resolveGameAdResolveMethod(type: Class<*>?): Method? {
    if (type == null) return null

    // The Javascript bridge entry itself (X.q10.postMessage(String, String))
    // fits this shape but is NOT a promise helper — invoking it re-posts the
    // message into the native pipeline. Exclude annotated entries and the
    // postMessage name so they are never picked as a resolve method.
    val candidates = (type.declaredMethods + type.methods).filter { method ->
        !Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            method.parameterCount == 2 &&
            method.parameterTypes[0] == String::class.java &&
            !method.parameterTypes[1].isPrimitive &&
            !method.isAnnotationPresent(JavascriptInterface::class.java) &&
            method.name != "postMessage"
    }

    return (candidates.firstOrNull { it.parameterTypes[1] == Any::class.java }
        ?: candidates.firstOrNull { JSONObject::class.java.isAssignableFrom(it.parameterTypes[1]) }
        ?: candidates.firstOrNull()
        )?.apply { isAccessible = true }
}

