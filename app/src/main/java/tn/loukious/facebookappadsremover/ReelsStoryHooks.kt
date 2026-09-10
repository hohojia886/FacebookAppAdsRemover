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

internal val storyAdProviderClassesHooked = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
internal val sponsoredPoolMethodsHooked = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
internal val reelsAdDiagnosticsInstalled = AtomicInteger(0)
internal val reelsAdDiagnosticsLogged = AtomicInteger(0)

internal fun installReelsAdDiagnostics(classLoader: ClassLoader, bridge: DexKitBridge) {
    if (!reelsAdDiagnosticsInstalled.compareAndSet(0, 1)) return

    val componentClasses = LinkedHashMap<String, Class<*>>()
    bridge.findClass {
        matcher {
            usingStrings("ReelsAdsCaptionCommentComponent")
        }
    }.forEach { candidate ->
        val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull() ?: return@forEach
        componentClasses.putIfAbsent(clazz.name, clazz)
    }
    if (componentClasses.isEmpty()) {
        Log.w(TAG, "Reels ad caption component not found via string")
        return
    }

    componentClasses.values.forEach { clazz ->
        // Constructor: (FbUserSession, C8YW ad-model, C3PA) per C28D case 13.
        clazz.declaredConstructors.forEach { constructor ->
            if (constructor.parameterCount in 1..4) {
                constructor.isAccessible = true
                XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val count = reelsAdDiagnosticsLogged.incrementAndGet()
                        if (count > 60) return
                        val args = param.args.orEmpty().joinToString(" | ") { arg ->
                            "${arg?.javaClass?.name}:${formatDiagValue(arg)}"
                        }
                        Log.i(TAG, "ReelsAdDiag captionCtor ${clazz.name} args=[$args]")
                        param.thisObject?.let { describeReelsAdModelChain(it) }
                    }
                })
                Log.i(TAG, "Hooked Reels ad caption ctor ${clazz.name}")
            }
        }

        resolveLithoRenderMethod(clazz)?.let { render ->
            XposedBridge.hookMethod(render, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val count = reelsAdDiagnosticsLogged.incrementAndGet()
                    if (count > 60) return
                    Log.i(
                        TAG,
                        "ReelsAdDiag captionRender ${clazz.name}.${render.name} " +
                            "this=${formatDiagValue(param.thisObject)} args=${formatDiagArgs(param.args)}"
                    )
                }
            })
            Log.i(TAG, "Hooked Reels ad caption render ${clazz.name}.${render.name}")
        }
    }

    installReelsAdPipelineProbes(classLoader, bridge)
    installReelsInstreamAdBlock(classLoader, bridge)
    installReelsAdListFilters(classLoader, bridge)
    Log.i(TAG, "Reels ad diagnostics installed components=${componentClasses.keys}")
}

// Block full-page Reels ads by forcing the instream eligibility gate to report
// "suppress ads". On 576 the gate (X.QNM.A00) is consulted at every decision
// point of the instream state machine (entry check, fetch trigger, fetch-result
// insertion) and a true result means "do not serve an ad" — early return,
// disabled state, or skipped insertion. The gate class is referenced only by
// the reels instream pipeline, so forcing it cannot affect other surfaces.
// Resolution is structural: an instance method returning boolean with exactly
// 5 parameters whose first is FbUserSession and last is int-Integer, on a tiny
// class that carries an ImmutableList cache field.
internal fun installReelsInstreamAdBlock(classLoader: ClassLoader, bridge: DexKitBridge) {
    val userSessionClass = runCatching {
        Class.forName("com.facebook.auth.usersession.FbUserSession", false, classLoader)
    }.getOrNull() ?: run {
        Log.w(TAG, "Reels instream gate: FbUserSession class not found; skipping")
        return
    }
    val immutableListClass = runCatching {
        Class.forName("com.google.common.collect.ImmutableList", false, classLoader)
    }.getOrNull()

    val candidates = runCatching {
        bridge.findMethod {
            matcher {
                returnType = "boolean"
                paramTypes = listOf(
                    "com.facebook.auth.usersession.FbUserSession",
                    null,
                    null,
                    null,
                    "java.lang.Integer"
                )
            }
        }
    }.getOrNull().orEmpty()

    var blocked = 0
    candidates.forEach { methodData ->
        val method = runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
            ?: return@forEach
        if (Modifier.isStatic(method.modifiers)) return@forEach
        val clazz = method.declaringClass

        // The gate class declares exactly one such method on 576; anything with
        // more than one matching method is a different (shared) helper.
        val gateMethods = clazz.declaredMethods.filter { candidate ->
            !Modifier.isStatic(candidate.modifiers) &&
                candidate.returnType == java.lang.Boolean.TYPE &&
                candidate.parameterTypes.size == 5 &&
                candidate.parameterTypes.first() == userSessionClass &&
                candidate.parameterTypes.last() == java.lang.Integer::class.java
        }
        if (gateMethods.size != 1 || gateMethods[0] != method) return@forEach
        if (immutableListClass != null &&
            clazz.declaredFields.none { it.type == immutableListClass }
        ) return@forEach

        method.isAccessible = true
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = true
            }
        })
        blocked++
        Log.i(TAG, "Reels instream ad gate forced to suppress: ${clazz.name}.${method.name}")
    }
    if (blocked == 0) {
        Log.w(TAG, "Reels instream ad gate not resolved (candidates=${candidates.size})")
    }
}

// Debug-only: renderables whose model carries a classification outside the
// blocklist (e.g. MIDCARD/PARADE/UGC) pass straight through the render block.
// Logging each (renderable, classification) pair once per session maps which
// classification the still-visible banner ads ride on.
internal val reelsNonAdClassificationSeen: MutableSet<String> =
    Collections.synchronizedSet(HashSet())

// Server-injected full-page Reels ads: the ad item arrives inline in the reels
// feed response (and, for the client-vended variant, via the FbShorts
// sponsored pool) and renders straight from the item list without any ad fetch
// or story-pool insertion, so removal happens at the video-home data
// controller: the single A0K(List) choke point both feed paths push through,
// plus disabling the client-side insertion trigger and the sponsored pool fill.
internal fun installReelsAdListFilters(classLoader: ClassLoader, bridge: DexKitBridge) {
    val classifier = resolveReelsAdClassifier(classLoader, bridge) ?: return
    Log.i(TAG, "Reels ad classifier resolved: ${classifier.describe()}")

    // 1. Data controller choke point: A0K(List<C76D>) — each wrapper's list
    //    field holds the InterfaceC190979h4 items pushed into the pager's UI
    //    collection. Filter ad items out of the nested lists.
    runCatching {
        bridge.findClass {
            matcher {
                usingStrings("VideoHomeDataControllerImpl.maybeInsertAds")
            }
        }.forEach { candidate ->
            val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull() ?: return@forEach
            clazz.declaredMethods.filter { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.returnType == java.lang.Boolean.TYPE &&
                    method.parameterTypes.size == 1 &&
                    List::class.java.isAssignableFrom(method.parameterTypes[0])
            }.forEach { method ->
                runCatching { hookReelsPagerListPush(method, classifier) }
                    .onFailure { Log.w(TAG, "Failed to hook reels pager push ${clazz.name}.${method.name}", it) }
            }
        }
    }.onFailure { Log.w(TAG, "Reels pager push resolution failed", it) }

    // 2. Client-side ad insertion trigger ("maybeInsertAds"): void no-op.
    hookVoidMethodsByString(classLoader, bridge, "VideoHomeDataControllerImpl.maybeInsertAds", "Reels client-side ad insertion disabled")

    // 3. FbShorts sponsored pool fill ("after_model_added_to_pool"): void
    //    no-op, so vended ads never enter the pool.
    hookVoidMethodsByString(classLoader, bridge, "after_model_added_to_pool", "Reels sponsored pool fill disabled")

    // 4. Async RTI ad fetches: block at the fetch builders.
    installReelsRtiAdBlock(classLoader, bridge)

    // 5. The video-home item collection snapshot read: filter ads out of the
    //    collection itself, so every reader sees a sanitized list regardless
    //    of how or when an ad entered it (defeats the cold-start race where
    //    the reels CSR load lands before hook installation finishes).
    installReelsCollectionFilter(classLoader, bridge, classifier)

    // 6. The shorts viewer's own ad components: RTI ad data (whose fetch can
    //    fire before hook installation and whose response lands later) is
    //    rendered directly by dedicated components, bypassing the video-home
    //    collection. Block them at render time.
    installReelsViewerAdRenderBlock(classLoader, bridge, classifier)

    // 7. Diagnostics: capture stack traces when an ad is classified or the
    //    sponsored label renders, to expose any remaining delivery path.
    installReelsAdClassificationProbe(classLoader, bridge, classifier)
    installReelsSponsoredLabelProbe(classLoader, bridge, classifier)

    reelsFullPassInstalled.set(true)
}

// Value-based on purpose: reflection hands out fresh Method copies on every
// lookup, so an identity-based set would let the early install re-hook the
// same methods on every retry (Method.equals compares declaring class, name
// and signature).
internal val reelsRenderHookedMethods: MutableSet<Method> =
    Collections.synchronizedSet(HashSet())

// Snapshots are cached by the host until invalidated, so memoize filtered
// results by snapshot identity to keep the hot read path cheap. Shared across
// the cached and DexKit installs.
internal val reelsSnapshotMemo = Collections.synchronizedMap(IdentityHashMap<Any, Any>())

// Hook targets recorded while the full DexKit pass installs, persisted for the
// next launch's early install. Renderables are plain class names (the hooked
// method is always "render"); the pager push and snapshot hooks are
// "class#method" specs.
internal val reelsGuardRenderableNames = Collections.synchronizedList(ArrayList<String>())

// Shoppable-card components blocked unconditionally by string anchor; tracked
// separately from the ad renderables so the cached early install can hook them
// without needing the ad classifier.
internal val reelsShoppingRenderableNames = Collections.synchronizedList(ArrayList<String>())
internal val reelsGuardPagerPushSpecs = Collections.synchronizedList(ArrayList<String>())
internal val reelsGuardSnapshotSpecs = Collections.synchronizedList(ArrayList<String>())

internal fun installReelsViewerAdRenderBlock(
    classLoader: ClassLoader,
    bridge: DexKitBridge,
    classifier: ReelsAdClassifier
) {
    val anchors = listOf(
        "FbShortsAdsRootKComponent" to "Reels viewer ad component blocked",
        "FbShortsAdsRealTimeIntentComponent" to "Reels RTI ad component blocked"
    )
    for ((anchor, label) in anchors) {
        runCatching {
            bridge.findClass {
                matcher {
                    usingStrings(anchor)
                }
            }.forEach { candidate ->
                val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull() ?: return@forEach
                hookReelsAdRenderable(clazz, classifier, reelsRenderHookedMethods, label)
            }
        }.onFailure { Log.w(TAG, "$label failed", it) }
    }

    // Shoppable product cards (the small "Shop now" banner overlaying a reel):
    // unlike the ad renderables above, these hold a shopping product payload,
    // not an ad-classified model, so they are blocked unconditionally — the
    // components exist solely to render the shopping card. The string anchors
    // only bootstrap the discovery: their shared non-framework field type is
    // the shopping payload class, and a structural pass then catches every
    // renderable holding it (banner card, marketplace card, hscroll items...).
    val shoppingAnchors = listOf(
        "FbShortsShoppableProductItemComponent",
        "FbShortsShoppableAdsItemComponent",
        "FbShortsShoppableMarketplaceCardComponent"
    )
    val shoppingAnchorClasses = ArrayList<Class<*>>()
    for (anchor in shoppingAnchors) {
        runCatching {
            bridge.findClass {
                matcher {
                    usingStrings(anchor)
                }
            }.forEach { candidate ->
                val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull() ?: return@forEach
                shoppingAnchorClasses.add(clazz)
                hookReelsShoppingRenderable(clazz, reelsRenderHookedMethods, "Reels shopping card blocked")
            }
        }.onFailure { Log.w(TAG, "Reels shopping card block failed for $anchor", it) }
    }
    val shoppingPayloadType = resolveShoppingPayloadType(shoppingAnchorClasses)
    if (shoppingPayloadType != null) {
        runCatching {
            val matches = bridge.findClass {
                matcher {
                    addFieldForType(shoppingPayloadType)
                    addMethod { name("render") }
                }
            }
            for (candidate in matches) {
                val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull() ?: continue
                hookReelsShoppingRenderable(clazz, reelsRenderHookedMethods, "Reels shopping card blocked (structural)")
            }
            Log.i(TAG, "Reels shopping renderables for ${shoppingPayloadType.name}: ${matches.size}")
        }.onFailure { Log.w(TAG, "Reels shopping structural pass failed", it) }
    } else {
        Log.w(TAG, "Reels shopping payload type not resolved from ${shoppingAnchorClasses.size} anchors")
    }

    // Structural pass: renderables with an ad-model-typed field. This covers
    // the ad components without string anchors and the shared page component.
    for (modelInterface in classifier.modelInterfaceClasses) {
        runCatching {
            val matches = bridge.findClass {
                matcher {
                    addFieldForType(modelInterface)
                    addMethod { name("render") }
                }
            }
            for (candidate in matches) {
                val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull() ?: continue
                hookReelsAdRenderable(clazz, classifier, reelsRenderHookedMethods, "Reels ad renderable (structural)")
            }
            Log.i(TAG, "Reels structural renderables for ${modelInterface.name}: ${matches.size}")
        }.onFailure { Log.w(TAG, "Reels structural renderable resolution failed for ${modelInterface.name}", it) }
    }
}

internal fun hookReelsAdRenderable(
    clazz: Class<*>,
    classifier: ReelsAdClassifier,
    hookedRenderMethods: MutableSet<Method>,
    label: String
) {
    val render = clazz.declaredMethods.firstOrNull {
        it.name == "render" && !Modifier.isStatic(it.modifiers)
    } ?: return
    if (!hookedRenderMethods.add(render)) return
    reelsGuardRenderableNames.add(clazz.name)
    val modelFields = clazz.declaredFields.filter { field ->
        !Modifier.isStatic(field.modifiers) && classifier.isModelType(field.type)
    }.onEach { it.isAccessible = true }
    val listFields = clazz.declaredFields.filter { field ->
        !Modifier.isStatic(field.modifiers) &&
            Iterable::class.java.isAssignableFrom(field.type)
    }.onEach { it.isAccessible = true }
    render.isAccessible = true
    XposedBridge.hookMethod(render, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val target = param.thisObject ?: return
            for (field in modelFields) {
                val model = runCatching { field.get(target) }.getOrNull() ?: continue
                val classification = classifier.modelClassification(model)
                if (classification in AD_CLASSIFICATION_VALUES) {
                    param.result = null
                    logHookHitThrottled(
                        "reelsViewerAdRenderBlock",
                        render,
                        "model=${model.javaClass.simpleName}"
                    )
                    return
                }
                if (classification != null && BuildConfig.DEBUG) {
                    val seenKey = "${clazz.name}=$classification"
                    if (reelsNonAdClassificationSeen.add(seenKey)) {
                        Log.i(
                            TAG,
                            "Reels renderable passthrough ${clazz.name} " +
                                "model=${model.javaClass.simpleName} classification=$classification"
                        )
                    }
                }
            }
            // Only block on an item list when every classifiable item in it is
            // an ad — a mixed list belongs to a component that also renders
            // organic reels and must not be blanked.
            for (field in listFields) {
                val items = runCatching { field.get(target) }.getOrNull() as? Iterable<*> ?: continue
                var anyAd = false
                var anyNonAd = false
                for (item in items) {
                    when (classifier.classificationOf(item)) {
                        in AD_CLASSIFICATION_VALUES -> anyAd = true
                        null -> {}
                        else -> anyNonAd = true
                    }
                }
                if (anyAd && !anyNonAd) {
                    param.result = null
                    logHookHitThrottled(
                        "reelsViewerAdRenderBlock",
                        render,
                        "adList=${field.name}"
                    )
                    return
                }
            }
        }
    })
    Log.i(
        TAG,
        "$label: ${clazz.name}.render modelFields=${modelFields.size} listFields=${listFields.size}"
    )
}

// Derives the shoppable product payload class from the anchor components: the
// non-framework field type shared by every anchored renderable (their product
// payload holder). Anchors may individually lack the field, so any class that
// shares the majority type wins.
internal fun resolveShoppingPayloadType(anchorClasses: List<Class<*>>): Class<*>? {
    val counts = HashMap<Class<*>, Int>()
    for (clazz in anchorClasses) {
        clazz.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }
            .forEach { field ->
                counts.merge(field.type, 1, Int::plus)
            }
    }
    return counts.entries
        .filter { (type, count) ->
            count >= 2 && !type.name.startsWith("java.") && !type.isPrimitive
        }
        .maxByOrNull { it.value }?.key
}

// Unconditional render block for the shoppable product cards. Same shape as
// hookReelsAdRenderable, but without the classifier check — every render of
// these components is a shopping card. Shares the hooked-methods set so the
// cached early install and the full pass never double-hook.
internal fun hookReelsShoppingRenderable(
    clazz: Class<*>,
    hookedRenderMethods: MutableSet<Method>,
    label: String
) {
    val render = clazz.declaredMethods.firstOrNull {
        it.name == "render" && !Modifier.isStatic(it.modifiers)
    } ?: return
    if (!hookedRenderMethods.add(render)) return
    reelsShoppingRenderableNames.add(clazz.name)
    render.isAccessible = true
    XposedBridge.hookMethod(render, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.result = null
            logHookHitThrottled("reelsShoppingCardBlock", render, "")
        }
    })
    Log.i(TAG, "$label: ${clazz.name}.render")
}

// The video-home item collection (576: X.4zE) is read exclusively through a
// static snapshot method that returns an ImmutableList copy of the current
// items (with copy-on-write caching). Every consumer — the reels pager, the
// adapter, the Litho sections — sees the collection through that snapshot, so
// filtering it removes ad items from all views at once. This catches ads that
// entered the collection through ANY path, including CSR loads that completed
// before the module's hooks were installed (the cold-start race).
internal fun installReelsCollectionFilter(
    classLoader: ClassLoader,
    bridge: DexKitBridge,
    classifier: ReelsAdClassifier
) {
    runCatching {
        bridge.findClass {
            matcher {
                usingStrings("videohome_insert_index_out_of_bounds")
            }
        }.forEach { candidate ->
            val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull() ?: return@forEach
            val snapshotMethods = clazz.declaredMethods.filter { method ->
                Modifier.isStatic(method.modifiers) &&
                    List::class.java.isAssignableFrom(method.returnType) &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == clazz
            }
            if (snapshotMethods.isEmpty()) {
                Log.w(TAG, "Reels collection snapshot method not found on ${clazz.name}")
                return@forEach
            }
            snapshotMethods.forEach { method ->
                hookReelsCollectionSnapshot(method, classifier)
            }
        }
    }.onFailure { Log.w(TAG, "Reels collection filter failed", it) }
}

internal fun hookReelsCollectionSnapshot(method: Method, classifier: ReelsAdClassifier) {
    method.isAccessible = true
    reelsGuardSnapshotSpecs.add("${method.declaringClass.name}#${method.name}")
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (param.throwable != null) return
            val snapshot = param.result as? List<*> ?: return
            val cached = reelsSnapshotMemo[snapshot]
            if (cached != null) {
                param.result = cached
                return
            }
            val kept = ArrayList<Any?>(snapshot.size)
            var removed = 0
            for (item in snapshot) {
                val classification = classifier.classificationOf(item)
                if (classification != null && classification in AD_CLASSIFICATION_VALUES) {
                    removed++
                } else {
                    kept.add(item)
                }
            }
            if (removed == 0) {
                reelsSnapshotMemo[snapshot] = snapshot
                return
            }
            val rebuilt = buildImmutableListLike(
                snapshot,
                kept,
                method.declaringClass.classLoader
            )
            if (rebuilt != null) {
                reelsSnapshotMemo[snapshot] = rebuilt
                param.result = rebuilt
                logHookHitThrottled(
                    "reelsCollectionFilter",
                    method,
                    "size=${snapshot.size} filtered=$removed kept=${kept.size}"
                )
            }
        }
    })
    Log.i(TAG, "Hooked Reels collection ad filter at ${method.declaringClass.name}.${method.name}")
}

internal val reelsClassificationProbeHits = AtomicInteger(0)
internal val reelsSponsoredLabelHits = AtomicInteger(0)

// Diagnostic probe: whenever a reel media model is classified as an ad, log
// the classification plus the call stack. Ads that bypass every known
// pipeline reveal themselves here — whoever renders or processes the ad item
// has to read its classification.
internal fun installReelsAdClassificationProbe(
    classLoader: ClassLoader,
    bridge: DexKitBridge,
    classifier: ReelsAdClassifier
) {
    runCatching {
        bridge.findMethod {
            matcher {
                returnType = classifier.enumClassName
                paramCount = 0
            }
        }.forEach { methodData ->
            val method = runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
                ?: return@forEach
            if (Modifier.isStatic(method.modifiers) || method.declaringClass.isInterface) return@forEach
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val value = param.result?.toString() ?: return
                    if (value !in AD_CLASSIFICATION_VALUES) return
                    val hits = reelsClassificationProbeHits.incrementAndGet()
                    if (hits > 12) return
                    val stack = Throwable().stackTrace.take(20)
                        .joinToString(" <- ") { "${it.className}.${it.methodName}" }
                    Log.i(
                        TAG,
                        "ReelsAdDiag classified=$value hits=$hits at " +
                            "${method.declaringClass.name}.${method.name} stack=$stack"
                    )
                }
            })
        }
    }.onFailure { Log.w(TAG, "Reels ad classification probe failed", it) }
}

// Diagnostic probe: the sponsored label component ("FbShortsAdsSponsoredLabel
// Component") is constructed only for ad items, so its constructor is a
// reliable render-time signal. Logs its ad model and the call stack.
internal fun installReelsSponsoredLabelProbe(
    classLoader: ClassLoader,
    bridge: DexKitBridge,
    classifier: ReelsAdClassifier
) {
    runCatching {
        bridge.findClass {
            matcher {
                usingStrings("FbShortsAdsSponsoredLabelComponent")
            }
        }.forEach { candidate ->
            val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull() ?: return@forEach
            clazz.declaredConstructors.forEach { ctor ->
                runCatching {
                    ctor.isAccessible = true
                    XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val hits = reelsSponsoredLabelHits.incrementAndGet()
                            if (hits > 6) return
                            val argInfo = param.args.joinToString(",") { arg ->
                                classifier.modelClassification(arg)?.let { cls ->
                                    "${arg?.javaClass?.simpleName}=$cls"
                                } ?: arg?.javaClass?.simpleName ?: "null"
                            }
                            val stack = Throwable().stackTrace.take(20)
                                .joinToString(" <- ") { "${it.className}.${it.methodName}" }
                            Log.i(TAG, "ReelsAdDiag sponsoredLabel hits=$hits args=[$argInfo] stack=$stack")
                        }
                    })
                    Log.i(TAG, "Hooked Reels sponsored label ctor ${clazz.name}")
                }
            }
        }
    }.onFailure { Log.w(TAG, "Reels sponsored label probe failed", it) }
}

// Async RTI (real-time intent) Reels ads: while watching organic reels, the
// viewer's FbShortsRealTimeIntentAdsDataController (576: X.50U) proactively
// requests a fresh ad from the server ("async_ads_request_type" =
// "IMMERSIVE_REAL_TIME_INTENT", plus the POE/post-roll interstitial variant)
// and injects the response's AD-classified items into the pager as full-page
// ads. These fetches run outside the gated instream pipeline entirely, so they
// are blocked at their builders: the Function0.invoke() methods that assemble
// the GraphQL request are no-op'd (their return value is discarded — they run
// through a Runnable SAM), so the ad response never arrives. This also covers
// the impRecord "INTERSTITIAL_N" ads, which come from the POE variant.
internal fun installReelsRtiAdBlock(classLoader: ClassLoader, bridge: DexKitBridge) {
    val targets = listOf(
        "IMMERSIVE_REAL_TIME_INTENT" to "Reels RTI ad fetch blocked",
        "POE_TRIGGERED_INTERSTITIAL" to "Reels POE interstitial fetch blocked"
    )
    for ((needle, label) in targets) {
        runCatching {
            bridge.findMethod {
                matcher {
                    usingStrings(needle)
                }
            }.forEach { methodData ->
                val method = runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
                    ?: return@forEach
                if (Modifier.isStatic(method.modifiers) || method.parameterCount != 0) return@forEach
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = null
                    }
                })
                Log.i(TAG, "$label: ${method.declaringClass.name}.${method.name}")
            }
        }.onFailure { Log.w(TAG, "$label resolution failed", it) }
    }
}

// Filters ad items out of the nested lists pushed into the reels pager. The
// pager push takes a list of wrapper objects (576: C76D), each holding its own
// item list in its only Iterable-typed field. Every invocation logs a
// throttled summary (item/model/ad counts plus a classification sample) so a
// path change or an unrecognized ad classification is visible in debug logs.
internal fun hookReelsPagerListPush(method: Method, classifier: ReelsAdClassifier) {
    method.isAccessible = true
    reelsGuardPagerPushSpecs.add("${method.declaringClass.name}#${method.name}")
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val wrappers = param.args.getOrNull(0) as? Iterable<*> ?: return
            var total = 0
            var withModel = 0
            var removed = 0
            var writeFailures = 0
            val sample = ArrayList<String>(8)
            val keptWrappers = ArrayList<Any?>()
            for (wrapper in wrappers) {
                val listField = resolveWrapperListField(wrapper?.javaClass)
                val items = if (listField != null) {
                    runCatching { listField.get(wrapper) }.getOrNull() as? Iterable<*>
                } else null
                if (items == null) {
                    keptWrappers.add(wrapper)
                    continue
                }
                val keptItems = ArrayList<Any?>()
                var ads = 0
                for (item in items) {
                    total++
                    val classification = classifier.classificationOf(item)
                    if (classification != null) {
                        withModel++
                        if (sample.size < 8) sample.add("${item?.javaClass?.simpleName}=${classification}")
                    }
                    if (classification != null && classification in AD_CLASSIFICATION_VALUES) {
                        ads++
                    } else {
                        keptItems.add(item)
                    }
                }
                when {
                    ads == 0 -> keptWrappers.add(wrapper)
                    // Ad-only wrapper: drop it from the outer list outright —
                    // rewriting param.args is guaranteed to take effect, even
                    // when the reflective write to the wrapper's final list
                    // field is rejected by the runtime.
                    keptItems.isEmpty() -> removed += ads
                    else -> {
                        val rebuilt = buildImmutableListLike(
                            items,
                            keptItems,
                            method.declaringClass.classLoader
                        )
                        val applied = rebuilt != null && runCatching {
                            listField?.set(wrapper, rebuilt)
                            true
                        }.getOrDefault(false)
                        if (applied) {
                            removed += ads
                        } else {
                            writeFailures++
                        }
                        keptWrappers.add(wrapper)
                    }
                }
            }
            if (keptWrappers.size != wrappers.count()) {
                // Never null out the argument: the hooked method declares its
                // parameter as a Kotlin non-null List, so a null (or a failed
                // rebuild) must leave the original list untouched — the
                // downstream render block still suppresses the ad page.
                val rebuilt = buildImmutableListLike(
                    param.args[0],
                    keptWrappers,
                    method.declaringClass.classLoader
                )
                if (rebuilt != null) {
                    param.args[0] = rebuilt
                }
            }
            logHookHitThrottled(
                "reelsPagerPush",
                method,
                "items=$total withModel=$withModel adsRemoved=$removed writeFailures=$writeFailures " +
                    "droppedWrappers=${wrappers.count() - keptWrappers.size} sample=[${sample.joinToString(", ")}]"
            )
        }
    })
    Log.i(TAG, "Hooked Reels pager ad filter at ${method.declaringClass.name}.${method.name}")
}

// Persists the reels hook targets discovered by the full DexKit pass so the
// next launch of the same Facebook build can install them right after
// Application.attach — before the cold-start reels render that flashes an ad
// and only disappears once the render block arms seconds later.
@Volatile
private var lastSavedReelsGuardCachePayload: String? = null

// Opt 3.1 & 3.2: Asynchronous background cache serialization and redundant write suppression
fun saveReelsGuardCache(context: Context, hostVersionName: String) {
    if (hostVersionName.isBlank()) return
    val renderables = reelsGuardRenderableNames.toList().distinct()
    val shoppingRenderables = reelsShoppingRenderableNames.toList().distinct()
    val interfaces = reelsGuardModelInterfaceSpecs
    if (renderables.isEmpty() || interfaces.isEmpty()) return

    val enumClass = reelsGuardEnumClassName
    val pagerPush = reelsGuardPagerPushSpecs.toList().distinct()
    val snapshots = reelsGuardSnapshotSpecs.toList().distinct()

    val payload = "$hostVersionName|${feedGuardCacheModuleKey()}|${interfaces.joinToString(",")}|$enumClass|" +
        "${renderables.joinToString(",")}|${shoppingRenderables.joinToString(",")}|${pagerPush.joinToString(",")}|${snapshots.joinToString(",")}"

    if (payload == lastSavedReelsGuardCachePayload) {
        if (BuildConfig.DEBUG) Log.i(TAG, "Reels guard cache payload unchanged; suppressing write")
        return
    }

    cacheIoExecutor.execute {
        runCatching {
            val properties = Properties()
            properties.setProperty("version", hostVersionName)
            properties.setProperty("moduleVersion", feedGuardCacheModuleKey())
            properties.setProperty("modelInterfaces", interfaces.joinToString(","))
            properties.setProperty("enumClass", enumClass)
            properties.setProperty("renderables", renderables.joinToString(","))
            properties.setProperty("shoppingRenderables", shoppingRenderables.joinToString(","))
            properties.setProperty("pagerPush", pagerPush.joinToString(","))
            properties.setProperty("snapshots", snapshots.joinToString(","))
            File(context.cacheDir, REELS_GUARD_CACHE_FILE).outputStream().use { properties.store(it, null) }
            lastSavedReelsGuardCachePayload = payload
            Log.i(TAG, "Saved reels guard cache renderables=${renderables.size} shopping=${shoppingRenderables.size} pagerPush=${reelsGuardPagerPushSpecs.size} snapshots=${reelsGuardSnapshotSpecs.size}")
        }.onFailure { Log.w(TAG, "Failed to save reels guard cache", it) }
    }
}

// Cached specs already resolved by the early-install thread; each is retried
// until its class becomes loadable, then never again.
internal val reelsGuardResolvedSpecs: MutableSet<String> = Collections.synchronizedSet(HashSet())

// Set once the full DexKit pass installs the reels hooks; the early-install
// thread stops retrying because every target is then hooked anyway.
internal val reelsFullPassInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

// Loads the persisted reels guard targets and installs them on a background
// thread, retrying while the secondary dex finishes configuring (the cached
// class names fail Class.forName at attach time, exactly like the feed guard).
fun installReelsGuardFromCache(
    context: Context,
    classLoader: ClassLoader,
    hostVersionName: String
) {
    if (hostVersionName.isBlank()) return
    if (!reelsGuardCacheParsed) {
        val parsed = runCatching {
            val file = File(context.cacheDir, REELS_GUARD_CACHE_FILE)
            if (!file.exists()) return
            val properties = Properties()
            file.inputStream().use { properties.load(it) }
            if (hostVersionName != properties.getProperty("version")) {
                Log.i(TAG, "Reels guard cache stale for version=$hostVersionName; re-discovering")
                return
            }
            if (feedGuardCacheModuleKey() != properties.getProperty("moduleVersion")) {
                Log.i(TAG, "Reels guard cache stale for moduleVersion; re-discovering")
                return
            }
            reelsGuardCachedInterfaces = properties.getProperty("modelInterfaces").orEmpty()
                .split(',').filter { it.isNotBlank() }
            reelsGuardCachedRenderables = properties.getProperty("renderables").orEmpty()
                .split(',').filter { it.isNotBlank() }
            reelsGuardCachedShoppingRenderables = properties.getProperty("shoppingRenderables").orEmpty()
                .split(',').filter { it.isNotBlank() }
            reelsGuardCachedPagerPush = properties.getProperty("pagerPush").orEmpty()
                .split(',').filter { it.isNotBlank() }
            reelsGuardCachedSnapshots = properties.getProperty("snapshots").orEmpty()
                .split(',').filter { it.isNotBlank() }
            lastSavedReelsGuardCachePayload = "$hostVersionName|${feedGuardCacheModuleKey()}|${reelsGuardCachedInterfaces.joinToString(",")}|${properties.getProperty("enumClass").orEmpty()}|" +
                "${reelsGuardCachedRenderables.joinToString(",")}|${reelsGuardCachedShoppingRenderables.joinToString(",")}|${reelsGuardCachedPagerPush.joinToString(",")}|${reelsGuardCachedSnapshots.joinToString(",")}"
            true
        }.getOrDefault(false)
        if (!parsed || reelsGuardCachedInterfaces.isEmpty() || reelsGuardCachedRenderables.isEmpty()) {
            reelsGuardCacheParsed = true
            return
        }
        Log.i(
            TAG,
            "Loaded reels guard cache renderables=${reelsGuardCachedRenderables.size} " +
                "pagerPush=${reelsGuardCachedPagerPush.size} snapshots=${reelsGuardCachedSnapshots.size}"
        )
        reelsGuardCacheParsed = true
    }
    if (reelsGuardCachedInterfaces.isEmpty()) return
    Thread(
        {
            // Some cached classes (e.g. the pager controller) only become
            // loadable when the secondary dexes finish configuring, seconds
            // after attach — hence the long retry window. Each spec is
            // resolved at most once; the full DexKit pass makes the whole
            // thread redundant once it installs.
            val allSpecs = reelsGuardCachedRenderables +
                reelsGuardCachedShoppingRenderables +
                reelsGuardCachedPagerPush +
                reelsGuardCachedSnapshots
            for (attempt in 1..80) {
                if (reelsFullPassInstalled.get()) return@Thread
                // Shopping cards need no classifier, so they install first —
                // even before the model interfaces become loadable.
                for (name in reelsGuardCachedShoppingRenderables) {
                    if (!reelsGuardResolvedSpecs.add(name)) continue
                    val clazz = runCatching { Class.forName(name, false, classLoader) }.getOrNull()
                    if (clazz == null) {
                        reelsGuardResolvedSpecs.remove(name)
                        continue
                    }
                    runCatching {
                        hookReelsShoppingRenderable(clazz, reelsRenderHookedMethods, "Reels shopping card (cached)")
                    }
                }
                val classifier = buildCachedReelsClassifier(classLoader)
                if (classifier != null) {
                    for (name in reelsGuardCachedRenderables) {
                        if (!reelsGuardResolvedSpecs.add(name)) continue
                        val clazz = runCatching { Class.forName(name, false, classLoader) }.getOrNull()
                        if (clazz == null) {
                            reelsGuardResolvedSpecs.remove(name)
                            continue
                        }
                        runCatching {
                            hookReelsAdRenderable(clazz, classifier, reelsRenderHookedMethods, "Reels ad renderable (cached)")
                        }
                    }
                    for (spec in reelsGuardCachedPagerPush) {
                        if (!reelsGuardResolvedSpecs.add(spec)) continue
                        val method = resolveReelsGuardMethodSpec(classLoader, spec)
                        if (method == null) {
                            reelsGuardResolvedSpecs.remove(spec)
                            continue
                        }
                        runCatching { hookReelsPagerListPush(method, classifier) }
                    }
                    for (spec in reelsGuardCachedSnapshots) {
                        if (!reelsGuardResolvedSpecs.add(spec)) continue
                        val method = resolveReelsGuardMethodSpec(classLoader, spec)
                        if (method == null) {
                            reelsGuardResolvedSpecs.remove(spec)
                            continue
                        }
                        runCatching { hookReelsCollectionSnapshot(method, classifier) }
                    }
                }
                if (reelsGuardResolvedSpecs.containsAll(allSpecs)) {
                    Log.i(TAG, "Reels guard cache installed early: ${allSpecs.size} targets")
                    return@Thread
                }
                Thread.sleep(250)
            }
            Log.w(TAG, "Reels guard cache install incomplete; full DexKit pass will finish it")
        },
        "FbarrReelsGuardInit"
    ).start()
}

internal fun buildCachedReelsClassifier(classLoader: ClassLoader): ReelsAdClassifier? {
    val pairs = reelsGuardCachedInterfaces.mapNotNull { spec ->
        val idx = spec.indexOf('#')
        if (idx <= 0) return@mapNotNull null
        runCatching {
            val iface = Class.forName(spec.substring(0, idx), false, classLoader)
            val method = iface.getDeclaredMethod(spec.substring(idx + 1))
            method.isAccessible = true
            iface to method
        }.getOrNull()
    }
    if (pairs.size != reelsGuardCachedInterfaces.size) return null
    return ReelsAdClassifier(pairs, emptySet()).also { it.enumClassName = reelsGuardEnumClassName }
}

// Pure log probes over the two candidate pipelines for full-page Reels ads:
// the instream/postloop fetch state machine ("FBFetchReelsVideoAdsQuery") and
// the reels ad impression recorder ("ReelsAdImpRecord"). Whichever fires while
// a full-page ad is visible identifies the real insertion pipeline.
internal fun installReelsAdPipelineProbes(classLoader: ClassLoader, bridge: DexKitBridge) {
    val userSessionClass = runCatching {
        Class.forName("com.facebook.auth.usersession.FbUserSession", false, classLoader)
    }.getOrNull()
    val futureClass = runCatching {
        Class.forName("com.google.common.util.concurrent.ListenableFuture", false, classLoader)
    }.getOrNull()

    // Impression recorder: constructed exactly when a reels ad is displayed.
    runCatching {
        bridge.findClass {
            matcher {
                usingStrings("ReelsAdImpRecord(sessionId=")
            }
        }.forEach { candidate ->
            val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull() ?: return@forEach
            clazz.declaredConstructors.forEach { constructor ->
                constructor.isAccessible = true
                XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val count = reelsAdDiagnosticsLogged.incrementAndGet()
                        if (count > 80) return
                        Log.i(
                            TAG,
                            "ReelsAdDiag impRecord ${clazz.name} args=${formatDiagArgs(param.args)}"
                        )
                    }
                })
            }
            Log.i(TAG, "Hooked Reels ad impression recorder ${clazz.name}")
        }
    }.onFailure { Log.w(TAG, "Reels ad impression recorder not found", it) }

    // Instream/postloop fetch pipeline classes.
    runCatching {
        bridge.findClass {
            matcher {
                usingStrings("FBFetchReelsVideoAdsQuery")
            }
        }.forEach { candidate ->
            val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull() ?: return@forEach
            var hooked = 0
            (clazz.declaredMethods + clazz.methods).filter { method ->
                !Modifier.isStatic(method.modifiers) && !method.isSynthetic && !method.isBridge
            }.forEach { method ->
                val isFetch = futureClass != null && method.returnType == futureClass
                val isTrigger = userSessionClass != null &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.firstOrNull() == userSessionClass
                if (!isFetch && !isTrigger) return@forEach
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val count = reelsAdDiagnosticsLogged.incrementAndGet()
                        if (count > 80) return
                        Log.i(
                            TAG,
                            "ReelsAdDiag ${if (isFetch) "fetch" else "trigger"} " +
                                "${clazz.name}.${method.name} args=${formatDiagArgs(param.args)}"
                        )
                    }
                })
                hooked++
            }
            Log.i(TAG, "Hooked $hooked Reels instream pipeline method(s) in ${clazz.name}")
        }
    }.onFailure { Log.w(TAG, "Reels instream pipeline classes not found", it) }
}

// Walks the component's fields to capture the reels item model (C8YG → A04
// ad model C8Wm) and every reachable string field, so one reproduction shows
// the full ad payload chain.
internal fun describeReelsAdModelChain(root: Any) {
    runCatching {
        val rootClass = root.javaClass
        val fields = rootClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        fields.forEach { field ->
            field.isAccessible = true
            val value = runCatching { field.get(root) }.getOrNull() ?: return@forEach
            val typeName = value.javaClass.name
            val description = when {
                value is String -> "\"${value.take(160)}\""
                value.javaClass.name.contains("FbUserSession") -> "userSession"
                else -> {
                    val inner = value.javaClass.declaredFields
                        .filter { !Modifier.isStatic(it.modifiers) }
                        .mapNotNull { innerField ->
                            innerField.isAccessible = true
                            val innerValue = runCatching { innerField.get(value) }.getOrNull()
                            innerValue?.let { "  ${innerField.name}(${innerField.type.simpleName})=${formatDiagValue(it).take(120)}" }
                        }
                        .joinToString("\n")
                    "$typeName {\n$inner\n}"
                }
            }
            Log.i(TAG, "ReelsAdDiag field ${field.name}($typeName)=${description.take(600)}")
        }
    }.onFailure { Log.w(TAG, "ReelsAdDiag chain walk failed", it) }
}

internal fun hookListBuilderAppend(method: Method, inspector: AdStoryInspector) {
    val listArgIndex = method.listParameterIndexes().singleOrNull()
    if (listArgIndex == null) {
        Log.w(
            TAG,
            "Skipping list append hook because ${method.declaringClass.name}.${method.name} does not expose exactly one List parameter"
        )
        return
    }

    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val list = param.args.getOrNull(listArgIndex) as? List<*>
            param.setObjectExtra(BEFORE_SIZE_EXTRA, list?.size ?: -1)
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            val beforeSize = param.getObjectExtra(BEFORE_SIZE_EXTRA) as? Int ?: return
            val list = param.args.getOrNull(listArgIndex) as? MutableList<Any?> ?: return
            if (beforeSize < 0 || beforeSize > list.size) return

            var removed = 0
            for (index in list.lastIndex downTo beforeSize) {
                if (inspector.containsAdStory(list[index])) {
                    list.removeAt(index)
                    removed++
                }
            }

            if (removed > 0) {
                Log.i(TAG, "Removed $removed ad item(s) from upstream list append")
            }
        }
    })
}

internal fun hookPluginPackFallback(method: Method, inspector: AdStoryInspector) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (isMarketplaceAdsPluginPack(param.thisObject)) {
                Log.i(TAG, "Returning an empty plugin pack for marketplace ads (${method.declaringClass.name})")
                param.result = arrayListOf<Any?>()
                return
            }
            if (inspector.containsAdStory(param.thisObject)) {
                Log.i(TAG, "Returning an empty plugin pack for an ad-backed story")
                param.result = arrayListOf<Any?>()
            }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            if (isMarketplaceAdsPluginPack(param.thisObject)) return
            val result = param.result as? MutableList<Any?> ?: return
            val removed = filterAdItems(result, inspector)
            if (removed > 0) {
                Log.i(TAG, "Removed $removed ad plugin item(s)")
            }
        }
    })
}

internal fun hookStoryPoolAdd(
    method: Method,
    feedItemInspector: FeedItemInspector,
    logAllowedItems: Boolean = false
) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val item = param.args.getOrNull(0)
            val blockReason = feedItemInspector.storyPoolBlockReason(item)
            if (blockReason == null) {
                // Opt 1.3: Lazy evaluation for diagnostic string formatting in debug builds
                if (BuildConfig.DEBUG && logAllowedItems && item != null) {
                    logHookHitThrottled(
                        "shortsPoolAddAllowed",
                        method,
                        feedItemInspector.describe(item)
                    )
                } else if (BuildConfig.DEBUG && feedItemInspector.isSponsoredFeedItem(item)) {
                    logHookHitThrottled("storyPoolBroadAllowed", method, feedItemInspector.describe(item))
                }
                return
            }

            param.result = false
            logHookHitThrottled(
                if (blockReason == "strict") "storyPoolStrictBlock" else "storyPoolBroadNetworkBlock",
                method,
                if (BuildConfig.DEBUG) feedItemInspector.describe(item) else null
            )
        }
    })
}

internal fun hookInstreamBannerEligibility(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            logHookHitThrottled("bannerState", method)
            param.result = false
        }
    })
}

internal fun hookIndicatorPillAdEligibility(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val pluginSlot = param.args.getOrNull(2)?.toString() ?: "unknown"
            logHookHitThrottled("indicatorPill", method, "slot=$pluginSlot")
            param.result = false
        }
    })
}

internal fun hookReelsBannerRender(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            logHookHitThrottled("reelsBannerRender", method)
            param.result = null
        }
    })
}

// Opt 1.2: Direct CharSequence checks avoiding intermediate lowercase/trim String allocations
internal fun isReelsShoppingStickerMarkerText(value: CharSequence?): Boolean {
    if (value.isNullOrBlank()) return false
    if (!value.contains(" - ") || !value.contains(", ")) return false
    val trimmed = value.trim()
    return REELS_SHOPPING_STICKER_CTA_TOKENS.any { token -> trimmed.endsWith(token, ignoreCase = true) }
}

// Marketplace sponsored tiles are React Native content rendered from Relay
// data, so view-level hiding cannot reclaim the grid cell (Yoga ignores
// View.visibility) and a visibility guard against Yoga re-layouts causes an
// ANR. The tiles ride the organic home feed queries, so the module instead
// (1) blocks the dedicated ads fetches in the RN Networking module and
// (2) flips the server-honoured ad-skip variables in the organic feed
// requests — see installMarketplaceAdsQueryBlock.


internal fun hideReelsShoppingSticker(view: View, reason: String): Boolean {
    // The sticker is a small self-contained pill (thumbnail + product title +
    // CTA) mounted as one Litho ComponentHost, so hiding the host itself —
    // instead of walking up to a full-card target — removes the whole pill
    // without touching the surrounding reel surface.
    var hidden = false
    if (view.visibility != View.GONE) {
        view.visibility = View.GONE
        hidden = true
    }
    view.minimumHeight = 0
    view.layoutParams?.let { params ->
        params.height = 0
        view.layoutParams = params
        hidden = true
    }
    view.requestLayout()
    if (hidden) {
        Log.i(
            TAG,
            "Hid reels shopping sticker via $reason view=${view.javaClass.name} " +
                "desc=${view.contentDescription?.toString()?.take(120)}"
        )
    }
    // Litho can re-bind the same mount and restore visibility after layout;
    // re-assert once the frame settles.
    view.post {
        if (view.visibility != View.GONE) {
            view.visibility = View.GONE
            view.requestLayout()
        }
    }
    return hidden
}


internal fun hookSponsoredPoolAdd(method: Method): Boolean {
    if (!sponsoredPoolMethodsHooked.add(methodHookKey(method))) {
        return false
    }
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.result = false
            logHookHitThrottled("sponsoredPoolBlock", method)
        }
    })
    return true
}

internal fun hookSponsoredStoryNext(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.result = null
            Log.i(TAG, "Blocked sponsored story vending from feed manager")
        }
    })
}

internal fun hookSponsoredStoryListMethods(managerClass: Class<*>) {
    var hooked = 0
    managerClass.declaredMethods
        .filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                isSponsoredStoryListMethod(method)
        }
        .forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    buildEmptyListReturn(method.returnType)?.let { emptyResult ->
                        param.result = emptyResult
                    }
                }
            })
            hooked++
        }
    Log.i(TAG, "Hooked $hooked sponsored story list method(s) on ${managerClass.name}")
}

internal fun isSponsoredStoryListMethod(method: Method): Boolean {
    if (method.parameterCount > 2) return false
    if (!Iterable::class.java.isAssignableFrom(method.returnType) &&
        method.returnType.name != "com.google.common.collect.ImmutableList"
    ) {
        return false
    }
    return method.parameterTypes.all { type ->
        type == Int::class.javaPrimitiveType ||
            type == Long::class.javaPrimitiveType ||
            type == Boolean::class.javaPrimitiveType
    }
}

internal fun buildEmptyListReturn(returnType: Class<*>): Any? {
    if (returnType.name == "com.google.common.collect.ImmutableList") {
        return runCatching {
            val of = returnType.getDeclaredMethod("of")
            of.isAccessible = true
            of.invoke(null)
        }.getOrNull()
    }
    return when {
        returnType.isAssignableFrom(ArrayList::class.java) -> arrayListOf<Any?>()
        Iterable::class.java.isAssignableFrom(returnType) -> emptyList<Any?>()
        else -> null
    }
}

internal fun hookStoryAdsMerge(method: Method, source: String) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val originalBuckets = param.args.getOrNull(2)
            if (originalBuckets != null) {
                param.result = originalBuckets
                Log.i(TAG, "Blocked story ad bucket merge in $source")
            }
        }
    })
}

internal fun hookStoryAdsNoOp(method: Method, reason: String, source: String) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.result = null
            Log.i(TAG, "Blocked $reason in $source")
        }
    })
}

internal fun hookStoryAdProvider(provider: StoryAdProviderHooks) {
    if (!storyAdProviderClassesHooked.add(provider.providerClass.name)) return

    val hooked = ArrayList<String>()

    provider.mergeMethod?.let { method ->
        hookStoryAdsMerge(method, provider.providerClass.name)
        hooked.add("merge")
    }
    provider.fetchMoreAdsMethod?.let { method ->
        hookStoryAdsNoOp(method, "story ad fetchMoreAds", provider.providerClass.name)
        hooked.add("fetchMoreAds")
    }
    provider.deferredUpdateMethod?.let { method ->
        hookStoryAdsNoOp(method, "story ad deferred update", provider.providerClass.name)
        hooked.add("deferredUpdate")
    }
    provider.insertionTriggerMethod?.let { method ->
        hookStoryAdsNoOp(method, "story ad insertion trigger", provider.providerClass.name)
        hooked.add("insertionTrigger")
    }

    if (hooked.isNotEmpty()) {
        Log.i(TAG, "Hooked story ad provider ${provider.providerClass.name}: ${hooked.joinToString()}")
    }
}

internal fun hookSponsoredPoolListMethods(poolClass: Class<*>) {
    var hooked = 0
    poolClass.declaredMethods
        .filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 0 &&
                List::class.java.isAssignableFrom(method.returnType)
        }
        .forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = arrayListOf<Any?>()
                }
            })
            hooked++
        }
    Log.i(TAG, "Hooked $hooked feed pool list method(s) on ${poolClass.name}")
}

internal fun hookSponsoredPoolResultMethods(poolClass: Class<*>) {
    var hooked = 0
    poolClass.declaredMethods
        .filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                isSponsoredResultCarrier(method.returnType) &&
                (
                    method.parameterCount == 0 ||
                        (method.parameterCount == 1 && method.parameterTypes[0] == Boolean::class.javaPrimitiveType)
                    )
        }
        .forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    buildSponsoredEmptyResult(method.returnType)?.let { emptyResult ->
                        param.result = emptyResult
                    }
                }
            })
            hooked++
        }
    Log.i(TAG, "Hooked $hooked feed pool result method(s) on ${poolClass.name}")
}

internal fun isSponsoredResultCarrier(type: Class<*>): Boolean {
    val constructor = type.declaredConstructors.firstOrNull { it.parameterCount == 2 } ?: return false
    val reasonType = constructor.parameterTypes.getOrNull(1) ?: return false
    return reasonType.enumConstants?.any { it.toString() == "SPONSORED_GET_NEXT_RETURN_NULL" } == true
}

internal fun buildSponsoredEmptyResult(type: Class<*>): Any? {
    val constructor = type.declaredConstructors.firstOrNull { it.parameterCount == 2 } ?: return null
    val reasonType = constructor.parameterTypes.getOrNull(1) ?: return null
    val emptyReason = reasonType.enumConstants?.firstOrNull { it.toString() == "SPONSORED_GET_NEXT_RETURN_NULL" }
        ?: reasonType.enumConstants?.firstOrNull { it.toString() == "FAIL" }
        ?: return null
    constructor.isAccessible = true
    return constructor.newInstance(null, emptyReason)
}

internal fun installReelsStoryHooksPipeline(
    classLoader: ClassLoader,
    bridge: DexKitBridge,
    hooks: ResolvedHooks,
    feedItemInspector: FeedItemInspector
): Boolean {
    var installedAny = false

    if (ENABLE_UPSTREAM_REELS_AD_HOOKS && hooks.adKindEnumClass != null && hooks.listBuilderAppendMethod != null) {
        runCatching {
            val inspector = AdStoryInspector(hooks.adKindEnumClass)
            hookListBuilderAppend(hooks.listBuilderAppendMethod, inspector)
            hooks.listBuilderFactoryMethod?.let { hookListResultFilter(it, "list factory", inspector) }
            hooks.pluginPackBuildMethods.forEach { hookPluginPackFallback(it, inspector) }
            installedAny = true
        }.onFailure { Log.e(TAG, "Failed upstream reels ad hooks", it) }
    } else if (ENABLE_UPSTREAM_REELS_AD_HOOKS) {
        Log.w(TAG, "Upstream Reels targets unresolved; continuing with independent feed ad hooks")
    } else {
        Log.i(TAG, "Skipped upstream Reels list/plugin hooks to preserve feed Reels carousels")
    }

    hooks.instreamBannerEligibilityMethod?.let {
        runCatching { hookInstreamBannerEligibility(it); installedAny = true }
    }
    hooks.indicatorPillAdEligibilityMethod?.let {
        runCatching { hookIndicatorPillAdEligibility(it); installedAny = true }
    }
    hooks.reelsBannerRenderMethods.forEach { method ->
        runCatching { hookReelsBannerRender(method); installedAny = true }
            .onFailure { Log.e(TAG, "Failed to hook Reels banner render ${method.declaringClass.name}.${method.name}", it) }
    }

    runCatching { installReelsAdDiagnostics(classLoader, bridge) }
        .onFailure { Log.w(TAG, "Failed to install Reels ad diagnostics", it) }

    if (ENABLE_STORY_POOL_ADD_HOOKS) {
        val shortsPoolClassNames = runCatching {
            bridge.findClass {
                matcher { usingStrings("FbShorts Pool") }
            }.map { it.name }.toSet()
        }.getOrDefault(emptySet())
        Log.i(TAG, "Shorts pool classes for diagnostics: $shortsPoolClassNames")
        hooks.storyPoolAddMethods.forEach { method ->
            val logAllowed = method.declaringClass.name in shortsPoolClassNames
            runCatching { hookStoryPoolAdd(method, feedItemInspector, logAllowed); installedAny = true }
                .onFailure { Log.e(TAG, "Failed to hook story pool add ${method.declaringClass.name}.${method.name}", it) }
        }
    } else {
        Log.i(TAG, "Skipped story pool add hooks to isolate feed Reels carousel loading")
    }

    hooks.storyAdProviders.forEach { provider ->
        runCatching { hookStoryAdProvider(provider); installedAny = true }
            .onFailure { Log.e(TAG, "Failed to hook story ad source ${provider.providerClass.name}", it) }
    }

    return installedAny
}

