package tn.loukious.facebookappadsremover

import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

internal val GAME_AD_METHOD_TAGS = listOf(
    "loadRewardedVideoAsync",
    "showRewardedVideoAsync",
    "loadInterstitialAdAsync",
    "showInterstitialAdAsync"
)

internal val GAME_AD_ACTIVITY_CLASS_NAMES = setOf(
    "com.facebook.quicksilver.QuicksilverActivity",
    "com.facebook.ads.AudienceNetworkActivity",
    "com.facebook.ads.internal.ipc.AudienceNetworkRemoteActivity",
    "com.facebook.neko.playables.activity.NekoPlayableAdActivity"
)

internal val HARD_BLOCKED_GAME_AD_ACTIVITY_CLASS_NAMES = setOf(
    "com.facebook.neko.playables.activity.NekoPlayableAdActivity"
)

internal val recentGameAdTargets = Collections.synchronizedMap(WeakHashMap<Any, Long>())

internal fun hookGameAdRequest(module: XposedModule, method: Method) {
    module.hook(method).intercept { chain ->
        val payload = chain.args.firstOrNull { it is JSONObject } as? JSONObject ?: return@intercept chain.proceed()
        
        markGameAdDiagnosticFlow("game.ad.request ${method.name}")
        logGameAdDiagnostic("game.ad.request.payload", "${method.name} args=${formatDiagArgs(chain.args.toTypedArray())}")
        
        if (!ENABLE_GAME_AD_AUTOFIX) return@intercept chain.proceed()
        
        val promiseId = payload.optString("promiseID", "")
        if (promiseId.isNotBlank()) {
            recentGameAdTargets[chain.thisObject] = System.currentTimeMillis()
            
            if (method.name.contains("show", ignoreCase = true)) {
                Logger.i(TAG, "Intercepted game ad show request: ${method.name}")
                resolveGameAdPayload(chain.thisObject, payload)
                return@intercept null
            } else if (method.name.contains("load", ignoreCase = true)) {
                Logger.i(TAG, "Intercepted game ad load request: ${method.name}")
                resolveGameAdPayload(chain.thisObject, payload)
                return@intercept null
            }
        }
        
        chain.proceed()
    }
}

internal fun hookGameAdBridge(module: XposedModule, method: Method) {
    module.hook(method).intercept { chain ->
        val messageType = chain.args.getOrNull(0) as? String ?: return@intercept chain.proceed()
        val payloadStr = chain.args.getOrNull(1) as? String ?: return@intercept chain.proceed()
        
        markGameAdDiagnosticFlow("game.bridge.message $messageType")
        
        if (!ENABLE_GAME_AD_AUTOFIX) return@intercept chain.proceed()
        
        val payload = runCatching { JSONObject(payloadStr) }.getOrNull() ?: return@intercept chain.proceed()
        val promiseId = payload.optString("promiseID", "")
        
        if (promiseId.isNotBlank()) {
            if (messageType.contains("load", ignoreCase = true) || messageType.contains("show", ignoreCase = true)) {
                Logger.i(TAG, "Intercepted game ad bridge message: $messageType")
                resolveGameAdPayload(chain.thisObject, payload)
                return@intercept null
            }
        }
        
        chain.proceed()
    }
}

internal fun hookGameAdResultMethods(module: XposedModule, bridgeClass: Class<*>) {
    if (gameAdResultHooksInstalled.getAndIncrement() != 0) return
    
    // Logic to hook result-handling methods in the bridge class
}

internal fun hookGameAdServiceDispatchMethods(module: XposedModule, bridgeClass: Class<*>) {
    if (gameAdServiceDispatchHooksInstalled.getAndIncrement() != 0) return

    // Logic to hook service-dispatch methods
}

internal fun resolveGameAdPayload(target: Any?, payload: JSONObject) {
    val promiseId = payload.optString("promiseID") ?: return
    
    // Logic to resolve/reject the JS promise back to the game
}

internal fun handleGameAdActivity(activity: android.app.Activity, source: String) {
    if (!ENABLE_GAME_AD_AUTOFIX) return
    
    Logger.i(TAG, "Handling game ad activity ($source): ${activity.javaClass.name}")
    activity.finish()
}

internal fun completeRecentGameAdRequests(reason: String) {
    // Logic to complete pending promises
}

internal fun isRecentUnavailableGameAd(): Boolean {
    return System.currentTimeMillis() - lastUnavailableGameAdMs.get() < GAME_AD_RECENT_WINDOW_MS
}
