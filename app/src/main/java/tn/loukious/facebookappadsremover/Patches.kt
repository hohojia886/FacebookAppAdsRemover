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

fun installFacebookAdRemover(classLoader: ClassLoader, bridge: DexKitBridge): Boolean {
    return try {
        Log.i(TAG, "[Init] Starting hook install: $BUILD_MARKER")
        val hooks = resolveHooks(classLoader, bridge)
        if (!hooks.hasLoadedSecondaryDexTargets()) {
            Log.w(TAG, "[Init] Facebook secondary dex targets are not loaded yet; deferring hook installation")
            return false
        }

        val feedItemInspector = FeedItemInspector(hooks.storyPoolAddMethods.map { it.parameterTypes[0] })
        Log.i(TAG, "[Init] FeedItemInspector accessors ${feedItemInspector.describeAccessors()}")

        var feedInstalled = false
        var reelsStoryInstalled = false
        var gameAdsInstalled = false

        runCatching {
            feedInstalled = installFeedHooksPipeline(classLoader, bridge, hooks, feedItemInspector)
        }.onFailure {
            Log.e(TAG, "[Feed] Feed ad pipeline installation failed", it)
        }

        runCatching {
            reelsStoryInstalled = installReelsStoryHooksPipeline(classLoader, bridge, hooks, feedItemInspector)
        }.onFailure {
            Log.e(TAG, "[Reels] Reels/Story ad pipeline installation failed", it)
        }

        runCatching {
            gameAdsInstalled = installGameAdsHooksPipeline(classLoader, hooks)
        }.onFailure {
            Log.e(TAG, "[GameAds] Game ads pipeline installation failed", it)
        }

        runCatching {
            installMarketplaceAdRenderBlock(classLoader, bridge)
            installMarketplaceAdsQueryBlock(classLoader, bridge)
            installMarketplaceFeedResponseFilter(classLoader, bridge)
        }.onFailure {
            Log.w(TAG, "[Marketplace] Marketplace ad pipeline installation failed", it)
        }

        Log.i(
            TAG,
            "[Init] Pipeline installation summary: feed=$feedInstalled, reelsStory=$reelsStoryInstalled, gameAds=$gameAdsInstalled"
        )
        true
    } catch (t: Throwable) {
        Log.resolutionFailure(TAG, "[Error] Failed to install Facebook ad remover hooks", t)
        false
    }
}

internal fun ResolvedHooks.hasLoadedSecondaryDexTargets(): Boolean {
    return listBuilderAppendMethod != null ||
        pluginPackBuildMethods.isNotEmpty() ||
        feedCsrFilterHooks.isNotEmpty() ||
        lateFeedListHooks.isNotEmpty() ||
        storyPoolAddMethods.isNotEmpty() ||
        sponsoredPoolClass != null ||
        sponsoredStoryManagerClass != null ||
        storyAdProviders.isNotEmpty() ||
        gameAdRequestMethods.isNotEmpty()
}

