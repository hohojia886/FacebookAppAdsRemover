package tn.loukious.facebookappadsremover

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.Messenger
import android.util.Log as AndroidLog
import android.view.View
import android.view.ViewGroup
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
import java.util.Collections
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal const val GAME_AD_WEBVIEW_HIDE_SCRIPT = """
(function(){
  if (window.__fbAppAdsRemoverBannerSweep) return;
  window.__fbAppAdsRemoverBannerSweep = true;
  function textOf(el) {
    try { return (el.innerText || el.textContent || '').toLowerCase(); } catch (e) { return ''; }
  }
  function attrsOf(el) {
    try { return ((el.id || '') + ' ' + (el.className || '') + ' ' + (el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('src') || '')).toLowerCase(); } catch (e) { return ''; }
  }
  function nearBottom(el) {
    try {
      var r = el.getBoundingClientRect();
      return r.height > 0 && r.height < Math.max(260, window.innerHeight * 0.35) && r.bottom > window.innerHeight * 0.55;
    } catch (e) { return false; }
  }
  function isAd(el) {
    var a = attrsOf(el);
    if (/audiencenetwork|adchoices|fbinstant.*ad|instant.*ad|banner.?ad|ad.?banner|ad-container|ad_container|sponsored/.test(a)) return true;
    if (!nearBottom(el)) return false;
    if (el.tagName === 'IFRAME') return true;
    var t = textOf(el);
    return t.indexOf('ads served by meta') >= 0 || t.indexOf('ad choices') >= 0;
  }
  function hide(el) {
    try {
      var target = el;
      for (var i = 0; i < 4 && target.parentElement && nearBottom(target.parentElement); i++) target = target.parentElement;
      target.style.setProperty('display', 'none', 'important');
      target.style.setProperty('visibility', 'hidden', 'important');
      target.style.setProperty('height', '0px', 'important');
      target.style.setProperty('min-height', '0px', 'important');
      target.style.setProperty('pointer-events', 'none', 'important');
    } catch (e) {}
  }
  var sweepTimer = null;
  function sweep() {
    if (sweepTimer) clearTimeout(sweepTimer);
    sweepTimer = setTimeout(function() {
      try {
        document.querySelectorAll('iframe, [id*="ad"], [class*="ad"], [id*="sponsored"], [class*="sponsored"], [aria-label*="ad"]').forEach(function(el) {
          if (isAd(el)) hide(el);
        });
      } catch (e) {}
    }, 150);
  }
  sweep();
  var observer = new MutationObserver(sweep);
  observer.observe(document.documentElement || document.body, {childList:true, subtree:true, attributes:true, attributeFilter:['id', 'class', 'aria-label']});
})();
"""

internal val gameAdSurfaceHooksInstalled = AtomicInteger(0)
internal val gameAdResultHooksInstalled = AtomicInteger(0)
internal val gameAdServiceDispatchHooksInstalled = AtomicInteger(0)
internal val gameAdSystemDiagnosticsInstalled = AtomicInteger(0)
internal val gameAdDynamicDiagnosticsInstalled = AtomicInteger(0)
internal val audienceNetworkViewDiagnosticsInstalled = AtomicInteger(0)
internal val audienceNetworkRewardHooksInstalled = AtomicInteger(0)
internal val lastGameAdActivityCloseMs = AtomicLong(0L)
internal val lastUnavailableGameAdMs = AtomicLong(0L)
internal val lastGameAdDiagnosticFlowMs = AtomicLong(0L)
internal val scheduledGameAdActivityCloses = Collections.synchronizedMap(WeakHashMap<Activity, Long>())
internal val scheduledAudienceNetworkExitViews = Collections.synchronizedMap(WeakHashMap<View, Long>())
internal val audienceNetworkRewardClassesHooked = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
internal val audienceNetworkRewardAdListeners = Collections.synchronizedMap(WeakHashMap<Any, Any>())
internal val audienceNetworkViewListenerClassesHooked = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
internal val audienceNetworkActivityStateDumps = Collections.synchronizedMap(WeakHashMap<Activity, Long>())

internal data class ResolvedHooks(
    val adKindEnumClass: Class<*>?,
    val listBuilderAppendMethod: Method?,
    val listBuilderFactoryMethod: Method?,
    val pluginPackBuildMethods: List<Method>,
    val instreamBannerEligibilityMethod: Method?,
    val indicatorPillAdEligibilityMethod: Method?,
    val reelsBannerRenderMethods: List<Method>,
    val feedCsrFilterHooks: List<FeedCsrFilterHook>,
    val lateFeedListHooks: List<FeedListSanitizerHook>,
    val storyPoolAddMethods: List<Method>,
    val sponsoredPoolClass: Class<*>?,
    val sponsoredPoolAddMethod: Method?,
    val sponsoredStoryManagerClass: Class<*>?,
    val sponsoredStoryNextMethod: Method?,
    val storyAdProviders: List<StoryAdProviderHooks>,
    val gameAdRequestMethods: List<Method>,
    val gameAdBridgePostMessageMethod: Method?,
    val playableAdActivityOnCreate: Method?,
    val gameAdUiActivityMethods: List<Method>
)

internal fun injectGameAdHidingScript(webView: WebView) {
    webView.post {
        runCatching {
            webView.evaluateJavascript(GAME_AD_WEBVIEW_HIDE_SCRIPT, null)
        }
    }
}

fun installFacebookAdRemover(classLoader: ClassLoader, bridge: DexKitBridge): Boolean {
    return try {
        Logger.i(TAG, "Starting hook install: $BUILD_MARKER")
        val hooks = resolveProjectHooks(classLoader, bridge)
        if (!hooks.hasLoadedSecondaryDexTargets()) {
            Logger.w(TAG, "Facebook secondary dex targets are not loaded yet; deferring hook installation")
            return false
        }
        installFacebook571FeedComponentGuard(classLoader)
        val feedItemInspector = FeedItemInspector(hooks.storyPoolAddMethods.map { it.parameterTypes[0] })
        Logger.i(TAG, "FeedItemInspector accessors ${feedItemInspector.describeAccessors()}")

        if (
            ENABLE_UPSTREAM_REELS_AD_HOOKS &&
            hooks.adKindEnumClass != null &&
            hooks.listBuilderAppendMethod != null
        ) {
            val inspector = AdStoryInspector(hooks.adKindEnumClass)
            hookListBuilderAppend(hooks.listBuilderAppendMethod, inspector)
            hooks.listBuilderFactoryMethod?.let { hookListResultFilter(it, "list factory", inspector) }
            hooks.pluginPackBuildMethods.forEach { hookPluginPackFallback(it, inspector) }
        } else if (ENABLE_UPSTREAM_REELS_AD_HOOKS) {
            Logger.w(TAG, "Upstream Reels targets unresolved; continuing with independent feed ad hooks")
        } else {
            Logger.i(TAG, "Skipped upstream Reels list/plugin hooks to preserve feed Reels carousels")
        }
        hooks.instreamBannerEligibilityMethod?.let { hookInstreamBannerEligibility(it) }
        hooks.indicatorPillAdEligibilityMethod?.let { hookIndicatorPillAdEligibility(it) }
        hooks.reelsBannerRenderMethods.forEach { method ->
            runCatching { hookReelsBannerRender(method) }
                .onFailure {
                    Logger.e(
                        TAG,
                        "Failed to hook Reels banner render ${method.declaringClass.name}.${method.name}",
                        it
                    )
                }
        }
        if (ENABLE_FEED_CSR_FILTER_HOOKS) {
            hooks.feedCsrFilterHooks.forEach { hook ->
                runCatching { hookFeedCsrFilterInput(hook, feedItemInspector) }
                    .onFailure {
                        Logger.e(
                            TAG,
                            "Failed to hook feed CSR filter ${hook.method.declaringClass.name}.${hook.method.name}",
                            it
                        )
                    }
            }
        } else {
            Logger.i(TAG, "Skipped feed CSR filter hooks to isolate feed Reels carousel loading")
        }
        if (ENABLE_LATE_FEED_LIST_HOOKS) {
            hooks.lateFeedListHooks.forEach { hook ->
                runCatching { hookLateFeedListSanitizer(hook, feedItemInspector) }
                    .onFailure {
                        Logger.e(
                            TAG,
                            "Failed to hook late feed list ${hook.method.declaringClass.name}.${hook.method.name}",
                            it
                        )
                    }
            }
        } else {
            Logger.i(TAG, "Skipped late feed list hooks to isolate feed Reels carousel loading")
        }
        if (ENABLE_STORY_POOL_ADD_HOOKS) {
            hooks.storyPoolAddMethods.forEach { method ->
                runCatching { hookStoryPoolAdd(method, feedItemInspector) }
                    .onFailure {
                        Logger.e(TAG, "Failed to hook story pool add ${method.declaringClass.name}.${method.name}", it)
                    }
            }
        } else {
            Logger.i(TAG, "Skipped story pool add hooks to isolate feed Reels carousel loading")
        }
        if (ENABLE_FEED_SPONSORED_POOL_HOOKS) {
            hooks.sponsoredPoolAddMethod?.let { hookSponsoredPoolAdd(it) }
            hooks.sponsoredStoryNextMethod?.let { hookSponsoredStoryNext(it) }
        } else {
            Logger.i(TAG, "Skipped feed sponsored pool hooks to isolate feed Reels carousel loading")
        }
        hooks.storyAdProviders.forEach { provider ->
            runCatching { hookStoryAdProvider(provider) }
                .onFailure {
                    Logger.e(TAG, "Failed to hook story ad source ${provider.providerClass.name}", it)
                }
        }
        if (ENABLE_FEED_SPONSORED_POOL_HOOKS) {
            hooks.sponsoredPoolClass?.let {
                hookSponsoredPoolListMethods(it)
                hookSponsoredPoolResultMethods(it)
            }
            hooks.sponsoredStoryManagerClass?.let {
                hookSponsoredStoryListMethods(it)
            }
        }
        hooks.gameAdRequestMethods.forEach { method ->
            runCatching { hookGameAdRequest(method) }
                .onFailure {
                    Logger.e(
                        TAG,
                        "Failed to hook game ad request ${method.declaringClass.name}.${method.name}",
                        it
                    )
                }
        }
        hooks.gameAdBridgePostMessageMethod?.let { method ->
            runCatching { hookGameAdBridge(method) }
                .onFailure {
                    Logger.e(
                        TAG,
                        "Failed to hook game ad bridge ${method.declaringClass.name}.${method.name}",
                        it
                    )
                }
        }
        hooks.gameAdRequestMethods.firstOrNull()?.declaringClass?.let { bridgeClass ->
            runCatching { hookGameAdResultMethods(bridgeClass) }
                .onFailure {
                    Logger.e(
                        TAG,
                        "Failed to hook game ad result helpers ${bridgeClass.name}",
                        it
                    )
                }
            runCatching { hookGameAdServiceDispatchMethods(bridgeClass) }
                .onFailure {
                    Logger.e(
                        TAG,
                        "Failed to hook game ad service dispatch ${bridgeClass.name}",
                        it
                    )
                }
        }
        if (ENABLE_AUDIENCE_NETWORK_REWARD_FALLBACKS) {
            runCatching { hookAudienceNetworkRewardFallbacks(classLoader) }
                .onFailure { Logger.e(TAG, "Failed to hook Audience Network reward fallbacks", it) }
        } else {
            Logger.i(TAG, "Skipped Audience Network reward fallback hooks for compatibility mode")
        }
        runCatching { hookGameAdSystemDiagnostics(classLoader) }
            .onFailure { Logger.e(TAG, "Failed to hook game ad diagnostics", it) }
        hooks.playableAdActivityOnCreate?.let { method ->
            runCatching { hookPlayableAdActivity(method) }
                .onFailure {
                    Logger.e(
                        TAG,
                        "Failed to hook playable ad activity ${method.declaringClass.name}.${method.name}",
                        it
                    )
                }
        }
        hooks.gameAdUiActivityMethods.forEach { method ->
            runCatching { hookPlayableAdActivity(method) }
                .onFailure {
                    Logger.e(
                        TAG,
                        "Failed to hook game ad activity ${method.declaringClass.name}.${method.name}",
                        it
                    )
                }
        }
        runCatching { hookGlobalGameAdActivityLifecycleFallback() }
            .onFailure { Logger.e(TAG, "Failed to hook global game ad activity lifecycle fallback", it) }
        runCatching { hookGameAdActivityLaunchFallbacks() }
            .onFailure { Logger.e(TAG, "Failed to hook game ad launch fallbacks", it) }
        runCatching { hookGlobalGameAdSurfaceFallbacks() }
            .onFailure { Logger.e(TAG, "Failed to hook global game ad surface fallbacks", it) }
        Logger.i(
            TAG,
            "Installed hooks: append=${if (ENABLE_UPSTREAM_REELS_AD_HOOKS) hooks.listBuilderAppendMethod?.let { "${it.declaringClass.name}.${it.name}" } ?: "none" else "disabled"}" +
                ", factory=${if (ENABLE_UPSTREAM_REELS_AD_HOOKS) hooks.listBuilderFactoryMethod?.let { "${it.declaringClass.name}.${it.name}" } ?: "none" else "disabled"}" +
                ", plugin=${if (ENABLE_UPSTREAM_REELS_AD_HOOKS) hooks.pluginPackBuildMethods.joinToString { "${it.declaringClass.name}.${it.name}" } else "disabled"}" +
                ", bannerState=${hooks.instreamBannerEligibilityMethod?.let { "${it.declaringClass.name}.${it.name}" } ?: "none"}" +
                ", indicatorPill=${hooks.indicatorPillAdEligibilityMethod?.let { "${it.declaringClass.name}.${it.name}" } ?: "none"}" +
                ", reelsBanner=${hooks.reelsBannerRenderMethods.joinToString { "${it.declaringClass.name}.${it.name}" }}" +
                ", feedFilters=${if (ENABLE_FEED_CSR_FILTER_HOOKS) hooks.feedCsrFilterHooks.joinToString { "${it.method.declaringClass.name}.${it.method.name}[${it.listArgIndex}]" } else "disabled"}" +
                ", lateFeed=${if (ENABLE_LATE_FEED_LIST_HOOKS) hooks.lateFeedListHooks.joinToString { "${it.method.declaringClass.name}.${it.method.name}[${it.listArgIndex}]" } else "disabled"}" +
                ", poolAdd=${if (ENABLE_STORY_POOL_ADD_HOOKS) hooks.storyPoolAddMethods.joinToString { "${it.declaringClass.name}.${it.name}" } else "disabled"}" +
                ", feedPoolAdd=${if (ENABLE_FEED_SPONSORED_POOL_HOOKS) hooks.sponsoredPoolAddMethod?.let { "${it.declaringClass.name}.${it.name}" } ?: "none" else "disabled"}" +
                ", feedHolder=${if (ENABLE_FEED_SPONSORED_POOL_HOOKS) hooks.sponsoredStoryManagerClass?.name ?: "none" else "disabled"}" +
                ", feedNext=${if (ENABLE_FEED_SPONSORED_POOL_HOOKS) hooks.sponsoredStoryNextMethod?.let { "${it.declaringClass.name}.${it.name}" } ?: "none" else "disabled"}" +
                ", storyProviders=${hooks.storyAdProviders.joinToString { it.providerClass.name }}" +
                ", gameAds=${hooks.gameAdRequestMethods.joinToString { "${it.declaringClass.name}.${it.name}" }}" +
                ", gameBridge=${hooks.gameAdBridgePostMessageMethod?.let { "${it.declaringClass.name}.${it.name}" } ?: "none"}" +
                ", playableAd=${hooks.playableAdActivityOnCreate?.let { "${it.declaringClass.name}.${it.name}" } ?: "none"}" +
                ", gameAdUi=${hooks.gameAdUiActivityMethods.joinToString { "${it.declaringClass.name}.${it.name}" }}"
        )
        true
    } catch (t: Throwable) {
        Logger.resolutionFailure(TAG, "Failed to install Facebook ad remover hooks", t)
        false
    }
}

internal fun ResolvedHooks.hasLoadedSecondaryDexTargets(): Boolean {
    return listBuilderAppendMethod != null ||
        pluginPackBuildMethods.isNotEmpty() ||
        feedCsrFilterHooks.isNotEmpty() ||
        lateFeedListHooks.isNotEmpty() ||
        storyPoolAddMethods.isNotEmpty() ||
        sponsoredPoolClass != null ||
        sponsoredStoryManagerClass != null ||
        storyAdProviders.isNotEmpty() ||
        gameAdRequestMethods.isNotEmpty()
}

internal fun resolveProjectHooks(classLoader: ClassLoader, bridge: DexKitBridge): ResolvedHooks {
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
        .distinctBy { provider -> provider.providerClass.name }
    val gameAdRequestMethods = resolveGameAdRequestMethods(classLoader, bridge)
    val gameAdBridgePostMessageMethod = resolveGameAdBridgePostMessageMethod(gameAdRequestMethods)
    val playableAdActivityOnCreate = resolvePlayableAdActivityOnCreate(classLoader)
    val gameAdUiActivityMethods = resolveGameAdUiActivityMethods(classLoader)

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
    batchCandidates.forEach { candidates.putIfAbsent(it.name, it) }
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

internal fun resolvePlayableAdActivityOnCreate(classLoader: ClassLoader): Method? {
    val activityClass = runCatching { classLoader.loadClass(NEKO_PLAYABLE_ACTIVITY_CLASS) }.getOrNull() ?: return null
    return activityClass.declaredMethods
        .firstOrNull { method ->
            method.name == "onResume" &&
                method.parameterCount == 0
        }?.apply { isAccessible = true }
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
            isFeedListType(method.parameterTypes[1])
    }?.apply { isAccessible = true }

    return StoryAdProviderHooks(
        providerClass = providerClass,
        mergeMethod = mergeMethod,
        fetchMoreAdsMethod = fetchMoreAdsMethod,
        deferredUpdateMethod = deferredUpdateMethod,
        insertionTriggerMethod = insertionTriggerMethod
    )
}

internal fun installFacebook571FeedResponseFastPath(classLoader: ClassLoader): Boolean {
    val contractTypes = FB571_FEED_ITEM_CONTRACT_CLASSES.mapNotNull { className ->
        runCatching { Class.forName(className, false, classLoader) }.getOrNull()
    }
    val feedItemInspector = FeedItemInspector(contractTypes)
    val hooks = FB571_FEED_CSR_CLASSES.flatMap { className ->
        val targetClass = runCatching {
            Class.forName(className, false, classLoader)
        }.getOrNull() ?: return@flatMap emptyList()

        (targetClass.declaredMethods + targetClass.methods)
            .asSequence()
            .filter { method ->
                method.name == "Ani" &&
                    !Modifier.isAbstract(method.modifiers) &&
                    method.parameterTypes.any(::isFeedListType)
            }
            .mapNotNull { method ->
                val preferredIndex = method.parameterTypes
                    .getOrNull(2)
                    ?.takeIf(::isFeedListType)
                    ?.let { 2 }
                val listArgIndex = preferredIndex
                    ?: method.parameterTypes.indexOfFirst(::isFeedListType).takeIf { it >= 0 }
                    ?: return@mapNotNull null
                FeedCsrFilterHook(method.apply { isAccessible = true }, listArgIndex)
            }
            .toList()
    }.distinctBy { methodHookKey(it.method) }

    var installed = 0
    hooks.forEach { hook ->
        if (hookFeedCsrFilterInput(hook, feedItemInspector)) {
            installed++
        }
    }
    val networkHooks = resolveFacebook571NetworkFeedHooks(classLoader)
    var networkInstalled = 0
    networkHooks.forEach { hook ->
        if (hookLateFeedListSanitizer(hook, feedItemInspector)) {
            networkInstalled++
        }
    }
    val sponsoredPoolMethod = resolveFacebook571SponsoredPoolAdd(classLoader)
    val poolInstalled = sponsoredPoolMethod?.let(::hookSponsoredPoolAdd) == true
    if (installed > 0) {
        Logger.i(
            TAG,
            "Installed FB 571 decoded feed response hooks=$installed " +
                "targets=${hooks.joinToString { "${it.method.declaringClass.name}.${it.method.name}" }} " +
                "accessors=${feedItemInspector.describeAccessors()}"
        )
    }
    if (networkInstalled > 0 || poolInstalled) {
        Logger.i(
            TAG,
            "Installed FB 571 decoded network feed hooks=" +
                "${networkHooks.joinToString { "${it.method.declaringClass.name}.${it.method.name}" }} " +
                "sponsoredPool=${sponsoredPoolMethod?.let { "${it.declaringClass.name}.${it.name}" } ?: "none"}"
        )
    }
    return hooks.isNotEmpty() && networkHooks.isNotEmpty() && sponsoredPoolMethod != null
}

internal fun resolveFacebook571NetworkFeedHooks(classLoader: ClassLoader): List<FeedListSanitizerHook> {
    val targetClass = runCatching {
        Class.forName(FB571_NETWORK_FEED_CLASS, false, classLoader)
    }.getOrNull() ?: return emptyList()

    return (targetClass.declaredMethods + targetClass.methods)
        .asSequence()
        .filter { method ->
            method.name == FB571_NETWORK_FEED_METHOD &&
                !Modifier.isAbstract(method.modifiers) &&
                method.parameterTypes.firstOrNull()?.let(::isFeedListType) == true
        }
        .map { method ->
            FeedListSanitizerHook(method.apply { isAccessible = true }, 0)
        }
        .distinctBy { methodHookKey(it.method) }
        .toList()
}

internal fun resolveFacebook571SponsoredPoolAdd(classLoader: ClassLoader): Method? {
    val targetClass = runCatching {
        Class.forName(FB571_SPONSORED_POOL_CLASS, false, classLoader)
    }.getOrNull() ?: return null

    return (targetClass.declaredMethods + targetClass.methods)
        .firstOrNull { method ->
            method.name == FB571_SPONSORED_POOL_ADD_METHOD &&
                !Modifier.isAbstract(method.modifiers) &&
                method.parameterCount == 1 &&
                method.returnType == Boolean::class.javaPrimitiveType
        }
        ?.apply { isAccessible = true }
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

    Logger.i(TAG, "Hooked $hooked global ad surface fallback method(s)")
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

internal fun resolveGameAdUiActivityMethods(classLoader: ClassLoader): List<Method> {
    val methods = LinkedHashMap<String, Method>()
    val classNames = listOf(
        AUDIENCE_NETWORK_ACTIVITY_CLASS,
        AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS
    )
    classNames.forEach { className ->
        val activityClass = runCatching { classLoader.loadClass(className) }.getOrNull()
        if (activityClass == null) {
            Logger.w(TAG, "Game ad UI class not loadable: $className")
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
            methods.putIfAbsent("${method.name}/${method.parameterCount}/${Modifier.isStatic(method.modifiers)}", method)
        }
    }
    return methods.values.toList()
}

private fun Method.listParameterIndexes(): List<Int> {
    return parameterTypes.mapIndexedNotNull { index, type ->
        index.takeIf { List::class.java.isAssignableFrom(type) }
    }
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
        candidate.findMethod {
            findFirst = true
            matcher {
                returnType = "boolean"
                paramTypes = listOf(null)
            }
        }.firstMethodInstanceOrNull(classLoader)?.let { method ->
            methods.putIfAbsent("${method.declaringClass.name}.${method.name}", method)
        }
    }

    return methods.values.filter { method ->
        !Modifier.isAbstract(method.modifiers) &&
            !method.declaringClass.isInterface &&
            !Modifier.isAbstract(method.declaringClass.modifiers)
    }.toList()
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

internal fun hookListBuilderAppend(method: Method, inspector: AdStoryInspector) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val list = param.args.lastOrNull() as? MutableList<Any?> ?: return
            val removed = filterAdItems(list, inspector)
            if (removed > 0) {
                Logger.i(TAG, "Removed $removed ad item(s) from reels list (append)")
            }
        }
    })
}

internal fun hookPluginPackFallback(method: Method, inspector: AdStoryInspector) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (isMarketplaceAdsPluginPack(param.thisObject)) {
                Logger.i(TAG, "Returning an empty plugin pack for marketplace ads (${method.declaringClass.name})")
                param.result = arrayListOf<Any?>()
                return
            }
            if (inspector.containsAdStory(param.thisObject)) {
                Logger.i(TAG, "Returning an empty plugin pack for an ad-backed story")
                param.result = arrayListOf<Any?>()
            }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            if (isMarketplaceAdsPluginPack(param.thisObject)) return
            val result = param.result as? MutableList<Any?> ?: return
            val removed = filterAdItems(result, inspector)
            if (removed > 0) {
                Logger.i(TAG, "Removed $removed ad plugin item(s)")
            }
        }
    })
}

internal val marketplaceAdsPackCache = ConcurrentHashMap<String, Boolean>()

internal fun isMarketplaceAdsPluginPack(instance: Any): Boolean {
    val className = instance.javaClass.name
    return marketplaceAdsPackCache.getOrPut(className) {
        runCatching {
            instance.javaClass.declaredMethods
                .filter { m ->
                    m.parameterCount == 0 &&
                        m.returnType == String::class.java &&
                        !java.lang.reflect.Modifier.isStatic(m.modifiers)
                }
                .any { m ->
                    m.isAccessible = true
                    val name = m.invoke(instance) as? String
                    name != null && name.contains("Ads", ignoreCase = true)
                }
        }.getOrDefault(false)
    }
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

fun installFacebook571FeedSourceFastPath(classLoader: ClassLoader): Boolean {
    val responseHooksInstalled = installFacebook571FeedResponseFastPath(classLoader)
    val providers = FB571_STORY_AD_SOURCE_CLASSES.mapNotNull { className ->
        val providerClass = runCatching {
            Class.forName(className, false, classLoader)
        }.getOrNull() ?: return@mapNotNull null

        resolveStoryAdProviderHooks(providerClass).takeIf { provider ->
            provider.mergeMethod != null ||
                provider.fetchMoreAdsMethod != null ||
                provider.deferredUpdateMethod != null
        }
    }

    providers.forEach { provider ->
        hookStoryAdProvider(provider)
    }
    if (providers.isNotEmpty()) {
        Logger.i(TAG, "Installed FB 571 fast feed source hooks=${providers.joinToString { it.providerClass.name }}")
    }
    return responseHooksInstalled
}

fun installFacebook571FeedComponentGuard(classLoader: ClassLoader): Boolean {
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

        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val owner = param.thisObject ?: return
                val component = when {
                    componentClass.isInstance(owner) -> owner
                    wrapperClass.isInstance(owner) -> runCatching {
                        wrapperChildField.get(owner)
                    }.getOrNull()?.takeIf(componentClass::isInstance)
                    else -> null
                } ?: return
                val edge = runCatching { edgeField.get(component) }.getOrNull() ?: return
                if (!inspector.isDefinitelySponsoredFeedItem(edge)) return

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
    if (installed > 0) {
        Logger.i(
            TAG,
            "Installed FB 571 sponsored feed component guards=" +
                renderMethods.joinToString { "${it.declaringClass.name}.${it.name}" }
        )
    }
    return true
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

    Logger.i(TAG, "Hooked global game ad activity lifecycle fallback")
}

internal fun hookGameAdActivityLaunchFallbacks() {
    val methods = LinkedHashMap<String, Method>()
    listOf(android.app.Instrumentation::class.java, Activity::class.java, ContextWrapper::class.java).forEach { type ->
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
            Logger.w(TAG, "Failed to hook game ad launch fallback ${method.declaringClass.name}.${method.name}", it)
        }
    }
    Logger.i(TAG, "Hooked $hooked game ad activity launch fallback method(s)")
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
            Logger.i(
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
