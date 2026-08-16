package tn.loukious.facebookappadsremover

internal const val TAG = "FacebookAppAdsRemover"
internal const val HOST_PACKAGE = "com.facebook.katana"
internal const val BEFORE_SIZE_EXTRA = "facebook_ads_before_size"
internal const val BUILD_MARKER = "fb571_sponsored_component_guard_v3_2026_07_23"

internal const val ENABLE_UPSTREAM_REELS_AD_HOOKS = true
internal const val ENABLE_FEED_CSR_FILTER_HOOKS = true
internal const val ENABLE_LATE_FEED_LIST_HOOKS = true
internal const val ENABLE_STORY_POOL_ADD_HOOKS = true
internal const val ENABLE_FEED_SPONSORED_POOL_HOOKS = true
internal const val ENABLE_FEED_UI_MARKER_FALLBACKS = false
internal const val ENABLE_GAME_AD_AUTOFIX = true
internal val ENABLE_GAME_AD_DIAGNOSTICS = BuildConfig.DEBUG
internal const val ENABLE_BROAD_HANDLER_GAME_AD_DIAGNOSTICS = false
internal const val ENABLE_AUDIENCE_NETWORK_VIEW_DIAGNOSTICS = false
internal const val ENABLE_AUDIENCE_NETWORK_REWARD_FALLBACKS = true
internal const val ENABLE_AUDIENCE_NETWORK_AUTO_EXIT_WHEN_READY = true

internal const val GAME_AD_DIAG_LOG_LIMIT = 8_000
internal const val GAME_AD_DIAG_TEXT_LIMIT = 1_200
internal const val GAME_AD_DIAG_FLOW_WINDOW_MS = 2 * 60_000L
internal const val AUDIENCE_NETWORK_STATE_DUMP_LIMIT = 120

internal const val GRAPHQL_FEED_UNIT_EDGE_CLASS = "com.facebook.graphql.model.GraphQLFeedUnitEdge"
internal const val GRAPHQL_MULTI_ADS_FEED_UNIT_CLASS = "com.facebook.graphql.model.GraphQLFBMultiAdsFeedUnit"
internal const val GRAPHQL_QUICK_PROMO_FEED_UNIT_CLASS =
    "com.facebook.graphql.model.GraphQLQuickPromotionNativeTemplateFeedUnit"

internal const val AUDIENCE_NETWORK_ACTIVITY_CLASS = "com.facebook.ads.AudienceNetworkActivity"
internal const val AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS = "com.facebook.ads.internal.ipc.AudienceNetworkRemoteActivity"
internal const val NEKO_PLAYABLE_ACTIVITY_CLASS = "com.facebook.neko.playables.activity.NekoPlayableAdActivity"

internal const val GAME_AD_REJECTION_MESSAGE = "Game ad request blocked"
internal const val GAME_AD_REJECTION_CODE = "CLIENT_UNSUPPORTED_OPERATION"
internal const val GAME_AD_UNAVAILABLE_MESSAGE = "Rewarded ad unavailable"
internal const val GAME_AD_UNAVAILABLE_CODE = "ADS_UNAVAILABLE"
internal const val GAME_AD_SUCCESS_INSTANCE_PREFIX = "facebook_app_ads_remover_noop_ad"

internal const val GAME_AD_RECENT_WINDOW_MS = 30_000L
internal const val GAME_AD_PROMISE_WINDOW_MS = 10 * 60_000L
internal const val AUDIENCE_NETWORK_REWARD_CLOSE_RETRY_WINDOW_MS = 35_000L

internal const val HOOK_HIT_LOG_EVERY = 25

internal val FEED_SURFACE_AD_MARKER_TOKENS = listOf(
    "hide ad",
    "ad\u2022",
    "sponsored",
    "promoted",
    "ad choices",
    "adchoices"
)

internal val EXPLICIT_FEED_CARD_AD_MARKER_TOKENS = listOf(
    "hide ad",
    "ad\u2022",
    "ad choices",
    "adchoices"
)

internal val EXPLICIT_FEED_AD_CTA_TOKENS = listOf(
    "apply now",
    "send message",
    "learn more",
    "shop now",
    "contact us",
    "get quote",
    "book now",
    "call now",
    "sign up",
    "download"
)

internal val FEED_REEL_CTA_AD_MARKER_TOKENS = listOf(
    "shared link:",
    "send message",
    "your business",
    "your ad"
)

internal fun isGameAdMarkerText(value: CharSequence?): Boolean {
    if (value.isNullOrBlank()) return false
    val normalized = value.toString().lowercase()
    return normalized.contains("ads served by meta") ||
        normalized.contains("ad choices") ||
        normalized.contains("adchoices")
}
