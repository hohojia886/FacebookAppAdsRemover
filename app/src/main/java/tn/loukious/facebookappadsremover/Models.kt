package tn.loukious.facebookappadsremover

import org.json.JSONObject
import java.lang.reflect.Method

data class GameAdPayloadSnapshot(
    val target: Any,
    val payload: JSONObject,
    val messageType: String?,
    val timestampMs: Long
)

data class GameAdPromiseSnapshot(
    val payload: JSONObject,
    val messageType: String?,
    val timestampMs: Long
)

data class AudienceNetworkGraphNode(
    val value: Any,
    val path: String,
    val depth: Int
)

data class FeedListSanitizerHook(
    val method: Method,
    val listArgIndex: Int
)

data class FeedCsrFilterHook(
    val method: Method,
    val listArgIndex: Int
)

data class StoryAdProviderHooks(
    val providerClass: Class<*>,
    val mergeMethod: Method?,
    val fetchMoreAdsMethod: Method?,
    val deferredUpdateMethod: Method?,
    val insertionTriggerMethod: Method?
)

data class ResolvedHooks(
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

data class VisibleAdGraphNode(
    val value: Any,
    val path: String,
    val depth: Int
)

data class ExplicitFeedAdCardSignals(
    val hasHideAd: Boolean,
    val hasAdLabel: Boolean,
    val hasSharedLink: Boolean,
    val hasStrongCta: Boolean
)

data class FeedReelCtaAdSignals(
    val hasSharedLink: Boolean,
    val hasSendMessageCta: Boolean,
    val hasReelSurface: Boolean,
    val hasLeadGenPrompt: Boolean
)
