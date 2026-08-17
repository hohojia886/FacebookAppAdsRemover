package tn.loukious.facebookappadsremover

import io.github.libxposed.api.XposedModule
import org.luckypray.dexkit.DexKitBridge

fun installFacebookAdRemover(module: XposedModule, classLoader: ClassLoader, bridge: DexKitBridge): Boolean {
    return try {
        Logger.i(TAG, "Starting hook install: $BUILD_MARKER")
        val hooks = resolveHooks(classLoader, bridge)
        if (!hooks.hasLoadedSecondaryDexTargets()) {
            Logger.w(TAG, "Facebook secondary dex targets are not loaded yet; deferring hook installation")
            return false
        }
        installFacebook571FeedComponentGuard(module, classLoader)
        val feedItemInspector = FeedItemInspector(hooks.storyPoolAddMethods.map { it.parameterTypes[0] })
        Logger.i(TAG, "FeedItemInspector accessors ${feedItemInspector.describeAccessors()}")

        if (
            ENABLE_UPSTREAM_REELS_AD_HOOKS &&
            hooks.adKindEnumClass != null &&
            hooks.listBuilderAppendMethod != null
        ) {
            val inspector = AdStoryInspector(hooks.adKindEnumClass)
            hookListBuilderAppend(module, hooks.listBuilderAppendMethod, inspector)
            hooks.listBuilderFactoryMethod?.let { hookListResultFilter(module, it, "list factory", inspector) }
            hooks.pluginPackBuildMethods.forEach { hookPluginPackFallback(module, it, inspector) }
        } else if (ENABLE_UPSTREAM_REELS_AD_HOOKS) {
            Logger.w(TAG, "Upstream Reels targets unresolved; continuing with independent feed ad hooks")
        } else {
            Logger.i(TAG, "Skipped upstream Reels list/plugin hooks to preserve feed Reels carousels")
        }
        hooks.instreamBannerEligibilityMethod?.let { hookInstreamBannerEligibility(module, it) }
        hooks.indicatorPillAdEligibilityMethod?.let { hookIndicatorPillAdEligibility(module, it) }
        hooks.reelsBannerRenderMethods.forEach { method ->
            runCatching { hookReelsBannerRender(module, method) }
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
                runCatching { hookFeedCsrFilterInput(module, hook, feedItemInspector) }
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
                runCatching { hookLateFeedListSanitizer(module, hook, feedItemInspector) }
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
                runCatching { hookStoryPoolAdd(module, method, feedItemInspector) }
                    .onFailure {
                        Logger.e(TAG, "Failed to hook story pool add ${method.declaringClass.name}.${method.name}", it)
                    }
            }
        } else {
            Logger.i(TAG, "Skipped story pool add hooks to isolate feed Reels carousel loading")
        }
        if (ENABLE_FEED_SPONSORED_POOL_HOOKS) {
            hooks.sponsoredPoolAddMethod?.let { hookSponsoredPoolAdd(module, it) }
            hooks.sponsoredStoryNextMethod?.let { hookSponsoredStoryNext(module, it) }
        } else {
            Logger.i(TAG, "Skipped feed sponsored pool hooks to isolate feed Reels carousel loading")
        }
        hooks.storyAdProviders.forEach { provider ->
            runCatching { hookStoryAdProvider(module, provider) }
                .onFailure {
                    Logger.e(TAG, "Failed to hook story ad source ${provider.providerClass.name}", it)
                }
        }
        if (ENABLE_FEED_SPONSORED_POOL_HOOKS) {
            hooks.sponsoredPoolClass?.let {
                val clazz = it.javaClass
                hookSponsoredPoolListMethods(module, clazz)
                hookSponsoredPoolResultMethods(module, clazz)
            }
            hooks.sponsoredStoryManagerClass?.let {
                hookSponsoredStoryListMethods(module, it.javaClass)
            }
        }
        hooks.gameAdRequestMethods.forEach { method ->
            runCatching { hookGameAdRequest(module, method) }
                .onFailure {
                    Logger.e(
                        TAG,
                        "Failed to hook game ad request ${method.declaringClass.name}.${method.name}",
                        it
                    )
                }
        }
        hooks.gameAdBridgePostMessageMethod?.let { method ->
            runCatching { hookGameAdBridge(module, method) }
                .onFailure {
                    Logger.e(
                        TAG,
                        "Failed to hook game ad bridge ${method.declaringClass.name}.${method.name}",
                        it
                    )
                }
        }
        hooks.gameAdRequestMethods.firstOrNull()?.declaringClass?.let { bridgeClass ->
            runCatching { hookGameAdResultMethods(module, bridgeClass) }
                .onFailure {
                    Logger.e(
                        TAG,
                        "Failed to hook game ad result helpers ${bridgeClass.name}",
                        it
                    )
                }
            runCatching { hookGameAdServiceDispatchMethods(module, bridgeClass) }
                .onFailure {
                    Logger.e(
                        TAG,
                        "Failed to hook game ad service dispatch ${bridgeClass.name}",
                        it
                    )
                }
        }
        if (ENABLE_AUDIENCE_NETWORK_REWARD_FALLBACKS) {
            runCatching { hookAudienceNetworkRewardFallbacks(module, classLoader) }
                .onFailure { Logger.e(TAG, "Failed to hook Audience Network reward fallbacks", it) }
        } else {
            Logger.i(TAG, "Skipped Audience Network reward fallback hooks for compatibility mode")
        }
        runCatching { hookGameAdSystemDiagnostics(module, classLoader) }
            .onFailure { Logger.e(TAG, "Failed to hook game ad diagnostics", it) }
        hooks.playableAdActivityOnCreate?.let { method ->
            runCatching { hookPlayableAdActivity(module, method) }
                .onFailure {
                    Logger.e(
                        TAG,
                        "Failed to hook playable ad activity ${method.declaringClass.name}.${method.name}",
                        it
                    )
                }
        }
        hooks.gameAdUiActivityMethods.forEach { method ->
            runCatching { hookPlayableAdActivity(module, method) }
                .onFailure {
                    Logger.e(
                        TAG,
                        "Failed to hook game ad activity ${method.declaringClass.name}.${method.name}",
                        it
                    )
                }
        }
        runCatching { hookGlobalGameAdActivityLifecycleFallback(module) }
            .onFailure { Logger.e(TAG, "Failed to hook global game ad activity lifecycle fallback", it) }
        runCatching { hookGameAdActivityLaunchFallbacks(module) }
            .onFailure { Logger.e(TAG, "Failed to hook game ad launch fallbacks", it) }
        runCatching { hookGlobalGameAdSurfaceFallbacks(module) }
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
                ", feedHolder=${if (ENABLE_FEED_SPONSORED_POOL_HOOKS) hooks.sponsoredStoryManagerClass?.javaClass?.name ?: "none" else "disabled"}" +
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

fun hookInstreamBannerEligibility(module: XposedModule, method: java.lang.reflect.Method) {
    module.hook(method).intercept { chain ->
        logHookHitThrottled("bannerState", method)
        false
    }
}

fun hookIndicatorPillAdEligibility(module: XposedModule, method: java.lang.reflect.Method) {
    module.hook(method).intercept { chain ->
        val pluginSlot = chain.args.getOrNull(2)?.toString() ?: "unknown"
        logHookHitThrottled("indicatorPill", method, "slot=$pluginSlot")
        false
    }
}

fun hookReelsBannerRender(module: XposedModule, method: java.lang.reflect.Method) {
    module.hook(method).intercept { chain ->
        logHookHitThrottled("reelsBanner", method)
        null
    }
}
