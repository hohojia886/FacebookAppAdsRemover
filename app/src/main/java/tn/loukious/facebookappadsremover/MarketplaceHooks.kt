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

// Marketplace sponsored units (seller row + "Sponsored" label + video card) in
// the home feed are rendered by Litho components that carry stable "Marketplace
// …Ads…" strings: the query-fetched fallback card ("MarketplaceVideoAdQuery"),
// the Litho wrapper that mounts the ad card content
// ("MarketplaceVideoAdsComponent"), and the ad video layout
// ("MarketplaceVideoAdsGrootLayoutSpec"). Blocking their render/layout methods
// to null makes Litho skip the whole unit. The ads themselves are fetched by a
// Relay query whose name only exists in the JS bundle, so render time is the
// earliest reliable native interception point.
internal val marketplaceAdRenderHookedMethods: MutableSet<Method> =
    Collections.synchronizedSet(HashSet())

internal fun installMarketplaceAdRenderBlock(classLoader: ClassLoader, bridge: DexKitBridge) {
    val anchors = listOf(
        "MarketplaceVideoAdQuery",
        "MarketplaceVideoAdsComponent",
        "MarketplaceVideoAdsGrootLayoutSpec"
    )
    for (anchor in anchors) {
        runCatching {
            val matches = bridge.findClass {
                matcher {
                    usingStrings(anchor)
                }
            }
            for (candidate in matches) {
                val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull() ?: continue
                hookMarketplaceAdRenderable(clazz, marketplaceAdRenderHookedMethods, "Marketplace ad component blocked")
            }
            Log.i(TAG, "Marketplace ad renderables for $anchor: ${matches.size}")
        }.onFailure { Log.w(TAG, "Marketplace ad render block failed for $anchor", it) }
    }
}

internal fun hookMarketplaceAdRenderable(
    clazz: Class<*>,
    hookedMethods: MutableSet<Method>,
    label: String
) {
    // The query-fetched card is a plain renderable (render(SectionContext));
    // the other two are Litho layout components whose method names are
    // obfuscated but whose shape is stable: exactly one non-primitive
    // parameter (the Litho component context) and a non-primitive return
    // (the component tree). Hooking that shape only matches the layout entry
    // point — lifecycle hooks (onCreateLayout's 2-param variants, void
    // attach/detach) are excluded.
    val targets = clazz.declaredMethods.filter { method ->
        !Modifier.isStatic(method.modifiers) && !method.isSynthetic && (
            method.name == "render" ||
                (
                    method.parameterCount == 1 &&
                        !method.parameterTypes[0].isPrimitive &&
                        method.returnType != Void.TYPE &&
                        !method.returnType.isPrimitive
                    )
            )
    }
    var hooked = 0
    for (method in targets) {
        if (!hookedMethods.add(method)) continue
        method.isAccessible = true
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = null
                logHookHitThrottled("marketplaceAdRenderBlock", method, "")
            }
        })
        hooked++
    }
    if (hooked > 0) {
        Log.i(TAG, "$label: ${clazz.name} methods=$hooked")
    }
}

// Marketplace home-feed sponsored tiles are fetched by dedicated Relay queries
// ("RelayFBNetwork_MarketplaceHomeFeedAdsQueryRendererQuery",
// "...MarketplaceHomeFeedAdsPaginationQuery",
// "...MarketplaceHomeFeedBoostedListingAds[...Pagination]Query") issued
// through the React Native Networking module. The query name is embedded in
// the POST body, so requests carrying it are dropped entirely: the RN
// QueryRenderer never receives data and keeps rendering its loading fallback
// (nothing), which removes the whole sponsored tile. The Networking module is
// found by its stable request-context string; its sendRequest method keeps the
// RN-native name because JS invokes it reflectively by name.
internal fun installMarketplaceAdsQueryBlock(classLoader: ClassLoader, bridge: DexKitBridge) {
    runCatching {
        val matches = bridge.findClass {
            matcher {
                usingStrings("FBNetworkingModule_React_Native")
            }
        }
        var installed = 0
        for (candidate in matches) {
            val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull() ?: continue
            val sendRequest = clazz.declaredMethods.firstOrNull { method ->
                method.name == "sendRequest" && method.parameterCount == 9
            } ?: continue
            if (hookMarketplaceSendRequest(sendRequest)) {
                installed++
                marketplaceNetResolvedClassName = clazz.name
            }
        }
        Log.i(TAG, "Marketplace ads query block installed on $installed Networking module(s)")
    }.onFailure { Log.w(TAG, "Marketplace ads query block failed", it) }
}

// Hooks already installed on these methods (the early cached install and the
// full DexKit pass resolve the same method object; hooking twice would run the
// rewrite/block logic twice per request).
internal val marketplaceSendRequestHookedMethods: MutableSet<Method> =
    Collections.synchronizedSet(HashSet())

// The Networking module class resolved by the last successful install; cached
// so later launches can re-install the hook before the marketplace renderer
// query fires (the cold-start race: the feed query goes out ~3s after launch,
// before the DexKit scan finishes).
@Volatile
internal var marketplaceNetResolvedClassName: String? = null

internal fun hookMarketplaceSendRequest(sendRequest: Method): Boolean {
    if (!marketplaceSendRequestHookedMethods.add(sendRequest)) return false
    sendRequest.isAccessible = true
    XposedBridge.hookMethod(sendRequest, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val body = requestBodyOf(param.args.getOrNull(4)) ?: return
                    val name = extractMarketplaceQueryName(body)
                    if (BuildConfig.DEBUG) {
                        val url = param.args.getOrNull(1) as? String ?: ""
                        if (marketplaceDiagnosedQueries.add("req:$url|$name")) {
                            Log.i(TAG, "RN request url=$url query=$name len=${body.length}")
                        }
                    }
                    // The marketplace home feed queries (initial renderer and
                    // scroll pagination) mix sponsored tiles into the organic
                    // grid. The persisted-query config exposes server-honoured
                    // skip flags, so rewriting the request variables makes the
                    // server omit the ads entirely instead of trying to strip
                    // them from a chunked incremental response.
                    if (name == "MarketplaceHomeFeedQueryRendererQuery" ||
                        name == "MarketplaceHomeFeedPaginationQuery"
                    ) {
                        val rewritten = rewriteMarketplaceFeedRequestVariables(body)
                        if (rewritten != null) {
                            val replacement = readableMapWithString(param.args.getOrNull(4), rewritten)
                            if (replacement != null) {
                                param.args[4] = replacement
                                logHookHitThrottled("marketplaceFeedAdSkip", sendRequest, "")
                            } else if (BuildConfig.DEBUG) {
                                Log.w(TAG, "Could not build replacement ReadableMap for feed request")
                            }
                        }
                        return
                    }
                    if (!body.contains("MarketplaceHomeFeedAds") &&
                        !body.contains("MarketplaceHomeFeedBoostedListingAds") &&
                        !body.contains("MarketplaceHomeFeedThemedAds")
                    ) {
                        return
                    }
                    param.result = null
                    logHookHitThrottled("marketplaceAdsQueryBlock", sendRequest, "")
                }
    })
    return true
}

@Volatile
private var lastSavedMarketplaceNetCachePayload: String? = null

// Opt 3.1 & 3.2: Asynchronous background cache serialization and redundant write suppression
fun saveMarketplaceNetGuardCache(context: Context, hostVersionName: String) {
    val className = marketplaceNetResolvedClassName ?: return
    if (hostVersionName.isBlank()) return

    val payload = "$hostVersionName|${feedGuardCacheModuleKey()}|$className"
    if (payload == lastSavedMarketplaceNetCachePayload) {
        if (BuildConfig.DEBUG) Log.i(TAG, "Marketplace net guard cache payload unchanged; suppressing write")
        return
    }

    cacheIoExecutor.execute {
        runCatching {
            val file = File(context.cacheDir, MARKETPLACE_NET_CACHE_FILE)
            val properties = Properties()
            properties.setProperty("version", hostVersionName)
            properties.setProperty("moduleVersion", feedGuardCacheModuleKey())
            properties.setProperty("networkingModule", className)
            file.outputStream().use { properties.store(it, null) }
            lastSavedMarketplaceNetCachePayload = payload
            Log.i(TAG, "Saved marketplace net guard cache networkingModule=$className")
        }.onFailure {
            Log.w(TAG, "Failed to save marketplace net guard cache", it)
        }
    }
}

// Returns true when the cached Networking module was loaded and hooked. Fails
// softly (false) while the secondary dex is not yet configured, so callers can
// retry on a timer.
fun installMarketplaceNetGuardFromCache(
    context: Context,
    classLoader: ClassLoader,
    hostVersionName: String
): Boolean {
    if (hostVersionName.isBlank()) return false
    return runCatching {
        val file = File(context.cacheDir, MARKETPLACE_NET_CACHE_FILE)
        if (!file.exists()) return false
        val properties = Properties()
        file.inputStream().use { properties.load(it) }
        if (hostVersionName != properties.getProperty("version")) return false
        if (feedGuardCacheModuleKey() != properties.getProperty("moduleVersion")) return false
        val className = properties.getProperty("networkingModule").orEmpty()
        if (className.isBlank()) return false
        lastSavedMarketplaceNetCachePayload = "$hostVersionName|${feedGuardCacheModuleKey()}|$className"
        val clazz = Class.forName(className, false, classLoader)
        val sendRequest = clazz.declaredMethods.firstOrNull { method ->
            method.name == "sendRequest" && method.parameterCount == 9
        } ?: return false
        val hooked = hookMarketplaceSendRequest(sendRequest)
        if (hooked) {
            Log.i(TAG, "Marketplace net guard installed from cache on $className")
        }
        hooked
    }.onFailure {
        false
    }.getOrDefault(false)
}

// The RN Networking module receives its POST body as a ReadableMap with a
// "string" key. ReadableMap is a host interface, so read it reflectively.
internal fun requestBodyOf(data: Any?): String? {
    if (data == null) return null
    val hasKey = data.javaClass.methods.firstOrNull {
        it.name == "hasKey" && it.parameterCount == 1
    } ?: return null
    val getString = data.javaClass.methods.firstOrNull {
        it.name == "getString" && it.parameterCount == 1
    } ?: return null
    return runCatching {
        if (hasKey.invoke(data, "string") != true) {
            return@runCatching null
        }
        getString.invoke(data, "string") as? String
    }.getOrNull()
}

// The marketplace home feed request body is form-encoded. Its "variables"
// parameter is URL-encoded JSON whose schema (from the persisted query config
// asset) includes server-honoured ad-skip flags. Flipping them to true makes
// the server omit sponsored tiles from the response instead of the module
// having to strip them out of a chunked incremental payload. Returns null when
// the body has no variables to rewrite.
internal fun rewriteMarketplaceFeedRequestVariables(body: String): String? {
    val marker = "variables="
    val markerIndex = body.indexOf(marker)
    if (markerIndex < 0) return null
    val valueStart = markerIndex + marker.length
    val valueEnd = body.indexOf('&', valueStart).let { if (it < 0) body.length else it }
    val encoded = body.substring(valueStart, valueEnd)
    val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: return null
    var changed = false
    var variables: JSONObject? = null
    try {
        variables = JSONObject(decoded)
        for (flag in MARKETPLACE_FEED_AD_SKIP_FLAGS) {
            if (variables.optBoolean(flag, false)) continue
            variables.put(flag, true)
            changed = true
        }
    } catch (throwable: Throwable) {
        if (BuildConfig.DEBUG) {
            Log.w(TAG, "Failed to parse marketplace feed variables", throwable)
        }
        return null
    }
    if (!changed) return null
    val rewritten = URLEncoder.encode(variables.toString(), "UTF-8")
    return body.substring(0, valueStart) + rewritten + body.substring(valueEnd)
}

// Builds a replacement ReadableMap body for the RN Networking module. The
// module and its same-origin delegate read the body only through
// hasKey/getString/getType, so any ReadableMap implementation works; the RN
// bridge's WritableNativeMap is public API with a no-arg constructor and
// putString(String, String).
internal fun readableMapWithString(original: Any?, body: String): Any? {
    if (original == null) return null
    return runCatching {
        // Resolve through the host classloader (the original body map's), not
        // the module's own.
        val mapClass = original.javaClass.classLoader
            .loadClass("com.facebook.react.bridge.WritableNativeMap")
        val instance = mapClass.getDeclaredConstructor().newInstance()
        val putString = mapClass.methods.firstOrNull {
            it.name == "putString" &&
                it.parameterCount == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java
        } ?: return@runCatching null
        putString.invoke(instance, "string", body)
        instance
    }.getOrNull()
}

// Correlates marketplace diagnostics across a session while diagnosing where
// the marketplace feed payload actually flows.
internal val marketplaceDiagnosedQueries: MutableSet<String> =
    Collections.synchronizedSet(HashSet())
internal val marketplaceQueryNameRegex = Regex("query[\\s]+([A-Za-z0-9_]+)")
// Persisted Relay requests are form-encoded and carry the readable query
// name in fb_api_req_friendly_name plus a numeric doc_id.
internal val marketplaceFormQueryIdRegex = Regex("fb_api_req_friendly_name=([A-Za-z0-9_]+)")

// Opt 1.2: Fast string pre-check before Regex evaluation to avoid unnecessary Regex execution over large request bodies
internal fun extractMarketplaceQueryName(body: String): String {
    if (body.contains("fb_api_req_friendly_name=")) {
        val match = marketplaceFormQueryIdRegex.find(body)
        if (match != null) return match.groupValues[1]
    }
    if (body.contains("query")) {
        val match = marketplaceQueryNameRegex.find(body)
        if (match != null) return match.groupValues[1]
    }
    return "persisted"
}

// The RN Networking module hands every response body to JavaScript through the
// static emitters of one class (found via its stable "didReceiveNetworkData"
// event string): the text path, the base64/byte[] path, and the incremental
// path. Feed responses are chunked through the incremental path, so reliably
// rewriting the payload there requires reassembling chunks across calls.
internal fun installMarketplaceFeedResponseFilter(classLoader: ClassLoader, bridge: DexKitBridge) {
    runCatching {
        val matches = bridge.findClass {
            matcher {
                usingStrings("didReceiveNetworkData")
            }
        }
        var installed = 0
        for (candidate in matches) {
            val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull() ?: continue
            for (method in clazz.declaredMethods) {
                if (!java.lang.reflect.Modifier.isStatic(method.modifiers)) continue
                if (method.returnType != Void.TYPE) continue
                val params = method.parameterTypes
                val incrementalEmitter = params.size == 6 &&
                    params[1] == String::class.java &&
                    params[2] == String::class.java &&
                    params[3] == Int::class.javaPrimitiveType &&
                    params[4] == Long::class.javaPrimitiveType &&
                    params[5] == Long::class.javaPrimitiveType
                if (!incrementalEmitter) continue
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!BuildConfig.DEBUG) return
                        val body = param.args.getOrNull(2) as? String ?: return
                        if (!body.contains("ponsored")) return
                        // Diagnostic capture: write sponsored-bearing chunks to
                        // the host cache dir (logcat's ring buffer is too small
                        // for ~120KB bodies).
                        runCatching {
                            val context = param.args.getOrNull(0) as? Context ?: return
                            val dir = File(context.cacheDir, "mp_probe")
                            dir.mkdirs()
                            val file = File(dir, "chunk_${System.currentTimeMillis()}.json")
                            file.writeText(body)
                            Log.i(TAG, "MP-CHUNK captured len=${body.length} file=${file.name}")
                        }
                    }
                })
                installed++
                Log.i(TAG, "Marketplace response probe installed on ${clazz.name}.$method")
            }
        }
        Log.i(TAG, "Marketplace response probe installed on $installed emitter(s)")
    }.onFailure { Log.w(TAG, "Marketplace response probe failed", it) }
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

