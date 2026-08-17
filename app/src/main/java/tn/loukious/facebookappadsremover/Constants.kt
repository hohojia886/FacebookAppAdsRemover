package tn.loukious.facebookappadsremover

const val TAG = "FacebookAppAdsRemover"

const val HOST_PACKAGE = "com.facebook.katana"
const val BEFORE_SIZE_EXTRA = "facebook_ads_before_size"
const val BUILD_MARKER = "fb571_sponsored_component_guard_v3_2026_07_23"
const val ENABLE_UPSTREAM_REELS_AD_HOOKS = true
const val ENABLE_FEED_CSR_FILTER_HOOKS = true
const val ENABLE_LATE_FEED_LIST_HOOKS = true
const val ENABLE_STORY_POOL_ADD_HOOKS = true
const val ENABLE_FEED_SPONSORED_POOL_HOOKS = true
const val ENABLE_FEED_UI_MARKER_FALLBACKS = false
const val ENABLE_GAME_AD_AUTOFIX = true
const val ENABLE_GAME_AD_DIAGNOSTICS = true
const val ENABLE_BROAD_HANDLER_GAME_AD_DIAGNOSTICS = false
const val ENABLE_AUDIENCE_NETWORK_VIEW_DIAGNOSTICS = false
const val ENABLE_AUDIENCE_NETWORK_REWARD_FALLBACKS = true
const val ENABLE_AUDIENCE_NETWORK_AUTO_EXIT_WHEN_READY = true
const val GAME_AD_DIAG_LOG_LIMIT = 8_000
const val GAME_AD_DIAG_TEXT_LIMIT = 1_200
const val GAME_AD_DIAG_FLOW_WINDOW_MS = 2 * 60_000L
const val AUDIENCE_NETWORK_STATE_DUMP_LIMIT = 120
const val GRAPHQL_FEED_UNIT_EDGE_CLASS = "com.facebook.graphql.model.GraphQLFeedUnitEdge"
const val GRAPHQL_MULTI_ADS_FEED_UNIT_CLASS = "com.facebook.graphql.model.GraphQLFBMultiAdsFeedUnit"
const val GRAPHQL_QUICK_PROMO_FEED_UNIT_CLASS =
    "com.facebook.graphql.model.GraphQLQuickPromotionNativeTemplateFeedUnit"
const val AUDIENCE_NETWORK_ACTIVITY_CLASS = "com.facebook.ads.AudienceNetworkActivity"
const val AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS = "com.facebook.ads.internal.ipc.AudienceNetworkRemoteActivity"
const val NEKO_PLAYABLE_ACTIVITY_CLASS = "com.facebook.neko.playables.activity.NekoPlayableAdActivity"
const val GAME_AD_REJECTION_MESSAGE = "Game ad request blocked"
const val GAME_AD_REJECTION_CODE = "CLIENT_UNSUPPORTED_OPERATION"
const val GAME_AD_UNAVAILABLE_MESSAGE = "Rewarded ad unavailable"
const val GAME_AD_UNAVAILABLE_CODE = "ADS_UNAVAILABLE"
const val GAME_AD_SUCCESS_INSTANCE_PREFIX = "facebook_app_ads_remover_noop_ad"
const val GAME_AD_RECENT_WINDOW_MS = 30_000L
const val GAME_AD_PROMISE_WINDOW_MS = 10 * 60_000L
const val AUDIENCE_NETWORK_REWARD_CLOSE_RETRY_WINDOW_MS = 35_000L
const val HOOK_HIT_LOG_EVERY = 25

const val GAME_AD_WEBVIEW_HIDE_SCRIPT = """
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
    var t = textOf(el);
    var a = attrsOf(el);
    if (t.indexOf('ads served by meta') >= 0 || t.indexOf('ad choices') >= 0) return true;
    if (!nearBottom(el)) return false;
    if ((el.tagName || '').toLowerCase() === 'iframe') return true;
    return /audiencenetwork|adchoices|fbinstant.*ad|instant.*ad|banner.?ad|ad.?banner|ad-container|ad_container|sponsored/.test(a);
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
  function sweep() {
    try {
      document.querySelectorAll('iframe, div, section, aside, [id], [class], [aria-label]').forEach(function(el) {
        if (isAd(el)) hide(el);
      });
    } catch (e) {}
  }
  sweep();
  new MutationObserver(sweep).observe(document.documentElement || document.body, {childList:true, subtree:true, attributes:true});
  setInterval(sweep, 1000);
})();
"""

val GAME_AD_MESSAGE_TYPES = setOf(
    "getinterstitialadasync",
    "getrewardedvideoasync",
    "getrewardedinterstitialasync",
    "loadadasync",
    "showadasync",
    "loadbanneradasync",
    "hidebanneradasync"
)

val GAME_AD_AUTOFIX_MESSAGE_TYPES = setOf(
    "loadbanneradasync",
    "hidebanneradasync"
)

val GAME_AD_UNAVAILABLE_MESSAGE_TYPES = setOf(
    "getrewardedvideoasync",
    "getrewardedinterstitialasync"
)

val GAME_AD_ACTIVITY_CLASS_NAMES = setOf(
    AUDIENCE_NETWORK_ACTIVITY_CLASS,
    AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS,
    NEKO_PLAYABLE_ACTIVITY_CLASS
)

val HARD_BLOCKED_GAME_AD_ACTIVITY_CLASS_NAMES = setOf(
    NEKO_PLAYABLE_ACTIVITY_CLASS
)

val AUDIENCE_NETWORK_REWARD_COMPLETION_METHOD_NAMES = setOf(
    "onRewardedVideoCompleted",
    "onRewardedAdCompleted",
    "onRewardedInterstitialCompleted",
    "onAdComplete",
    "onAdCompleted"
)

val AUDIENCE_NETWORK_CLOSE_LISTENER_CLASS_NAMES = setOf(
    "X.mGv",
    "X.mGo",
    "p000X.mGv",
    "p000X.mGo"
)

val AUDIENCE_NETWORK_FOCUSED_DIAGNOSTIC_CLASS_NAMES = setOf(
    "mgv",
    "mgo",
    "mkr",
    "mkq",
    "mks",
    "mdx",
    "mkp"
)

val GAME_AD_METHOD_TAGS = listOf(
    "Invalid JSON content received by onGetInterstitialAdAsync: ",
    "Invalid JSON content received by onGetRewardedInterstitialAsync: ",
    "Invalid JSON content received by onRewardedVideoAsync: ",
    "Invalid JSON content received by onLoadAdAsync: ",
    "Invalid JSON content received by onShowAdAsync: "
)

val FEED_AD_CATEGORY_VALUES = setOf(
    "SPONSORED",
    "PROMOTION",
    "ENGAGEMENT_QP",
    "AD",
    "ADVERTISEMENT",
    "BANNER"
)

val FEED_SAFE_CONTAINER_CATEGORY_VALUES = setOf(
    "FB_SHORTS",
    "MULTI_FB_STORIES_TRAY"
)

val FEED_AD_SIGNAL_TOKENS = listOf(
    "sponsored",
    "promotion",
    "multiads",
    "quickpromotion",
    "reels_banner_ad",
    "reelsbannerads",
    "reels_post_loop_deferred_card",
    "deferred_card",
    "adbreakdeferredcta",
    "instreamadidlewithbannerstate",
    "instream_legacy_banner_ad",
    "unified_player_banner_ad",
    "banner_ad_",
    "floatingcta"
)

val STORY_AD_PROVIDER_TAGS = listOf(
    "ads_deletion",
    "ads_insertion",
    "StoryAdsInDisc"
)

val FB571_STORY_AD_SOURCE_CLASSES = listOf(
    "X.9xH",
    "X.A4W",
    "X.9zi",
    "X.A4w",
    "X.CNo",
    "X.KJw"
)

val FB571_FEED_CSR_CLASSES = listOf(
    "X.21p",
    "X.baJ",
    "X.baK"
)

val FB571_FEED_ITEM_CONTRACT_CLASSES = listOf(
    "X.3YX"
)

const val FB571_NETWORK_FEED_CLASS = "X.1fM"
const val FB571_NETWORK_FEED_METHOD = "A0B"
const val FB571_SPONSORED_POOL_CLASS = "X.21O"
const val FB571_SPONSORED_POOL_ADD_METHOD = "A03"
val FB571_SURVIVING_FEED_TYPE_CLASSES = listOf(
    "X.2OT",
    "X.2OU",
    "X.2OP",
    "X.2Ou",
    "X.2Oc",
    "X.3OJ",
    "X.3OF",
    "X.3xW",
    GRAPHQL_FEED_UNIT_EDGE_CLASS
)

val FEED_SURFACE_AD_MARKER_TOKENS = listOf(
    "hide ad",
    "ad\u2022",
    "sponsored",
    "promoted",
    "ad choices",
    "adchoices"
)

val EXPLICIT_FEED_CARD_AD_MARKER_TOKENS = listOf(
    "hide ad",
    "ad\u2022",
    "ad choices",
    "adchoices"
)

val EXPLICIT_FEED_AD_CTA_TOKENS = listOf(
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

val FEED_REEL_CTA_AD_MARKER_TOKENS = listOf(
    "shared link:",
    "send message",
    "your business",
    "your ad"
)

val REELS_AD_SIGNAL_TOKENS = listOf(
    "sponsored",
    "promotion",
    "multiads",
    "quickpromotion",
    "reels_banner_ad",
    "reelsbannerads",
    "adbreakdeferredcta",
    "instreamadidlewithbannerstate",
    "instream_legacy_banner_ad",
    "unified_player_banner_ad",
    "banner_ad_"
)
