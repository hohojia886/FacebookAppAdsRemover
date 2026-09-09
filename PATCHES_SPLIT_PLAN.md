# Patches.kt File Splitting Plan (For AI Execution)

Source: https://github.com/Loukious/FacebookAppAdsRemover/blob/main/app/src/main/java/tn/loukious/facebookappadsremover/Patches.kt

Package: `tn.loukious.facebookappadsremover`

Total Top-Level Declarations: **443** (All allocated, none omitted)

## Goals and Rules

1. Split into the following **8 files** (within the same package).
2. **Do not alter business logic**; only move top-level declarations, along with any **immediately preceding** contiguous `//` comments and `@` annotations (e.g., `@Volatile`, `@Suppress`).
3. Every file must contain the identical `package tn.loukious.facebookappadsremover` + the complete original `import` list.
4. Top-level `private` modifiers in the original file must be changed to **`internal`**, otherwise cross-file compilation will fail.
5. Shared **mutable state** (`ConcurrentHashMap` / `AtomicInteger` / `AtomicBoolean` / `AtomicLong`, etc.) must all be placed in `PatchShared.kt`.
6. Triple-quoted string constants (especially `GAME_AD_WEBVIEW_HIDE_SCRIPT`) must be moved in their entirety without truncation.
7. `Patches.kt` **must be retained**, serving as the entry point for `installFacebookAdRemover`.

## File Responsibilities Overview

| File | Responsibilities | Declaration Count |
|------|------------------|-------------------|
| `PatchShared.kt` | Constants, ENABLE_* flags, Log, data classes, shared helpers, shared state, ReelsAdClassifier | **99** |
| `FeedInspectors.kt` | `AdStoryInspector` and `FeedItemInspector` classes | **2** |
| `HookResolution.kt` | `resolveHooks` and all DexKit `resolve*` functions (resolution only, no Xposed hooks installed) | **37** |
| `FeedHooks.kt` | News Feed component guard, CSR, late feed list, visible ad trace, feed UI marker hiding | **66** |
| `ReelsStoryHooks.kt` | Reels full-page/banner ads, story pool, sponsored pool, story ad provider | **53** |
| `MarketplaceHooks.kt` | Marketplace rendering/request/response interception and net guard cache | **18** |
| `GameAdsHooks.kt` | Game ads, Audience Network, WebView/surface fallbacks, reward autofix | **166** |
| `Patches.kt` | Only `installFacebookAdRemover` + `hasLoadedSecondaryDexTargets` | **2** |

## Recommended Execution Order (To Minimize Compilation Friction)

1. `PatchShared.kt`
2. `FeedInspectors.kt`
3. `HookResolution.kt`
4. `FeedHooks.kt` / `ReelsStoryHooks.kt` / `MarketplaceHooks.kt` / `GameAdsHooks.kt` (Can be processed in parallel)
5. `Patches.kt` (Last, keeping entry points only)

---

## `PatchShared.kt` (99 items)

| Original Line | Type | Name |
|---------------|------|------|
| 46 | const val | `TAG` |
| 48 | const val | `HOST_PACKAGE` |
| 49 | const val | `BEFORE_SIZE_EXTRA` |
| 50 | const val | `BUILD_MARKER` |
| 51 | const val | `ENABLE_UPSTREAM_REELS_AD_HOOKS` |
| 52 | const val | `ENABLE_FEED_CSR_FILTER_HOOKS` |
| 53 | const val | `ENABLE_LATE_FEED_LIST_HOOKS` |
| 54 | const val | `ENABLE_STORY_POOL_ADD_HOOKS` |
| 55 | const val | `ENABLE_FEED_SPONSORED_POOL_HOOKS` |
| 56 | const val | `ENABLE_FEED_UI_MARKER_FALLBACKS` |
| 57 | const val | `ENABLE_GAME_AD_AUTOFIX` |
| 58 | const val | `ENABLE_GAME_AD_DIAGNOSTICS` |
| 59 | const val | `ENABLE_BROAD_HANDLER_GAME_AD_DIAGNOSTICS` |
| 60 | const val | `ENABLE_AUDIENCE_NETWORK_VIEW_DIAGNOSTICS` |
| 61 | const val | `ENABLE_AUDIENCE_NETWORK_REWARD_FALLBACKS` |
| 62 | const val | `GAME_AD_DIAG_LOG_LIMIT` |
| 63 | const val | `GAME_AD_DIAG_TEXT_LIMIT` |
| 64 | const val | `GAME_AD_DIAG_FLOW_WINDOW_MS` |
| 65 | const val | `AUDIENCE_NETWORK_STATE_DUMP_LIMIT` |
| 66 | const val | `GRAPHQL_FEED_UNIT_EDGE_CLASS` |
| 67 | const val | `GRAPHQL_MULTI_ADS_FEED_UNIT_CLASS` |
| 68 | const val | `GRAPHQL_QUICK_PROMO_FEED_UNIT_CLASS` |
| 70 | const val | `AUDIENCE_NETWORK_ACTIVITY_CLASS` |
| 71 | const val | `AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS` |
| 72 | const val | `NEKO_PLAYABLE_ACTIVITY_CLASS` |
| 73 | const val | `GAME_AD_REJECTION_MESSAGE` |
| 74 | const val | `GAME_AD_REJECTION_CODE` |
| 75 | const val | `GAME_AD_UNAVAILABLE_MESSAGE` |
| 76 | const val | `GAME_AD_UNAVAILABLE_CODE` |
| 77 | const val | `GAME_AD_SUCCESS_INSTANCE_PREFIX` |
| 78 | const val | `GAME_AD_RECENT_WINDOW_MS` |
| 79 | const val | `GAME_AD_PROMISE_WINDOW_MS` |
| 80 | const val | `AUDIENCE_NETWORK_REWARD_CLOSE_RETRY_WINDOW_MS` |
| 81 | const val | `HOOK_HIT_LOG_EVERY` |
| 83 | const val | `GAME_AD_WEBVIEW_HIDE_SCRIPT` |
| 131 | val | `GAME_AD_MESSAGE_TYPES` |
| 143 | val | `GAME_AD_AUTOFIX_MESSAGE_TYPES` |
| 150 | val | `GAME_AD_REWARD_MESSAGE_TYPES` |
| 155 | val | `GAME_AD_ACTIVITY_CLASS_NAMES` |
| 161 | val | `HARD_BLOCKED_GAME_AD_ACTIVITY_CLASS_NAMES` |
| 165 | val | `AUDIENCE_NETWORK_REWARD_COMPLETION_METHOD_NAMES` |
| 178 | val | `hookHitCounters` |
| 204 | val | `feedWrapperCandidates` |
| 212 | data class | `GameAdPayloadSnapshot` |
| 219 | data class | `GameAdPromiseSnapshot` |
| 225 | data class | `AudienceNetworkGraphNode` |
| 231 | val | `GAME_AD_METHOD_TAGS` |
| 239 | val | `FEED_AD_CATEGORY_VALUES` |
| 248 | val | `FEED_COLLECTION_AD_CATEGORY_VALUES` |
| 256 | val | `FEED_SAFE_CONTAINER_CATEGORY_VALUES` |
| 261 | val | `FEED_AD_SIGNAL_TOKENS` |
| 281 | val | `STORY_AD_PROVIDER_TAGS` |
| 289 | data class | `NamedHookTarget` |
| 299 | const val | `FEED_UNIT_COMPONENT_NAME` |
| 300 | const val | `FEED_WRAPPER_COMPONENT_NAME` |
| 304 | val | `FEED_RENDER_PARAMETER_COUNTS` |
| 310 | val | `FEED_SURFACE_AD_MARKER_TOKENS` |
| 319 | val | `EXPLICIT_FEED_CARD_AD_MARKER_TOKENS` |
| 326 | val | `EXPLICIT_FEED_AD_CTA_TOKENS` |
| 339 | val | `FEED_REEL_CTA_AD_MARKER_TOKENS` |
| 350 | val | `REELS_SHOPPING_STICKER_CTA_TOKENS` |
| 356 | val | `REELS_AD_SIGNAL_TOKENS` |
| 370 | object | `Log` |
| 395 | data class | `FeedListSanitizerHook` |
| 400 | data class | `FeedCsrFilterHook` |
| 405 | data class | `StoryAdProviderHooks` |
| 413 | data class | `ResolvedHooks` |
| 1063 | ext fun | `Collection.firstMethodInstanceOrNull` |
| 1073 | fun | `findClassesByZeroArgStringTags` |
| 1941 | const val | `FEED_GUARD_CACHE_FILE` |
| 2090 | fun | `declaresFeedStoryCategoryAccessor` |
| 2174 | data class | `VisibleAdGraphNode` |
| 2254 | fun | `invokeMethodByName` |
| 2374 | fun | `allFieldsInHierarchy` |
| 2384 | fun | `allMethodsInHierarchy` |
| 2400 | fun | `isFeedListType` |
| 2408 | fun | `isConcreteFeedListType` |
| 2450 | ext fun | `Method.listParameterIndexes` |
| 2907 | val | `AD_CLASSIFICATION_VALUES` |
| 2997 | const val | `REELS_GUARD_CACHE_FILE` |
| 3025 | var | `reelsGuardModelInterfaceSpecs` |
| 3028 | var | `reelsGuardEnumClassName` |
| 3411 | const val | `MARKETPLACE_NET_CACHE_FILE` |
| 3513 | val | `MARKETPLACE_FEED_AD_SKIP_FLAGS` |
| 3951 | class | `ReelsAdClassifier` |
| 4132 | var | `reelsGuardCachedInterfaces` |
| 4135 | var | `reelsGuardCachedRenderables` |
| 4138 | var | `reelsGuardCachedShoppingRenderables` |
| 4141 | var | `reelsGuardCachedPagerPush` |
| 4144 | var | `reelsGuardCachedSnapshots` |
| 4147 | var | `reelsGuardCacheParsed` |
| 4674 | fun | `methodHookKey` |
| 4746 | fun | `logHookHitThrottled` |
| 6080 | fun | `methodSignature` |
| 6135 | fun | `shortObjectLabel` |
| 6139 | fun | `byteArrayHexPreview` |
| 6143 | fun | `byteArrayAsciiPreview` |
| 7606 | data class | `ExplicitFeedAdCardSignals` |
| 7766 | data class | `FeedReelCtaAdSignals` |

---

## `FeedInspectors.kt` (2 items)

| Original Line | Type | Name |
|---------------|------|------|
| 435 | class | `AdStoryInspector` |
| 634 | class | `FeedItemInspector` |

---

## `HookResolution.kt` (37 items)

| Original Line | Type | Name |
|---------------|------|------|
| 1328 | fun | `resolveHooks` |
| 1458 | fun | `logMissingHooks` |
| 1509 | fun | `resolveAdKindEnumClass` |
| 1535 | fun | `resolveListBuilderClass` |
| 1578 | fun | `resolvePluginPackClasses` |
| 1623 | fun | `resolveSponsoredPoolClass` |
| 1648 | fun | `resolveSponsoredStoryManagerClass` |
| 1674 | fun | `resolveStoryAdProviderClasses` |
| 1695 | fun | `resolveStoryAdProviderHooks` |
| 1710 | fun | `resolveStoryAdProviderHooks` |
| 2045 | fun | `resolveLithoLayoutContextType` |
| 2067 | fun | `resolveFeedEdgeField` |
| 2076 | fun | `resolveWrapperChildField` |
| 2412 | fun | `resolveAppendMethod` |
| 2424 | fun | `resolveFactoryMethod` |
| 2440 | fun | `resolveListBuilderMethods` |
| 2456 | fun | `scoreAppendMethod` |
| 2468 | fun | `scoreFactoryMethod` |
| 2479 | fun | `resolvePluginPackMethod` |
| 2491 | fun | `resolveFeedCsrFilterMethods` |
| 2547 | fun | `resolveLateFeedListHooks` |
| 2625 | fun | `resolveStoryPoolAddMethods` |
| 2659 | fun | `resolveInstreamBannerEligibilityMethod` |
| 2700 | fun | `resolveIndicatorPillAdEligibilityMethod` |
| 2725 | fun | `resolveReelsBannerRenderMethods` |
| 2749 | fun | `resolveLithoRenderMethod` |
| 3924 | fun | `resolveWrapperListField` |
| 4048 | fun | `resolveReelsAdClassifier` |
| 4289 | fun | `resolveReelsGuardMethodSpec` |
| 4404 | fun | `resolveSponsoredPoolAddMethod` |
| 4416 | fun | `resolveSponsoredStoryNextMethod` |
| 4431 | fun | `resolveGameAdRequestMethods` |
| 4457 | fun | `resolveGameAdBridgePostMessageMethod` |
| 4466 | fun | `resolvePlayableAdActivityOnCreate` |
| 4475 | fun | `resolveGameAdUiActivityMethods` |
| 4503 | fun | `resolveGameAdUiActivityMethodsFallback` |
| 8052 | fun | `resolveGameAdResolveMethod` |

---

## `FeedHooks.kt` (66 items)

| Original Line | Type | Name |
|---------------|------|------|
| 199 | val | `feedCsrMethodsHooked` |
| 200 | val | `lateFeedMethodsHooked` |
| 202 | val | `feedComponentMethodsHooked` |
| 203 | val | `feedComponentCandidates` |
| 205 | val | `feedGuardResolvedComponentNames` |
| 206 | val | `feedGuardResolvedWrapperNames` |
| 207 | val | `visibleAdTraceInstalled` |
| 208 | val | `visibleAdViewsTraced` |
| 209 | val | `survivingFeedAdTraceCount` |
| 210 | val | `survivingFeedTypeContractsLogged` |
| 306 | fun | `survivingFeedTypeClassNames` |
| 1749 | val | `feedWrapperChildClassesLogged` |
| 1751 | fun | `logWrapperChildClass` |
| 1759 | fun | `installFacebookFeedComponentGuard` |
| 1855 | val | `lithoComponentNameFields` |
| 1857 | fun | `lithoComponentNameOf` |
| 1890 | fun | `registerFeedGuardCandidate` |
| 1906 | fun | `registerLithoComponentClasses` |
| 1929 | fun | `discoverFeedComponentGuardCandidates` |
| 1943 | fun | `feedGuardCacheModuleKey` |
| 1947 | fun | `registerCachedGuardClasses` |
| 1966 | var | `feedGuardCachedComponentNames` |
| 1969 | var | `feedGuardCachedWrapperNames` |
| 1971 | fun | `loadCachedFeedGuardCandidates` |
| 2010 | fun | `saveFeedGuardCandidateCache` |
| 2032 | fun | `lithoLayoutMethods` |
| 2104 | fun | `logFacebookSurvivingFeedTypeContracts` |
| 2112 | fun | `logSurvivingFeedTypeContract` |
| 2148 | fun | `installFacebookVisibleAdTrace` |
| 2180 | fun | `traceVisibleFacebookFeedAd` |
| 2236 | fun | `findRecyclerViewAncestor` |
| 2267 | fun | `traceVisibleAdObjectGraph` |
| 2339 | fun | `isTraceableFeedObject` |
| 2344 | fun | `shouldSkipVisibleAdTraceType` |
| 2355 | fun | `isVisibleAdTraceString` |
| 3922 | val | `wrapperListFieldCache` |
| 4562 | fun | `hookListResultFilter` |
| 4620 | fun | `hookFeedCsrFilterInput` |
| 4680 | fun | `hookLateFeedListSanitizer` |
| 7359 | fun | `shouldScheduleFeedRowSweep` |
| 7364 | fun | `scheduleFeedRowSweep` |
| 7447 | fun | `hideLikelyExplicitFeedAdCardContainer` |
| 7496 | fun | `traceSurvivingFeedAdSourceOnce` |
| 7532 | fun | `hideLikelyFeedReelCtaAdContainer` |
| 7598 | fun | `shouldUseFeedMarkerCardTarget` |
| 7602 | fun | `shouldUseExplicitFeedMarkerCardTarget` |
| 7663 | fun | `isSafeFeedMarkerCardCandidate` |
| 7683 | fun | `isLikelyExplicitFeedAdCardContainer` |
| 7690 | fun | `isLikelyExplicitFeedAdCardContainer` |
| 7714 | fun | `collectExplicitFeedAdCardSignals` |
| 7773 | fun | `isLikelyFeedReelCtaAdContainer` |
| 7792 | fun | `collectFeedReelCtaAdSignals` |
| 7867 | fun | `isPotentialFeedAdMarkerView` |
| 7872 | fun | `isPotentialExplicitFeedAdMarkerView` |
| 7877 | fun | `isPotentialFeedReelCtaAdMarkerView` |
| 7882 | fun | `isAnyAdMarkerText` |
| 7894 | fun | `isFeedAdMarkerText` |
| 7900 | fun | `isExplicitFeedAdMarkerText` |
| 7906 | fun | `isExplicitFeedAdCtaText` |
| 7912 | fun | `isFeedReelCtaAdMarkerText` |
| 8479 | fun | `filterAdItems` |
| 8491 | fun | `buildImmutableListLike` |
| 8520 | fun | `replaceFeedItemsInResult` |
| 8527 | fun | `rebuildFeedResult` |
| 8565 | fun | `extractFeedItemsFromResult` |
| 8578 | fun | `logFeedItems` |

---

## `ReelsStoryHooks.kt` (53 items)

| Original Line | Type | Name |
|---------------|------|------|
| 198 | val | `storyAdProviderClassesHooked` |
| 201 | val | `sponsoredPoolMethodsHooked` |
| 2774 | val | `reelsAdDiagnosticsInstalled` |
| 2775 | val | `reelsAdDiagnosticsLogged` |
| 2777 | fun | `installReelsAdDiagnostics` |
| 2845 | fun | `installReelsInstreamAdBlock` |
| 2913 | val | `reelsNonAdClassificationSeen` |
| 2922 | fun | `installReelsAdListFilters` |
| 3003 | val | `reelsRenderHookedMethods` |
| 3009 | val | `reelsSnapshotMemo` |
| 3015 | val | `reelsGuardRenderableNames` |
| 3020 | val | `reelsShoppingRenderableNames` |
| 3021 | val | `reelsGuardPagerPushSpecs` |
| 3022 | val | `reelsGuardSnapshotSpecs` |
| 3030 | fun | `installReelsViewerAdRenderBlock` |
| 3116 | fun | `hookReelsAdRenderable` |
| 3197 | fun | `resolveShoppingPayloadType` |
| 3217 | fun | `hookReelsShoppingRenderable` |
| 3611 | fun | `installReelsCollectionFilter` |
| 3640 | fun | `hookReelsCollectionSnapshot` |
| 3685 | val | `reelsClassificationProbeHits` |
| 3686 | val | `reelsSponsoredLabelHits` |
| 3692 | fun | `installReelsAdClassificationProbe` |
| 3731 | fun | `installReelsSponsoredLabelProbe` |
| 3777 | fun | `installReelsRtiAdBlock` |
| 3835 | fun | `hookReelsPagerListPush` |
| 4110 | fun | `saveReelsGuardCache` |
| 4151 | val | `reelsGuardResolvedSpecs` |
| 4155 | val | `reelsFullPassInstalled` |
| 4160 | fun | `installReelsGuardFromCache` |
| 4274 | fun | `buildCachedReelsClassifier` |
| 4304 | fun | `installReelsAdPipelineProbes` |
| 4376 | fun | `describeReelsAdModelChain` |
| 4526 | fun | `hookListBuilderAppend` |
| 4574 | fun | `hookPluginPackFallback` |
| 4714 | fun | `hookStoryPoolAdd` |
| 4754 | fun | `hookInstreamBannerEligibility` |
| 4763 | fun | `hookIndicatorPillAdEligibility` |
| 4773 | fun | `hookReelsBannerRender` |
| 7918 | fun | `isReelsShoppingStickerMarkerText` |
| 7934 | fun | `hideReelsShoppingSticker` |
| 8298 | fun | `hookSponsoredPoolAdd` |
| 8311 | fun | `hookSponsoredStoryNext` |
| 8320 | fun | `hookSponsoredStoryListMethods` |
| 8341 | fun | `isSponsoredStoryListMethod` |
| 8355 | fun | `buildEmptyListReturn` |
| 8370 | fun | `hookStoryAdsMerge` |
| 8382 | fun | `hookStoryAdsNoOp` |
| 8391 | fun | `hookStoryAdProvider` |
| 8418 | fun | `hookSponsoredPoolListMethods` |
| 8438 | fun | `hookSponsoredPoolResultMethods` |
| 8463 | fun | `isSponsoredResultCarrier` |
| 8469 | fun | `buildSponsoredEmptyResult` |

---

## `MarketplaceHooks.kt` (18 items)

| Original Line | Type | Name |
|---------------|------|------|
| 3246 | val | `marketplaceAdRenderHookedMethods` |
| 3249 | fun | `installMarketplaceAdRenderBlock` |
| 3271 | fun | `hookMarketplaceAdRenderable` |
| 3321 | fun | `installMarketplaceAdsQueryBlock` |
| 3346 | val | `marketplaceSendRequestHookedMethods` |
| 3354 | var | `marketplaceNetResolvedClassName` |
| 3356 | fun | `hookMarketplaceSendRequest` |
| 3413 | fun | `saveMarketplaceNetGuardCache` |
| 3432 | fun | `installMarketplaceNetGuardFromCache` |
| 3463 | fun | `requestBodyOf` |
| 3485 | fun | `rewriteMarketplaceFeedRequestVariables` |
| 3523 | fun | `readableMapWithString` |
| 3544 | val | `marketplaceDiagnosedQueries` |
| 3546 | val | `marketplaceQueryNameRegex` |
| 3549 | val | `marketplaceFormQueryIdRegex` |
| 3556 | fun | `installMarketplaceFeedResponseFilter` |
| 4599 | val | `marketplaceAdsPackCache` |
| 4601 | fun | `isMarketplaceAdsPluginPack` |

---

## `GameAdsHooks.kt` (166 items)

| Original Line | Type | Name |
|---------------|------|------|
| 173 | val | `gameAdInstanceIds` |
| 174 | val | `gameAdInstanceTypes` |
| 175 | val | `gameAdPromiseSnapshots` |
| 176 | val | `recentGameAdTargets` |
| 177 | val | `recentGameAdPayloads` |
| 179 | val | `gameAdSurfaceHooksInstalled` |
| 180 | val | `gameAdActivityLifecycleHookInstalled` |
| 181 | val | `gameAdResultHookedClasses` |
| 182 | val | `gameAdServiceDispatchHookedClasses` |
| 183 | val | `gameAdSystemDiagnosticsInstalled` |
| 184 | val | `gameAdDynamicDiagnosticsInstalled` |
| 185 | val | `audienceNetworkViewDiagnosticsInstalled` |
| 186 | val | `audienceNetworkRewardHooksInstalled` |
| 187 | val | `lastGameAdActivityCloseMs` |
| 188 | val | `lastUnavailableGameAdMs` |
| 189 | val | `lastGameAdDiagnosticFlowMs` |
| 190 | val | `gameAdDiagnosticLogCount` |
| 191 | val | `scheduledGameAdActivityCloses` |
| 192 | val | `audienceNetworkRewardClassesHooked` |
| 193 | val | `audienceNetworkRewardAdListeners` |
| 194 | val | `gameAdDiagnosticClassesHooked` |
| 195 | val | `gameAdDiagnosticClassesLogged` |
| 196 | val | `audienceNetworkViewListenerClassesHooked` |
| 197 | val | `audienceNetworkActivityStateDumps` |
| 2226 | fun | `findViewAncestor` |
| 3804 | fun | `hookVoidMethodsByString` |
| 4782 | fun | `hookGameAdRequest` |
| 4828 | fun | `hookGameAdBridge` |
| 4887 | val | `gameAdJavascriptInterfaceHookInstalled` |
| 4888 | val | `gameAdBridgeEntryMethodsHooked` |
| 4890 | fun | `installGameAdJavascriptInterfaceBridgeHook` |
| 4918 | fun | `hookGameAdBridgeObject` |
| 4949 | val | `gameAdScriptHooksInstalled` |
| 4950 | val | `gameAdScriptDiagnostics` |
| 4952 | fun | `installGameAdScriptResultHooks` |
| 4982 | fun | `hookWebViewScriptDelivery` |
| 4997 | fun | `rewriteGameAdDeliveryIfNeeded` |
| 5018 | fun | `logGameAdDeliveryDiagnostic` |
| 5030 | fun | `rewritePromiseJsonInDelivery` |
| 5074 | fun | `extractBalancedJson` |
| 5101 | fun | `forceSuccessDeep` |
| 5129 | fun | `hookGameAdResultMethods` |
| 5274 | fun | `hookGameAdServiceDispatchMethods` |
| 5333 | fun | `hookGameAdSystemDiagnostics` |
| 5350 | fun | `hookMessengerSendDiagnostics` |
| 5384 | fun | `hookHandlerMessageDiagnostics` |
| 5425 | fun | `hookActivityResultDiagnostics` |
| 5499 | fun | `hookAudienceNetworkViewDiagnostics` |
| 5568 | fun | `dumpAudienceNetworkActivityState` |
| 5594 | fun | `dumpAudienceNetworkIntentExtras` |
| 5606 | fun | `dumpAudienceNetworkViewState` |
| 5643 | fun | `dumpAudienceNetworkObjectGraph` |
| 5703 | fun | `tryHookAudienceNetworkDiagnosticObjectClass` |
| 5712 | fun | `tryHookAudienceNetworkViewListenerClass` |
| 5761 | fun | `isAudienceNetworkViewListenerDiagnosticMethod` |
| 5773 | fun | `shouldLogAudienceNetworkListenerCall` |
| 5784 | fun | `shouldLogAudienceNetworkViewDiagnostic` |
| 5802 | fun | `shouldDescribeAudienceNetworkViewInTree` |
| 5810 | fun | `describeAudienceNetworkView` |
| 5830 | fun | `viewIdLabel` |
| 5835 | fun | `audienceNetworkViewMarker` |
| 5847 | fun | `audienceNetworkParentPath` |
| 5859 | fun | `contextActivityForView` |
| 5870 | fun | `findViewOnClickListener` |
| 5874 | fun | `findViewOnTouchListener` |
| 5878 | fun | `findViewListenerInfoField` |
| 5891 | fun | `shouldQueueAudienceNetworkDiagnosticObject` |
| 5920 | fun | `isPotentialAudienceNetworkAppClass` |
| 5927 | fun | `shouldHookAudienceNetworkListenerClass` |
| 5932 | fun | `audienceNetworkInterestingMethodsSummary` |
| 5947 | ext fun | `String.hasAudienceNetworkViewSignal` |
| 5966 | fun | `hookDynamicGameAdClassDiagnostics` |
| 5998 | fun | `tryHookGameAdDiagnosticClass` |
| 6041 | fun | `logGameAdDiagnosticClass` |
| 6059 | fun | `markGameAdDiagnosticFlow` |
| 6065 | fun | `isRecentGameAdDiagnosticFlow` |
| 6070 | fun | `logGameAdDiagnostic` |
| 6084 | fun | `formatDiagArgs` |
| 6090 | fun | `formatDiagThrowable` |
| 6095 | fun | `formatDiagValue` |
| 6150 | fun | `truncateDiag` |
| 6154 | fun | `shouldLogGameAdMessage` |
| 6160 | fun | `shouldLogGameAdActivityDiagnostic` |
| 6166 | fun | `shouldLogGameAdDiagnosticCall` |
| 6173 | fun | `isGameAdDiagnosticMethod` |
| 6188 | fun | `isGameAdDiagnosticClassName` |
| 6205 | fun | `isGameAdInterestingActivity` |
| 6214 | fun | `isGameAdDiagnosticValue` |
| 6241 | ext fun | `String.hasGameAdSignal` |
| 6263 | fun | `hookAudienceNetworkRewardFallbacks` |
| 6301 | fun | `tryHookAudienceNetworkRewardClass` |
| 6400 | fun | `isAudienceNetworkRewardRelevantClass` |
| 6413 | fun | `isAudienceNetworkRewardShowMethod` |
| 6424 | fun | `isAudienceNetworkRewardLoadMethod` |
| 6431 | fun | `isAudienceNetworkRewardListenerRegistrationMethod` |
| 6442 | fun | `rememberAudienceNetworkRewardListeners` |
| 6463 | fun | `isAudienceNetworkRewardListenerObject` |
| 6483 | fun | `audienceNetworkInterfacesFor` |
| 6496 | fun | `completeAudienceNetworkRewardObject` |
| 6520 | fun | `findAudienceNetworkRewardListeners` |
| 6569 | fun | `invokeAudienceNetworkRewardListenerCallbacks` |
| 6604 | fun | `audienceNetworkCallbackArgs` |
| 6615 | fun | `audienceNetworkRewardMethodsFor` |
| 6630 | fun | `inferGameAdMessageType` |
| 6646 | fun | `dispatchPostResolveGameAdSignals` |
| 6657 | fun | `rememberGameAdPayload` |
| 6683 | fun | `completeRecentGameAdRequests` |
| 6713 | fun | `shouldConvertGameAdRejectToSuccess` |
| 6723 | fun | `shouldAutofixGameAdMessage` |
| 6730 | fun | `shouldForceGameAdSuccess` |
| 6736 | fun | `hasRewardGameAdSignal` |
| 6755 | fun | `isRecentUnavailableGameAd` |
| 6760 | fun | `isRecentGameAdActivityClose` |
| 6765 | fun | `gameAdPromiseTypeFromReason` |
| 6778 | fun | `hasRecentGameAdRequest` |
| 6786 | fun | `hookPlayableAdActivity` |
| 6796 | fun | `hookGlobalGameAdActivityLifecycleFallback` |
| 6822 | fun | `hookGameAdActivityLaunchFallbacks` |
| 6856 | fun | `hookGameAdActivityLaunchMethod` |
| 6891 | fun | `shouldBlockGameAdActivityLaunch` |
| 6897 | fun | `resolveBlockedGameAdActivity` |
| 6905 | fun | `handleGameAdActivity` |
| 6932 | fun | `scheduleAudienceNetworkRewardClose` |
| 6978 | fun | `clickLikelyAudienceNetworkCloseButton` |
| 6993 | fun | `collectAudienceNetworkCloseCandidates` |
| 7016 | fun | `audienceNetworkCloseCandidateScore` |
| 7051 | fun | `isTopRightSmallControl` |
| 7065 | fun | `finishGameAdActivity` |
| 7078 | fun | `buildGameAdActivityResultIntent` |
| 7084 | fun | `forceAudienceNetworkRewardCompletion` |
| 7121 | fun | `invokeAudienceNetworkRewardCompletionMethods` |
| 7144 | fun | `audienceNetworkFieldsFor` |
| 7163 | fun | `audienceNetworkMethodsFor` |
| 7181 | fun | `shouldQueueAudienceNetworkObject` |
| 7194 | fun | `shouldTraverseAudienceNetworkObject` |
| 7214 | fun | `installGlobalAdSurfaceFallbacksEarly` |
| 7221 | fun | `hookGlobalGameAdSurfaceFallbacks` |
| 7350 | fun | `scheduleGameAdSurfaceSweep` |
| 7373 | fun | `sweepGameAdSurface` |
| 7400 | fun | `injectGameAdHidingScript` |
| 7408 | fun | `hideLikelyAdContainer` |
| 7458 | fun | `hideResolvedAdSurfaceTarget` |
| 7556 | fun | `resolveLikelyAdContainerTarget` |
| 7613 | fun | `resolveLikelyExplicitFeedAdCardTarget` |
| 7638 | fun | `resolveLikelyFeedMarkerCardTarget` |
| 7750 | fun | `resolveLikelyFeedReelCtaAdContainerTarget` |
| 7846 | fun | `isPotentialNativeGameAdView` |
| 7854 | fun | `collectViewMarkerTexts` |
| 7886 | fun | `isGameAdMarkerText` |
| 7970 | fun | `isLikelyBannerSized` |
| 7981 | fun | `resolveGameAdPayload` |
| 8006 | fun | `rejectGameAdPayload` |
| 8075 | fun | `resolveGameAdBridgeRejectMethod` |
| 8087 | fun | `resolveGameAdRejectMethod` |
| 8097 | fun | `dispatchGameEvent` |
| 8111 | fun | `resolveGameEventDispatchMethod` |
| 8122 | fun | `resolveGameEventValue` |
| 8143 | fun | `extractGameAdContent` |
| 8148 | fun | `buildGameAdPayloadFromServiceBundle` |
| 8156 | fun | `bundleToJsonObject` |
| 8167 | fun | `putJsonCompatibleValue` |
| 8180 | fun | `buildGameAdSuccessPayload` |
| 8226 | fun | `forceGameAdSuccessResult` |
| 8258 | fun | `copyJsonObject` |
| 8268 | fun | `resolveGameAdInstanceId` |
| 8280 | fun | `extractPromiseId` |

---

## `Patches.kt` (2 items)

| Original Line | Type | Name |
|---------------|------|------|
| 1097 | fun | `installFacebookAdRemover` |
| 1316 | ext fun | `ResolvedHooks.hasLoadedSecondaryDexTargets` |

## Cross-file Dependencies (Must Read)

### HookResolution → PatchShared

Must be marked as `internal`:

- `firstMethodInstanceOrNull`
- `isFeedListType` / `isConcreteFeedListType`
- `declaresFeedStoryCategoryAccessor`
- `Method.listParameterIndexes`
- `findClassesByZeroArgStringTags`
- `ReelsAdClassifier` (and reelsGuard* cache variables)

### Each Hook Module → PatchShared

- `logHookHitThrottled` + `hookHitCounters` + `HOOK_HIT_LOG_EVERY`
- `methodHookKey` / `methodSignature` / `Log` / `TAG` / 各 `ENABLE_*` flags
- Area-specific `*Hooked` / `*Installed` / `*Candidates` state variables

### Functions Called by `Patches.kt` Entry Point (Must Be Visible Across Files)

- `resolveHooks` (`HookResolution`)
- `installFacebookFeedComponentGuard`, `installFacebookVisibleAdTrace`, etc. (`FeedHooks`)
- Reels / Story / Sponsored related `hook*` (`ReelsStoryHooks`)
- Marketplace `installMarketplace*` (`MarketplaceHooks`)
- Game / AN / surface `hook*` / `install*` (`GameAdsHooks`)

### Ambiguous Boundaries (Decided in this plan)

| Symbol Type | Placed In |
|-------------|-----------|
| Feed-specific marker hide / trace | `FeedHooks.kt` |
| Generic `hideLikelyAdContainer`, surface sweep, WebView inject | `GameAdsHooks.kt` |
| Sponsored pool / story ad provider | `ReelsStoryHooks.kt` |
| Marketplace request body helpers | `MarketplaceHooks.kt` |

## Troubleshooting Compilation Errors

| Error | Resolution |
|-------|------------|
| `Cannot access ... private in file` | Change the declaration to `internal`, or move it to `PatchShared.kt` |
| `Unresolved reference` | Check if the item was missed, or is still marked `private` |
| `@Volatile` on immutable | Ensure `@Volatile` is applied only to `var` and matches the declaration |
| Unterminated string / JS syntax error | Verify if `GAME_AD_WEBVIEW_HIDE_SCRIPT` was truncated |

## Content Not Listed in Tables But Must Be Copied

- Each output file: complete `package` + full original `import` list
- Preceding comments and annotations: attached to the **following** declaration
