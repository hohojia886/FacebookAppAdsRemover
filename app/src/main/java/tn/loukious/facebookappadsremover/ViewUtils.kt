package tn.loukious.facebookappadsremover

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView

fun findRecyclerViewAncestor(view: View): Any? {
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

fun scheduleFeedRowSweep(view: View?, reason: String) {
    val subtree = view ?: return
    longArrayOf(60L, 500L, 1_500L, 3_000L).forEach { delayMs ->
        subtree.postDelayed({
            sweepGameAdSurface(subtree, reason)
        }, delayMs)
    }
}

/*
// [2026-08-17 08:24] Project E: Original recursive implementation:
fun sweepGameAdSurface(view: View?, reason: String): Boolean {
    if (view == null) return false

    var hidden = false
    if (view is WebView) {
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
*/

fun sweepGameAdSurface(root: View?, reason: String): Boolean {
    if (root == null) return false

    val queue = java.util.ArrayDeque<View>()
    queue.add(root)
    var hidden = false

    while (queue.isNotEmpty()) {
        val view = queue.removeFirst()

        if (view is WebView) {
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

        val group = view as? ViewGroup
        if (group != null) {
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (child != null) {
                    queue.addLast(child)
                }
            }
        }
    }
    return hidden
}

fun injectGameAdHidingScript(webView: WebView) {
    webView.post {
        runCatching {
            webView.evaluateJavascript(GAME_AD_WEBVIEW_HIDE_SCRIPT, null)
        }
    }
}

fun hideLikelyAdContainer(view: View, reason: String): Boolean {
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

fun hideLikelyExplicitFeedAdCardContainer(view: View, reason: String): Boolean {
    val target = resolveLikelyExplicitFeedAdCardTarget(view) ?: return false
    return hideResolvedAdSurfaceTarget(
        target = target,
        source = view,
        root = view.rootView,
        reason = "$reason explicit feed card",
        forceCollapseHeight = true
    )
}

fun hideResolvedAdSurfaceTarget(
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

fun hideLikelyFeedReelCtaAdContainer(view: View, reason: String): Boolean {
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

fun resolveLikelyAdContainerTarget(view: View): View {
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

fun shouldUseFeedMarkerCardTarget(view: View): Boolean {
    return isPotentialFeedAdMarkerView(view) || (view is TextView && isFeedAdMarkerText(view.text))
}

fun shouldUseExplicitFeedMarkerCardTarget(view: View): Boolean {
    return isPotentialExplicitFeedAdMarkerView(view) || (view is TextView && isExplicitFeedAdMarkerText(view.text))
}

fun resolveLikelyExplicitFeedAdCardTarget(view: View): View? {
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

fun resolveLikelyFeedMarkerCardTarget(view: View): View? {
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

fun isSafeFeedMarkerCardCandidate(view: View, rootWidth: Int, rootHeight: Int): Boolean {
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

fun isLikelyExplicitFeedAdCardContainer(view: View): Boolean {
    val root = view.rootView ?: return false
    val rootWidth = root.width.takeIf { it > 0 } ?: return false
    val rootHeight = root.height.takeIf { it > 0 } ?: return false
    return isLikelyExplicitFeedAdCardContainer(view, rootWidth, rootHeight)
}

fun isLikelyExplicitFeedAdCardContainer(view: View, rootWidth: Int, rootHeight: Int): Boolean {
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

fun collectExplicitFeedAdCardSignals(root: View): ExplicitFeedAdCardSignals {
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
            /*
            // [2026-08-17 01:13] Original:
            val normalized = marker.lowercase()
            if (!hasHideAd && normalized.contains("hide ad")) hasHideAd = true
            if (!hasAdLabel && isExplicitFeedAdMarkerText(normalized)) hasAdLabel = true
            if (!hasSharedLink && normalized.contains("shared link:")) hasSharedLink = true
            if (!hasStrongCta && isExplicitFeedAdCtaText(normalized)) hasStrongCta = true
            */
            if (!hasHideAd && marker.contains("hide ad", ignoreCase = true)) hasHideAd = true
            if (!hasAdLabel && isExplicitFeedAdMarkerText(marker)) hasAdLabel = true
            if (!hasSharedLink && marker.contains("shared link:", ignoreCase = true)) hasSharedLink = true
            if (!hasStrongCta && isExplicitFeedAdCtaText(marker)) hasStrongCta = true
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

fun resolveLikelyFeedReelCtaAdContainerTarget(view: View): View? {
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

fun isLikelyFeedReelCtaAdContainer(view: View, rootWidth: Int, rootHeight: Int): Boolean {
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

fun collectFeedReelCtaAdSignals(root: View): FeedReelCtaAdSignals {
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

        /*
        // [2026-08-17 01:16] Original implementation:
        val className = view.javaClass.name.lowercase()
        val contentDescription = view.contentDescription?.toString().orEmpty().lowercase()
        val text = (view as? TextView)?.text?.toString().orEmpty().lowercase()
        val marker = "$className $contentDescription $text"

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
        */
        val className = view.javaClass.name
        val contentDescription = view.contentDescription?.toString().orEmpty()
        val text = (view as? TextView)?.text?.toString().orEmpty()

        if (!hasSharedLink && (className.contains("shared link:", ignoreCase = true) || contentDescription.contains("shared link:", ignoreCase = true) || text.contains("shared link:", ignoreCase = true))) hasSharedLink = true
        if (!hasSendMessageCta && (className.contains("send message", ignoreCase = true) || contentDescription.contains("send message", ignoreCase = true) || text.contains("send message", ignoreCase = true))) hasSendMessageCta = true
        
        if (!hasLeadGenPrompt) {
            val isLeadGen = className.contains("your business", ignoreCase = true) || contentDescription.contains("your business", ignoreCase = true) || text.contains("your business", ignoreCase = true) ||
                            className.contains("your ad", ignoreCase = true) || contentDescription.contains("your ad", ignoreCase = true) || text.contains("your ad", ignoreCase = true)
            if (isLeadGen) hasLeadGenPrompt = true
        }

        if (!hasReelSurface) {
            val isReel = className.contains("reel", ignoreCase = true) || contentDescription.contains("reel", ignoreCase = true) || text.contains("reel", ignoreCase = true) ||
                         className.contains("surfaceview", ignoreCase = true) || className.contains("textureview", ignoreCase = true) || className.contains("videoview", ignoreCase = true)
            if (isReel) hasReelSurface = true
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

fun isPotentialNativeGameAdView(view: View?): Boolean {
    val className = view?.javaClass?.name?.lowercase() ?: return false
    return className == "com.facebook.ads.adview" ||
        (className.endsWith(".adview") &&
            (className.startsWith("com.facebook.ads.") || className.contains("audiencenetwork"))) ||
        className.contains("adchoices")
}

fun collectViewMarkerTexts(view: View?): List<String> {
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

fun isPotentialFeedAdMarkerView(view: View?): Boolean {
    if (view == null) return false
    return collectViewMarkerTexts(view).any(::isFeedAdMarkerText)
}

fun isPotentialExplicitFeedAdMarkerView(view: View?): Boolean {
    if (view == null) return false
    return collectViewMarkerTexts(view).any(::isExplicitFeedAdMarkerText)
}

fun isPotentialFeedReelCtaAdMarkerView(view: View?): Boolean {
    if (view == null) return false
    return collectViewMarkerTexts(view).any(::isFeedReelCtaAdMarkerText)
}

fun isAnyAdMarkerText(value: CharSequence?): Boolean {
    return isGameAdMarkerText(value) || isFeedAdMarkerText(value)
}

fun isGameAdMarkerText(value: CharSequence?): Boolean {
    if (value.isNullOrBlank()) return false
    /*
    // [2026-08-17 01:14] Original implementation:
    val normalized = value.toString().lowercase()
    return normalized.contains("ads served by meta") ||
        normalized.contains("ad choices") ||
        normalized.contains("adchoices")
    */
    val str = value.toString()
    return str.contains("ads served by meta", ignoreCase = true) ||
        str.contains("ad choices", ignoreCase = true) ||
        str.contains("adchoices", ignoreCase = true)
}

fun isFeedAdMarkerText(value: CharSequence?): Boolean {
    if (value.isNullOrBlank()) return false
    /*
    // [2026-08-17 01:15] Original implementation:
    val normalized = value.toString().lowercase()
    return FEED_SURFACE_AD_MARKER_TOKENS.any { token -> normalized.contains(token) }
    */
    val str = value.toString()
    return FEED_SURFACE_AD_MARKER_TOKENS.any { token -> str.contains(token, ignoreCase = true) }
}

fun isExplicitFeedAdMarkerText(value: CharSequence?): Boolean {
    if (value.isNullOrBlank()) return false
    /*
    // [2026-08-17 01:15] Original implementation:
    val normalized = value.toString().lowercase()
    return EXPLICIT_FEED_CARD_AD_MARKER_TOKENS.any { token -> normalized.contains(token) }
    */
    val str = value.toString()
    return EXPLICIT_FEED_CARD_AD_MARKER_TOKENS.any { token -> str.contains(token, ignoreCase = true) }
}

fun isExplicitFeedAdCtaText(value: CharSequence?): Boolean {
    if (value.isNullOrBlank()) return false
    /*
    // [2026-08-17 01:15] Original implementation:
    val normalized = value.toString().lowercase()
    return EXPLICIT_FEED_AD_CTA_TOKENS.any { token -> normalized.contains(token) }
    */
    val str = value.toString()
    return EXPLICIT_FEED_AD_CTA_TOKENS.any { token -> str.contains(token, ignoreCase = true) }
}

fun isFeedReelCtaAdMarkerText(value: CharSequence?): Boolean {
    if (value.isNullOrBlank()) return false
    /*
    // [2026-08-17 01:15] Original implementation:
    val normalized = value.toString().lowercase()
    return FEED_REEL_CTA_AD_MARKER_TOKENS.any { token -> normalized.contains(token) }
    */
    val str = value.toString()
    return FEED_REEL_CTA_AD_MARKER_TOKENS.any { token -> str.contains(token, ignoreCase = true) }
}

fun isLikelyBannerSized(view: View, root: View?): Boolean {
    val rootHeight = root?.height?.takeIf { it > 0 } ?: return view.height in 1..360
    val height = view.height
    if (height <= 0 || height > maxOf(360, rootHeight / 3)) return false
    val location = IntArray(2)
    return runCatching {
        view.getLocationOnScreen(location)
        location[1] + height > rootHeight / 2
    }.getOrDefault(true)
}
