package tn.loukious.facebookappadsremover

import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier

fun hookStoryPoolAdd(module: XposedModule, method: Method, feedItemInspector: FeedItemInspector) {
    module.hook(method).intercept { chain ->
        val item = chain.args.getOrNull(0)
        val blockReason = feedItemInspector.storyPoolBlockReason(item)
        if (blockReason == null) {
            if (feedItemInspector.isSponsoredFeedItem(item)) {
                logHookHitThrottled("storyPoolBroadAllowed", method, feedItemInspector.describe(item))
            }
            return@intercept chain.proceed()
        }

        logHookHitThrottled(
            if (blockReason == "strict") "storyPoolStrictBlock" else "storyPoolBroadNetworkBlock",
            method,
            feedItemInspector.describe(item)
        )
        false
    }
}

fun hookStoryAdsMerge(module: XposedModule, method: Method, source: String) {
    module.hook(method).intercept { chain ->
        val originalBuckets = chain.args.getOrNull(2)
        if (originalBuckets != null) {
            Logger.i(TAG, "Blocked story ad bucket merge in $source")
            return@intercept originalBuckets
        }
        chain.proceed()
    }
}

fun hookStoryAdsNoOp(module: XposedModule, method: Method, reason: String, source: String) {
    module.hook(method).intercept { chain ->
        Logger.i(TAG, "Blocked $reason in $source")
        null
    }
}

fun hookStoryAdProvider(module: XposedModule, provider: StoryAdProviderHooks) {
    if (!storyAdProviderClassesHooked.add(provider.providerClass.name)) return

    val hooked = ArrayList<String>()

    provider.mergeMethod?.let { method ->
        hookStoryAdsMerge(module, method, provider.providerClass.name)
        hooked.add("merge")
    }
    provider.fetchMoreAdsMethod?.let { method ->
        hookStoryAdsNoOp(module, method, "story ad fetchMoreAds", provider.providerClass.name)
        hooked.add("fetchMoreAds")
    }
    provider.deferredUpdateMethod?.let { method ->
        hookStoryAdsNoOp(module, method, "story ad deferred update", provider.providerClass.name)
        hooked.add("deferredUpdate")
    }
    provider.insertionTriggerMethod?.let { method ->
        hookStoryAdsNoOp(module, method, "story ad insertion trigger", provider.providerClass.name)
        hooked.add("insertionTrigger")
    }

    if (hooked.isNotEmpty()) {
        Logger.i(TAG, "Hooked story ad provider ${provider.providerClass.name}: ${hooked.joinToString()}")
    }
}
