package tn.loukious.facebookappadsremover

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.ArrayDeque

internal data class NativeGameAdSignals(
    val hasAdMarker: Boolean,
    val hasHideButton: Boolean,
    val hasDownloadCta: Boolean,
    val hasExternalLink: Boolean
)

internal fun isPotentialNativeGameAdView(view: View): Boolean {
    if (view !is ViewGroup) return false
    val signals = collectNativeGameAdSignals(view)
    
    return signals.hasAdMarker && (signals.hasHideButton || signals.hasDownloadCta || signals.hasExternalLink)
}

internal fun collectNativeGameAdSignals(root: ViewGroup): NativeGameAdSignals {
    var hasAdMarker = false
    var hasHideButton = false
    var hasDownloadCta = false
    var hasExternalLink = false

    val queue = ArrayDeque<View>()
    queue.add(root)
    
    var count = 0
    while (queue.isNotEmpty() && count < 100) {
        val view = queue.removeFirst()
        count++

        if (view is TextView) {
            val text = view.text?.toString()?.lowercase() ?: ""
            if (isGameAdMarkerText(text)) hasAdMarker = true
            if (text.contains("hide ad")) hasHideButton = true
            if (text.contains("download") || text.contains("install")) hasDownloadCta = true
        }

        if (view.contentDescription?.toString()?.lowercase()?.contains("ad choices") == true) {
            hasAdMarker = true
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                queue.add(view.getChildAt(i))
            }
        }
    }

    return NativeGameAdSignals(hasAdMarker, hasHideButton, hasDownloadCta, hasExternalLink)
}

internal fun hideLikelyAdContainer(view: View, reason: String) {
    var current = view
    var depth = 0
    while (depth < 8) {
        val parent = current.parent as? ViewGroup ?: break
        if (isLikelyAdContainer(parent)) {
            if (parent.visibility != View.GONE) {
                Logger.i(TAG, "Hiding ad container ($reason): ${parent.javaClass.name}")
                parent.visibility = View.GONE
                parent.layoutParams?.let { 
                    it.width = 0
                    it.height = 0
                }
            }
            return
        }
        current = parent
        depth++
    }
}

internal fun isLikelyAdContainer(view: ViewGroup): Boolean {
    val name = view.javaClass.name
    return name.contains("AdLayout") || name.contains("AdContainer") || name.contains("NativeAd")
}

internal fun scheduleGameAdSurfaceSweep(view: View?, source: String) {
    val root = view?.rootView as? ViewGroup ?: return
    root.postDelayed({
        runCatching {
            sweepGameAdSurfaces(root, "$source-delayed")
        }
    }, 1000L)
}

internal fun sweepGameAdSurfaces(root: ViewGroup, source: String) {
    val queue = ArrayDeque<View>()
    queue.add(root)
    
    var count = 0
    while (queue.isNotEmpty() && count < 500) {
        val view = queue.removeFirst()
        count++

        if (isPotentialNativeGameAdView(view)) {
            hideLikelyAdContainer(view, "sweep-$source")
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                queue.add(view.getChildAt(i))
            }
        }
    }
}

internal fun isPotentialExplicitFeedAdMarkerView(view: View): Boolean {
    val description = view.contentDescription?.toString()?.lowercase() ?: ""
    if (isExplicitFeedAdMarkerText(description)) return true
    
    if (view is TextView) {
        val text = view.text?.toString()?.lowercase() ?: ""
        if (isExplicitFeedAdMarkerText(text)) return true
    }
    
    return false
}

internal fun isPotentialFeedAdMarkerView(view: View): Boolean {
    val description = view.contentDescription?.toString()?.lowercase() ?: ""
    if (isFeedAdMarkerText(description)) return true
    
    if (view is TextView) {
        val text = view.text?.toString()?.lowercase() ?: ""
        if (isFeedAdMarkerText(text)) return true
    }
    
    return false
}

internal fun isPotentialFeedReelCtaAdMarkerView(view: View): Boolean {
    val description = view.contentDescription?.toString()?.lowercase() ?: ""
    if (isFeedReelCtaAdMarkerText(description)) return true
    
    if (view is TextView) {
        val text = view.text?.toString()?.lowercase() ?: ""
        if (isFeedReelCtaAdMarkerText(text)) return true
    }
    
    return false
}

internal fun hideLikelyFeedReelCtaAdContainer(view: View, reason: String) {
    hideLikelyAdContainer(view, reason)
}

internal fun shouldScheduleFeedRowSweep(parent: ViewGroup?, child: View): Boolean {
    return parent != null && child.javaClass.name.contains("Feed")
}

internal fun scheduleFeedRowSweep(view: View, source: String) {
    // Optional: add logic to sweep feed rows for ads
}

internal data class FeedReelCtaAdSignals(
    val hasSharedLink: Boolean,
    val hasSendMessageCta: Boolean,
    val hasReelSurface: Boolean,
    val hasLeadGenPrompt: Boolean
)

internal fun collectViewMarkerTexts(view: View?): List<String> {
    if (view == null) return emptyList()

    val values = LinkedHashSet<String>()
    view.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(values::add)
    (view as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }?.let(values::add)

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
    if (bottomOnScreen > (rootHeight * 0.98f).toInt()) return false

    return true
}
