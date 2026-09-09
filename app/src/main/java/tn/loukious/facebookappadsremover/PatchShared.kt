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

const val TAG = "FacebookAppAdsRemover"

internal const val HOST_PACKAGE = "com.facebook.katana"
internal const val BEFORE_SIZE_EXTRA = "facebook_ads_before_size"
internal const val BUILD_MARKER = "fb576_structural_component_guard_v1_2026_08_29"
internal const val ENABLE_UPSTREAM_REELS_AD_HOOKS = true
internal const val ENABLE_FEED_CSR_FILTER_HOOKS = true
internal const val ENABLE_LATE_FEED_LIST_HOOKS = true
internal const val ENABLE_STORY_POOL_ADD_HOOKS = true
internal const val ENABLE_FEED_SPONSORED_POOL_HOOKS = true
internal const val ENABLE_FEED_UI_MARKER_FALLBACKS = false
internal const val ENABLE_GAME_AD_AUTOFIX = true
internal val ENABLE_GAME_AD_DIAGNOSTICS = BuildConfig.DEBUG
internal val ENABLE_BROAD_HANDLER_GAME_AD_DIAGNOSTICS = BuildConfig.DEBUG && false
internal val ENABLE_AUDIENCE_NETWORK_VIEW_DIAGNOSTICS = BuildConfig.DEBUG && false
internal const val ENABLE_AUDIENCE_NETWORK_REWARD_FALLBACKS = true
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

internal val GAME_AD_MESSAGE_TYPES = setOf(
    "getinterstitialadasync",
    "getrewardedvideoasync",
    "getrewardedinterstitialasync",
    "loadadasync",
    "showadasync",
    "loadbanneradasync",
    "hidebanneradasync"
)

// Rewarded requests are resolved as success instead of "unavailable" so the
// game grants the reward without showing an ad.
internal val GAME_AD_AUTOFIX_MESSAGE_TYPES = setOf(
    "getrewardedvideoasync",
    "getrewardedinterstitialasync",
    "loadbanneradasync",
    "hidebanneradasync"
)

internal val GAME_AD_REWARD_MESSAGE_TYPES = setOf(
    "getrewardedvideoasync",
    "getrewardedinterstitialasync"
)

internal val GAME_AD_ACTIVITY_CLASS_NAMES = setOf(
    AUDIENCE_NETWORK_ACTIVITY_CLASS,
    AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS,
    NEKO_PLAYABLE_ACTIVITY_CLASS
)

internal val HARD_BLOCKED_GAME_AD_ACTIVITY_CLASS_NAMES = setOf(
    NEKO_PLAYABLE_ACTIVITY_CLASS
)

internal val AUDIENCE_NETWORK_REWARD_COMPLETION_METHOD_NAMES = setOf(
    "onRewardedVideoCompleted",
    "onRewardedAdCompleted",
    "onRewardedInterstitialCompleted",
    "onAdComplete",
    "onAdCompleted"
)

internal val hookHitCounters = ConcurrentHashMap<String, AtomicInteger>()
internal val feedWrapperCandidates = ConcurrentHashMap<String, Class<*>>()
internal data class GameAdPayloadSnapshot(
    val target: Any,
    val payload: JSONObject,
    val messageType: String?,
    val timestampMs: Long
)

internal data class GameAdPromiseSnapshot(
    val payload: JSONObject,
    val messageType: String?,
    val timestampMs: Long
)

internal data class AudienceNetworkGraphNode(
    val value: Any,
    val path: String,
    val depth: Int
)

internal val GAME_AD_METHOD_TAGS = listOf(
    "Invalid JSON content received by onGetInterstitialAdAsync: ",
    "Invalid JSON content received by onGetRewardedInterstitialAsync: ",
    "Invalid JSON content received by onRewardedVideoAsync: ",
    "Invalid JSON content received by onLoadAdAsync: ",
    "Invalid JSON content received by onShowAdAsync: "
)

internal val FEED_AD_CATEGORY_VALUES = setOf(
    "SPONSORED",
    "PROMOTION",
    "ENGAGEMENT_QP",
    "AD",
    "ADVERTISEMENT",
    "BANNER"
)

internal val FEED_COLLECTION_AD_CATEGORY_VALUES = setOf(
    "SPONSORED",
    "PROMOTION",
    "AD",
    "ADVERTISEMENT",
    "BANNER"
)

internal val FEED_SAFE_CONTAINER_CATEGORY_VALUES = setOf(
    "FB_SHORTS",
    "MULTI_FB_STORIES_TRAY"
)

internal val FEED_AD_SIGNAL_TOKENS = listOf(
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

// Selectors must identify the story ad *store* itself. The telemetry labels
// "ads_insertion"/"ads_deletion" are logged by unrelated story viewer classes too
// (e.g. the viewer's onDataChanged handler), and hooking those blanks the viewer.
internal val STORY_AD_PROVIDER_TAGS = listOf(
    "AdsPaginatingNetworkAdBucketFetcher",
    "FbStoryAdInDiscStoreImpl",
    "IN_DISC_METADATA_KEY",
    "AD_BUCKETS_KEY",
    "StoryAdsInDisc"
)

internal data class NamedHookTarget(
    val className: String,
    val methodName: String
)

// Litho generated component classes pass a stable spec name to their base class
// constructor ("NewsFeedFeedUnitComponent" for the feed unit component,
// "LoggingComponent" for the generic wrapper Litho renders feed units through).
// Those names survive Facebook's obfuscator; the X.* class names change every
// build, so the component guard discovers its targets from these names instead.
const val FEED_UNIT_COMPONENT_NAME = "NewsFeedFeedUnitComponent"
const val FEED_WRAPPER_COMPONENT_NAME = "LoggingComponent"

// Litho layout entry points are matched by shape; the render parameter count
// has varied between builds (1 on 576), so later counts are only fallbacks.
internal val FEED_RENDER_PARAMETER_COUNTS = listOf(1, 2)

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

// Tagged-product sticker pills overlaid on organic reels compose their
// accessibility label as "<creator> - <product>, <CTA>" (e.g.
// "Zack D. Films - Hat (Denim), Shop now"). Requiring the full composite
// shape keeps plain "Shop now" buttons on other surfaces untouched.
internal val REELS_SHOPPING_STICKER_CTA_TOKENS = listOf(
    "shop now",
    "buy now",
    "order now"
)

internal val REELS_AD_SIGNAL_TOKENS = listOf(
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

internal object Log {
    fun i(tag: String, msg: String): Int = if (BuildConfig.DEBUG) AndroidLog.i(tag, msg) else 0

    fun w(tag: String, msg: String): Int = if (BuildConfig.DEBUG) AndroidLog.w(tag, msg) else 0

    fun w(tag: String, msg: String, throwable: Throwable): Int =
        if (BuildConfig.DEBUG) AndroidLog.w(tag, msg, throwable) else 0

    fun e(tag: String, msg: String): Int = if (BuildConfig.DEBUG) AndroidLog.e(tag, msg) else 0

    fun e(tag: String, msg: String, throwable: Throwable): Int =
        if (BuildConfig.DEBUG) AndroidLog.e(tag, msg, throwable) else 0

    fun missing(tag: String, hookName: String): Int =
        AndroidLog.w(tag, "Hook target not found: $hookName")

    fun resolutionFailure(tag: String, msg: String, throwable: Throwable): Int {
        return if (BuildConfig.DEBUG || throwable.message?.contains("Unable to resolve") == true) {
            AndroidLog.e(tag, msg, throwable)
        } else {
            0
        }
    }
}

internal data class FeedListSanitizerHook(
    val method: Method,
    val listArgIndex: Int
)

internal data class FeedCsrFilterHook(
    val method: Method,
    val listArgIndex: Int
)

internal data class StoryAdProviderHooks(
    val providerClass: Class<*>,
    val mergeMethod: Method?,
    val fetchMoreAdsMethod: Method?,
    val deferredUpdateMethod: Method?,
    val insertionTriggerMethod: Method?
)

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

internal fun Collection<MethodData>.firstMethodInstanceOrNull(classLoader: ClassLoader): Method? {
    return asSequence()
        .mapNotNull { methodData ->
            runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
        }
        .firstOrNull { method ->
            method.name != "<init>" && method.name != "<clinit>"
        }?.apply { isAccessible = true }
}

internal fun findClassesByZeroArgStringTags(
    bridge: DexKitBridge,
    tags: Collection<String>
): List<ClassData> {
    val candidates = LinkedHashMap<String, ClassData>()
    tags.forEach { tag ->
        bridge.findClass {
            matcher {
                methods {
                    matchType = MatchType.Contains
                    add {
                        returnType = "java.lang.String"
                        paramCount = 0
                        usingStrings(tag)
                    }
                }
            }
        }.forEach { candidate ->
            candidates.putIfAbsent(candidate.name, candidate)
        }
    }
    return candidates.values.toList()
}

// The full DexKit scan takes seconds, but the cached initial News Feed renders
// within the first seconds after a cold start — before the scan finishes. The
// discovered guard pair is therefore persisted (keyed by the host app version
// AND the module version) so later launches can register and hook the same
// classes within ~100ms of Application.attach. A Facebook update changes the
// obfuscated names, and a module update may change discovery semantics; either
// invalidates the cache via the version keys.
internal const val FEED_GUARD_CACHE_FILE = "fbar_feed_guard_cache.properties"

// The story-category enum moved package between builds, so match it by its
// constants rather than by class name.
internal fun declaresFeedStoryCategoryAccessor(type: Class<*>): Boolean {
    return runCatching {
        type.declaredMethods.any { method ->
            method.parameterCount == 0 &&
                method.returnType.isEnum &&
                method.returnType.enumConstants?.any { constant ->
                    val name = constant.toString()
                    name == "SPONSORED" || name == "PROMOTION"
                } == true
        }
    }.getOrDefault(false)
}


internal data class VisibleAdGraphNode(
    val value: Any,
    val path: String,
    val depth: Int
)

internal fun invokeMethodByName(target: Any?, methodName: String, vararg args: Any?): Any? {
    if (target == null) return null
    val method = allMethodsInHierarchy(target.javaClass).firstOrNull { candidate ->
        candidate.name == methodName &&
            candidate.parameterCount == args.size &&
            candidate.parameterTypes.zip(args).all { (parameterType, argument) ->
                argument == null || parameterType.isAssignableFrom(argument.javaClass)
            }
    } ?: return null
    method.isAccessible = true
    return runCatching { method.invoke(target, *args) }.getOrNull()
}

internal fun allFieldsInHierarchy(type: Class<*>): List<Field> {
    val fields = ArrayList<Field>()
    var current: Class<*>? = type
    while (current != null && current != Any::class.java && fields.size < 200) {
        fields.addAll(current.declaredFields)
        current = current.superclass
    }
    return fields
}

internal fun allMethodsInHierarchy(type: Class<*>): List<Method> {
    val methods = LinkedHashMap<String, Method>()
    var current: Class<*>? = type
    while (current != null && current != Any::class.java) {
        current.declaredMethods.forEach { method ->
            methods.putIfAbsent(
                "${method.name}:${method.parameterTypes.joinToString { it.name }}",
                method
            )
        }
        current = current.superclass
    }
    return methods.values.toList()
}


internal fun isFeedListType(type: Class<*>): Boolean {
    return Iterable::class.java.isAssignableFrom(type) ||
        type.name == "com.google.common.collect.ImmutableList"
}

// Narrower than isFeedListType: only concrete collection types, never arbitrary
// interfaces that happen to extend Iterable. Used where the shape rule alone is
// too generic to safely identify a method (see deferredUpdateMethod).
internal fun isConcreteFeedListType(type: Class<*>): Boolean {
    return Collection::class.java.isAssignableFrom(type)
}

internal fun Method.listParameterIndexes(): List<Int> {
    return parameterTypes.mapIndexedNotNull { index, type ->
        index.takeIf { List::class.java.isAssignableFrom(type) }
    }
}

// Server classification values that mark a reel item as an ad.
internal val AD_CLASSIFICATION_VALUES = setOf("AD", "ADS_MIDCARD")

// The shorts viewer renders server-injected full-page ads through dedicated
// ad components — the ad root component ("FbShortsAdsRootKComponent"), the
// real-time-intent section ("FbShortsAdsRealTimeIntentComponent"), and a
// sibling of the RTI section with no surviving string anchor (576: X.PWg) —
// fed directly by the RTI ad data, not by the video-home collection. Worse,
// some of these components carry no anchor strings at all, and every reels
// page (organic or ad) is ultimately built by the shared page component
// (576: X.Ad2), whose only ad marker is its ad-model-typed field. So besides
// the string anchors, every renderable class declaring a render method AND
// holding a field typed as the ad model interface is hooked: when such a
// component instance carries an ad-classified model (or an item list made
// only of ad items), its render is short-circuited to null, which Litho
// treats as "render nothing".
// The reels render block races the cold-start ad render (the full DexKit pass
// needs seconds; the reels UI renders within the first second). The resolved
// hook targets are therefore persisted (keyed by host and module version, like
// the feed guard cache) and re-installed within ~100ms of Application.attach
// on later launches. The hook registries are shared between the early cached
// install and the full DexKit pass so nothing is hooked twice.
internal const val REELS_GUARD_CACHE_FILE = "fbar_reels_guard_cache.properties"

@Volatile
internal var reelsGuardModelInterfaceSpecs: List<String> = emptyList()

@Volatile
internal var reelsGuardEnumClassName: String = ""

// The marketplace feed query fires within seconds of launch — before the
// DexKit scan installs the main hooks (the same cold-start race as the cached
// News Feed) — so the resolved Networking module class name is persisted and
// re-hooked right after Application.attach on later launches. The class name
// is obfuscated per build, but it is discovered at runtime and cached keyed by
// the Facebook version, never hardcoded.
internal const val MARKETPLACE_NET_CACHE_FILE = "fbar_marketplace_net_cache.properties"

internal val MARKETPLACE_FEED_AD_SKIP_FLAGS = listOf(
    "shouldSkipAdRequest",
    "shouldSkipBoostedListingAdRequest",
)

// Classifier for server-injected full-page Reels ads. Reel items expose their
// media model through a zero-arg accessor; the model implements an interface
// (576: X.9AE) whose zero-arg classifier method (BQB) returns a classification
// enum (576: X.7T7) with stable server values ("AD", "ADS_MIDCARD", "MIDCARD",
// "PARADE", "UGC"). The enum is found via the stable "ADS_MIDCARD" string; the
// item interfaces are interfaces declaring exactly one zero-arg method
// returning that enum.
internal class ReelsAdClassifier(
    private val modelInterfaces: List<Pair<Class<*>, Method>>,
    private val adValues: Set<Any>
) {
    lateinit var enumClassName: String

    val modelInterfaceClasses: List<Class<*>> get() = modelInterfaces.map { it.first }

    // Item class -> candidate accessor chains (each chain is a sequence of
    // zero-arg getters from item to model). Null entries are cached misses.
    private val accessorChainsCache = ConcurrentHashMap<Class<*>, List<List<Method>>?>()

    fun isAdReelItem(item: Any?): Boolean {
        val classification = classificationOf(item) ?: return false
        return classification in AD_CLASSIFICATION_VALUES
    }

    // Classification of a direct model object (e.g. a constructor arg), or
    // null when the value is not a model instance.
    fun modelClassification(model: Any?): String? {
        if (model == null) return null
        for ((iface, method) in modelInterfaces) {
            if (iface.isInstance(model)) {
                return runCatching { method.invoke(model)?.toString() }.getOrNull()
            }
        }
        return null
    }

    // Returns the item's classification name (e.g. "AD", "UGC"), or null when
    // no model accessor chain resolves for the item's class.
    fun classificationOf(item: Any?): String? {
        if (item == null) return null
        val chains = resolveAccessorChains(item.javaClass) ?: return null
        for (chain in chains) {
            var current: Any? = item
            for (accessor in chain) {
                current = runCatching { accessor.invoke(current) }.getOrNull() ?: break
            }
            val model = current ?: continue
            for ((iface, method) in modelInterfaces) {
                if (!iface.isInstance(model)) continue
                return runCatching { method.invoke(model)?.toString() }.getOrNull()
            }
        }
        return null
    }

    private fun resolveAccessorChains(clazz: Class<*>): List<List<Method>>? {
        accessorChainsCache.get(clazz)?.let { return it }
        val chains = runCatching { findAccessorChains(clazz) }.getOrNull()
        accessorChainsCache[clazz] = chains
        return chains
    }

    // Direct accessors first (item method returning a model interface), then
    // two-hop chains through a holder object (576 reels items expose the media
    // model via item.A04().A00()-style getter pairs). Only one hop through
    // non-trivial types; deeper nesting has not been seen.
    private fun findAccessorChains(clazz: Class<*>): List<List<Method>>? {
        val chains = ArrayList<List<Method>>()
        zeroArgMethods(clazz).forEach { candidate ->
            if (modelInterfaces.any { (iface, _) -> candidate.returnType == iface }) {
                chains.add(listOf(candidate))
            }
        }
        zeroArgMethods(clazz).forEach { candidate ->
            val holder = candidate.returnType
            if (holder == clazz || holder.isPrimitive || holder == Void.TYPE ||
                holder == String::class.java ||
                Collection::class.java.isAssignableFrom(holder) ||
                holder.name.startsWith("java.")
            ) return@forEach
            zeroArgMethods(holder).forEach { nested ->
                if (modelInterfaces.any { (iface, _) -> nested.returnType == iface }) {
                    chains.add(listOf(candidate, nested))
                }
            }
        }
        return chains.ifEmpty { null }
    }

    private fun zeroArgMethods(clazz: Class<*>): List<Method> =
        clazz.methods.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 0 &&
                method.returnType != Void.TYPE
        }

    fun isModelType(type: Class<*>): Boolean = modelInterfaces.any { it.first == type }

    fun describe(): String {
        return "models=${modelInterfaces.joinToString { "${it.first.name}.${it.second.name}" }} " +
            "adValues=${adValues.joinToString { it.toString() }}"
    }
}

@Volatile
internal var reelsGuardCachedInterfaces: List<String> = emptyList()

@Volatile
internal var reelsGuardCachedRenderables: List<String> = emptyList()

@Volatile
internal var reelsGuardCachedShoppingRenderables: List<String> = emptyList()

@Volatile
internal var reelsGuardCachedPagerPush: List<String> = emptyList()

@Volatile
internal var reelsGuardCachedSnapshots: List<String> = emptyList()

@Volatile
internal var reelsGuardCacheParsed = false

internal fun methodHookKey(method: Method): String {
    return "${method.declaringClass.name}#${method.name}(" +
        method.parameterTypes.joinToString(",") { it.name } +
        "):${method.returnType.name}"
}

internal fun logHookHitThrottled(hookName: String, method: Method, detail: String? = null) {
    val hits = hookHitCounters.computeIfAbsent(hookName) { AtomicInteger(0) }.incrementAndGet()
    if (hits <= 3 || hits % HOOK_HIT_LOG_EVERY == 0) {
        val extra = detail?.let { " $it" } ?: ""
        Log.i(TAG, "Hook hit $hookName count=$hits at ${method.declaringClass.name}.${method.name}$extra")
    }
}

internal fun methodSignature(method: Method): String {
    return "${method.declaringClass.name}.${method.name}(${method.parameterTypes.joinToString(",") { it.name }}):${method.returnType.name}"
}

internal fun shortObjectLabel(value: Any): String {
    return "${value.javaClass.name}@${Integer.toHexString(System.identityHashCode(value))}"
}

internal fun byteArrayHexPreview(value: ByteArray): String {
    return value.take(48).joinToString(" ") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal fun byteArrayAsciiPreview(value: ByteArray): String {
    return value.take(96).joinToString("") { byte ->
        val code = byte.toInt() and 0xff
        if (code in 32..126) code.toChar().toString() else "."
    }
}

internal data class ExplicitFeedAdCardSignals(
    val hasHideAd: Boolean,
    val hasAdLabel: Boolean,
    val hasSharedLink: Boolean,
    val hasStrongCta: Boolean
)

internal data class FeedReelCtaAdSignals(
    val hasSharedLink: Boolean,
    val hasSendMessageCta: Boolean,
    val hasReelSurface: Boolean,
    val hasLeadGenPrompt: Boolean
)

