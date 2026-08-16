package tn.loukious.facebookappadsremover

import android.app.Activity
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap

internal val sweepPendingRoots = Collections.synchronizedMap(java.util.WeakHashMap<View, Long>())

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

internal fun hideLikelyAdContainer(view: View, reason: String): Boolean {
    val root = view.rootView
    val target =
        if (shouldUseExplicitFeedMarkerCardTarget(view)) {
            resolveLikelyExplicitFeedAdCardTarget(view) ?: run {
                Logger.i(
                    TAG,
                    "Skipped explicit feed ad hide via $reason because no safe full-card target was found"
                )
                return false
            }
        } else if (shouldUseFeedMarkerCardTarget(view)) {
            resolveLikelyFeedMarkerCardTarget(view) ?: run {
                Logger.i(
                    TAG,
                    "Skipped feed marker hide via $reason because no safe full-card target was found"
                )
                return false
            }
        } else {
            resolveLikelyAdContainerTarget(view)
        }
    return hideResolvedAdSurfaceTarget(
        target = target,
        source = view,
        root = root,
        reason = reason,
        forceCollapseHeight = false
    )
}

internal fun hideLikelyExplicitFeedAdCardContainer(view: View, reason: String): Boolean {
    val target = resolveLikelyExplicitFeedAdCardTarget(view) ?: return false
    return hideResolvedAdSurfaceTarget(
        target = target,
        source = view,
        root = view.rootView,
        reason = "$reason explicit feed card",
        forceCollapseHeight = true
    )
}

internal fun hideResolvedAdSurfaceTarget(
    target: View,
    source: View,
    root: View?,
    reason: String,
    forceCollapseHeight: Boolean
): Boolean {
    var hidden = false
    if (target.visibility != View.GONE) {
        target.visibility = View.GONE
        hidden = true
    }
    target.minimumHeight = 0
    target.layoutParams?.let { params ->
        if (
            forceCollapseHeight ||
            target !== source ||
            isLikelyBannerSized(target, root) ||
            isPotentialNativeGameAdView(target) ||
            isPotentialFeedAdMarkerView(source) ||
            isPotentialExplicitFeedAdMarkerView(source)
        ) {
            params.height = 0
            target.layoutParams = params
            hidden = true
        }
    }
    target.requestLayout()

    if (hidden) {
        Logger.i(
            TAG,
            "Hid ad surface via $reason target=${target.javaClass.name} bounds=${target.left},${target.top},${target.right},${target.bottom}"
        )
    }
    return hidden
}

internal fun hideLikelyFeedReelCtaAdContainer(view: View, reason: String): Boolean {
    val target = resolveLikelyFeedReelCtaAdContainerTarget(view) ?: return false
    var hidden = false
    if (target.visibility != View.GONE) {
        target.visibility = View.GONE
        hidden = true
    }
    target.minimumHeight = 0
    target.layoutParams?.let { params ->
        params.height = 0
        target.layoutParams = params
        hidden = true
    }
    target.requestLayout()

    if (hidden) {
        Logger.i(
            TAG,
            "Hid ad surface via $reason reelCtaTarget=${target.javaClass.name} bounds=${target.left},${target.top},${target.right},${target.bottom}"
        )
    }
    return hidden
}

internal fun isLikelyExplicitFeedAdCardContainer(view: View): Boolean {
    val root = view.rootView ?: return false
    val rootWidth = root.width.takeIf { it > 0 } ?: return false
    val rootHeight = root.height.takeIf { it > 0 } ?: return false
    return isLikelyExplicitFeedAdCardContainer(view, rootWidth, rootHeight)
}

internal fun isLikelyExplicitFeedAdCardContainer(view: View, rootWidth: Int, rootHeight: Int): Boolean {
    if (view !is ViewGroup) return false

    val width = view.width
    val height = view.height
    if (width < (rootWidth * 0.82f).toInt()) return false
    if (height < maxOf(420, (rootHeight * 0.18f).toInt())) return false
    if (height > (rootHeight * 0.96f).toInt()) return false

    val location = IntArray(2)
    val topOnScreen = runCatching {
        view.getLocationOnScreen(location)
        location[1]
    }.getOrDefault(view.top)
    val bottomOnScreen = topOnScreen + height

    if (topOnScreen < (rootHeight * 0.04f).toInt()) return false
    if (bottomOnScreen > (rootHeight * 0.98f).toInt()) return false

    val signals = collectExplicitFeedAdCardSignals(view)
    return signals.hasHideAd &&
        (signals.hasAdLabel || signals.hasSharedLink || signals.hasStrongCta)
}

internal fun isLikelyFeedReelCtaAdContainer(view: View, rootWidth: Int, rootHeight: Int): Boolean {
    val width = view.width
    val height = view.height
    if (width < (rootWidth * 0.82f).toInt()) return false
    if (height < (rootHeight * 0.45f).toInt() || height > (rootHeight * 0.92f).toInt()) return false

    val location = IntArray(2)
    val topOnScreen = runCatching {
        view.getLocationOnScreen(location)
        location[1]
    }.getOrDefault(view.top)
    if (topOnScreen < (rootHeight * 0.08f).toInt()) return false

    val signals = collectFeedReelCtaAdSignals(view)
    return signals.hasSharedLink &&
        signals.hasSendMessageCta &&
        (signals.hasReelSurface || signals.hasLeadGenPrompt)
}

internal fun isLikelyBannerSized(view: View, root: View?): Boolean {
    val rootHeight = root?.height?.takeIf { it > 0 } ?: return view.height in 1..360
    val height = view.height
    if (height <= 0 || height > maxOf(360, rootHeight / 3)) return false
    val location = IntArray(2)
    return runCatching {
        view.getLocationOnScreen(location)
        location[1] + height > rootHeight / 2
    }.getOrDefault(true)
}

internal fun isPotentialExplicitFeedAdMarkerView(view: View?): Boolean {
    if (view == null) return false
    return collectViewMarkerTexts(view).any(::isExplicitFeedAdMarkerText)
}

internal fun isPotentialFeedAdMarkerView(view: View?): Boolean {
    if (view == null) return false
    return collectViewMarkerTexts(view).any(::isFeedAdMarkerText)
}

internal fun isPotentialFeedReelCtaAdMarkerView(view: View?): Boolean {
    if (view == null) return false
    return collectViewMarkerTexts(view).any(::isFeedReelCtaAdMarkerText)
}

internal fun isPotentialNativeGameAdView(view: View?): Boolean {
    val className = view?.javaClass?.name?.lowercase() ?: return false
    return className == "com.facebook.ads.adview" ||
        (className.endsWith(".adview") &&
            (className.startsWith("com.facebook.ads.") || className.contains("audiencenetwork"))) ||
        className.contains("adchoices")
}

internal fun isTopRightSmallControl(view: View, root: View): Boolean {
    val rootWidth = root.width.takeIf { it > 0 } ?: return false
    val rootHeight = root.height.takeIf { it > 0 } ?: return false
    val width = view.width
    val height = view.height
    if (width !in 1..260 || height !in 1..260) return false

    val location = IntArray(2)
    return runCatching {
        view.getLocationOnScreen(location)
        location[0] > rootWidth * 0.55f && location[1] < rootHeight * 0.32f
    }.getOrDefault(false)
}

internal fun resolveLikelyAdContainerTarget(view: View): View {
    val root = view.rootView ?: return view
    var current = view
    var selected = view
    val rootWidth = root.width.takeIf { it > 0 } ?: 0
    val rootHeight = root.height.takeIf { it > 0 } ?: 0

    while (true) {
        val parentView = current.parent as? View ?: break
        val parentClassName = parentView.javaClass.name
        if (parentClassName.contains("RecyclerView")) {
            break
        }

        val parentWidth = parentView.width
        val parentHeight = parentView.height
        val looksLikePostContainer =
            rootWidth > 0 &&
                rootHeight > 0 &&
                parentWidth >= (rootWidth * 0.82f).toInt() &&
                parentHeight > 0 &&
                parentHeight < (rootHeight * 0.72f).toInt()

        if (!looksLikePostContainer) {
            break
        }

        val currentHeight = current.height.takeIf { it > 0 } ?: parentHeight
        if (
            currentHeight > 0 &&
            parentHeight > maxOf((currentHeight * 1.25f).toInt(), currentHeight + 180)
        ) {
            break
        }

        selected = parentView
        current = parentView
    }

    return selected
}

internal fun resolveLikelyExplicitFeedAdCardTarget(view: View): View? {
    val root = view.rootView ?: return null
    val rootWidth = root.width.takeIf { it > 0 } ?: return null
    val rootHeight = root.height.takeIf { it > 0 } ?: return null

    var current: View? = view
    var best: View? = null
    var bestHeight = -1

    while (current != null) {
        if (isLikelyExplicitFeedAdCardContainer(current, rootWidth, rootHeight)) {
            val candidateHeight = current.height
            if (candidateHeight > bestHeight) {
                best = current
                bestHeight = candidateHeight
            }
        }

        val parentView = current.parent as? View ?: break
        current = parentView
    }

    return best
}

internal fun resolveLikelyFeedMarkerCardTarget(view: View): View? {
    val root = view.rootView ?: return null
    val rootWidth = root.width.takeIf { it > 0 } ?: return null
    val rootHeight = root.height.takeIf { it > 0 } ?: return null

    var current: View? = view
    var best: View? = null
    var bestHeight = -1

    while (current != null) {
        if (isSafeFeedMarkerCardCandidate(current, rootWidth, rootHeight)) {
            val candidateHeight = current.height
            if (candidateHeight > bestHeight) {
                best = current
                bestHeight = candidateHeight
            }
        }

        val parentView = current.parent as? View ?: break
        current = parentView
    }

    return best
}

internal fun resolveLikelyFeedReelCtaAdContainerTarget(view: View): View? {
    val root = view.rootView ?: return null
    val rootWidth = root.width.takeIf { it > 0 } ?: return null
    val rootHeight = root.height.takeIf { it > 0 } ?: return null

    var current: View? = view
    while (current != null) {
        if (isLikelyFeedReelCtaAdContainer(current, rootWidth, rootHeight)) {
            return current
        }
        val parentView = current.parent as? View ?: break
        current = parentView
    }
    return null
}

internal fun scheduleFeedRowSweep(view: View?, reason: String) {
    val subtree = view ?: return
    longArrayOf(60L, 500L, 1_500L, 3_000L).forEach { delayMs ->
        subtree.postDelayed({
            sweepGameAdSurface(subtree, reason)
        }, delayMs)
    }
}

internal fun scheduleGameAdSurfaceSweep(view: View?, reason: String) {
    val root = view?.rootView ?: view ?: return
    val now = System.currentTimeMillis()
    val lastScheduled = sweepPendingRoots[root] ?: 0L
    if (now - lastScheduled < 150L) return
    sweepPendingRoots[root] = now

    longArrayOf(0L, 250L, 1_000L, 2_500L, 5_000L).forEach { delayMs ->
        root.postDelayed({
            sweepGameAdSurface(root, reason)
        }, delayMs)
    }
}

internal fun shouldScheduleFeedRowSweep(parent: ViewGroup?, child: View?): Boolean {
    if (parent == null || child !is ViewGroup) return false
    return parent.javaClass.name.contains("RecyclerView")
}

internal fun shouldUseExplicitFeedMarkerCardTarget(view: View): Boolean {
    return isPotentialExplicitFeedAdMarkerView(view) || (view is TextView && isExplicitFeedAdMarkerText(view.text))
}

internal fun shouldUseFeedMarkerCardTarget(view: View): Boolean {
    return isPotentialFeedAdMarkerView(view) || (view is TextView && isFeedAdMarkerText(view.text))
}

internal fun sweepGameAdSurface(view: View?, reason: String): Boolean {
    if (view == null) return false

    var hidden = false
    if (view is android.webkit.WebView) {
        injectGameAdHidingScript(view)
    }
    if (isLikelyExplicitFeedAdCardContainer(view)) {
        hidden = hideLikelyExplicitFeedAdCardContainer(view, reason) || hidden
    }
    if (isPotentialNativeGameAdView(view) || isPotentialExplicitFeedAdMarkerView(view) || (ENABLE_FEED_UI_MARKER_FALLBACKS && (isPotentialFeedAdMarkerView(view) || (view is TextView && isAnyAdMarkerText(view.text))))) {
        hidden = hideLikelyAdContainer(view, reason) || hidden
    }
    if (ENABLE_FEED_UI_MARKER_FALLBACKS && isPotentialFeedReelCtaAdMarkerView(view)) {
        hidden = hideLikelyFeedReelCtaAdContainer(view, reason) || hidden
    }

    val group = view as? ViewGroup ?: return hidden
    for (index in 0 until group.childCount) {
        hidden = sweepGameAdSurface(group.getChildAt(index), reason) || hidden
    }
    return hidden
}

internal fun collectExplicitFeedAdCardSignals(root: View): ExplicitFeedAdCardSignals {
    val queue = java.util.ArrayDeque<View>()
    queue.add(root)

    var visited = 0
    var hasHideAd = false
    var hasAdLabel = false
    var hasSharedLink = false
    var hasStrongCta = false

    while (queue.isNotEmpty() && visited < 192 && !(hasHideAd && (hasAdLabel || hasSharedLink || hasStrongCta))) {
        val view = queue.removeFirst()
        visited++

        for (marker in collectViewMarkerTexts(view)) {
            val normalized = marker.lowercase()
            if (!hasHideAd && normalized.contains("hide ad")) hasHideAd = true
            if (!hasAdLabel && isExplicitFeedAdMarkerText(normalized)) hasAdLabel = true
            if (!hasSharedLink && normalized.contains("shared link:")) hasSharedLink = true
            if (!hasStrongCta && isExplicitFeedAdCtaText(normalized)) hasStrongCta = true
        }

        val group = view as? ViewGroup ?: continue
        for (index in 0 until group.childCount) {
            queue.addLast(group.getChildAt(index))
        }
    }

    return ExplicitFeedAdCardSignals(
        hasHideAd = hasHideAd,
        hasAdLabel = hasAdLabel,
        hasSharedLink = hasSharedLink,
        hasStrongCta = hasStrongCta
    )
}

internal fun collectFeedReelCtaAdSignals(root: View): FeedReelCtaAdSignals {
    val queue = java.util.ArrayDeque<View>()
    queue.add(root)

    var visited = 0
    var hasSharedLink = false
    var hasSendMessageCta = false
    var hasReelSurface = false
    var hasLeadGenPrompt = false

    while (queue.isNotEmpty() && visited < 128 && !(hasSharedLink && hasSendMessageCta && (hasReelSurface || hasLeadGenPrompt))) {
        val view = queue.removeFirst()
        visited++

        val className = view.javaClass.name.lowercase()
        val contentDescription = view.contentDescription?.toString() ?: ""
        val text = (view as? TextView)?.text?.toString() ?: ""
        val marker = "$className ${contentDescription.lowercase()} ${text.lowercase()}"

        if (!hasSharedLink && marker.contains("shared link:")) hasSharedLink = true
        if (!hasSendMessageCta && marker.contains("send message")) hasSendMessageCta = true
        if (!hasLeadGenPrompt &&
            (
                marker.contains("your business") ||
                    marker.contains("your ad")
                )
        ) {
            hasLeadGenPrompt = true
        }
        if (!hasReelSurface &&
            (
                marker.contains("reel") ||
                    className.contains("surfaceview") ||
                    className.contains("textureview") ||
                    className.contains("videoview")
                )
        ) {
            hasReelSurface = true
        }

        val group = view as? ViewGroup ?: continue
        for (index in 0 until group.childCount) {
            queue.addLast(group.getChildAt(index))
        }
    }

    return FeedReelCtaAdSignals(
        hasSharedLink = hasSharedLink,
        hasSendMessageCta = hasSendMessageCta,
        hasReelSurface = hasReelSurface,
        hasLeadGenPrompt = hasLeadGenPrompt
    )
}

internal fun collectViewMarkerTexts(view: View?): List<String> {
    if (view == null) return emptyList()

    val values = LinkedHashSet<String>()
    view.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(values::add)
    (view as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }?.let(values::add)

    runCatching {
        val info = view.createAccessibilityNodeInfo() ?: return@runCatching
        try {
            info.text?.toString()?.takeIf { it.isNotBlank() }?.let(values::add)
            info.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(values::add)
        } finally {
            info.recycle()
        }
    }

    return values.toList()
}

internal fun isAnyAdMarkerText(value: CharSequence?): Boolean {
    return isGameAdMarkerText(value) || isFeedAdMarkerText(value)
}

internal fun isExplicitFeedAdCtaText(value: CharSequence?): Boolean {
    if (value.isNullOrBlank()) return false
    val normalized = value.toString().lowercase()
    return EXPLICIT_FEED_AD_CTA_TOKENS.any { token: String -> normalized.contains(token) }
}

internal fun isExplicitFeedAdMarkerText(value: CharSequence?): Boolean {
    if (value.isNullOrBlank()) return false
    val normalized = value.toString().lowercase()
    return EXPLICIT_FEED_CARD_AD_MARKER_TOKENS.any { token: String -> normalized.contains(token) }
}

internal fun isFeedAdMarkerText(value: CharSequence?): Boolean {
    if (value.isNullOrBlank()) return false
    val normalized = value.toString().lowercase()
    return FEED_SURFACE_AD_MARKER_TOKENS.any { token: String -> normalized.contains(token) }
}

internal fun isFeedReelCtaAdMarkerText(value: CharSequence?): Boolean {
    if (value.isNullOrBlank()) return false
    val normalized = value.toString().lowercase()
    return FEED_REEL_CTA_AD_MARKER_TOKENS.any { token: String -> normalized.contains(token) }
}

internal fun isSafeFeedMarkerCardCandidate(view: View, rootWidth: Int, rootHeight: Int): Boolean {
    val width = view.width
    val height = view.height
    if (width < (rootWidth * 0.82f).toInt()) return false
    if (height < maxOf(360, (rootHeight * 0.18f).toInt())) return false
    if (height > (rootHeight * 0.82f).toInt()) return false

    val location = IntArray(2)
    val topOnScreen = runCatching {
        view.getLocationOnScreen(location)
        location[1]
    }.getOrDefault(view.top)
    val bottomOnScreen = topOnScreen + height

    if (topOnScreen < (rootHeight * 0.04f).toInt()) return false
    if (bottomOnScreen > (rootHeight * 0.96f).toInt()) return false

    return true
}

internal fun findRecyclerViewAncestor(view: View): Any? {
    var current: Any? = view
    repeat(80) {
        val value = current ?: return null
        if (value.javaClass.name == "androidx.recyclerview.widget.RecyclerView") {
            return value
        }
        current = runCatching {
            (value as? View)?.parent
        }.getOrNull()
    }
    return null
}

internal fun viewIdLabel(view: View): String {
    if (view.id == View.NO_ID) return "NO_ID"
    return runCatching { view.resources.getResourceName(view.id) }.getOrDefault(view.id.toString())
}

internal fun contextActivityForView(view: View): Activity? {
    var context = view.context
    var depth = 0
    while (depth < 8) {
        if (context is Activity) return context
        context = (context as? ContextWrapper)?.baseContext ?: return null
        depth++
    }
    return null
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

internal fun hookInstreamBannerEligibility(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            logHookHitThrottled("bannerState", method)
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
