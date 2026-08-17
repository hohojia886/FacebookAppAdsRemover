package tn.loukious.facebookappadsremover

import android.app.Activity
import android.view.View
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

val gameAdInstanceIds = ConcurrentHashMap<String, String>()
val gameAdInstanceTypes = ConcurrentHashMap<String, String>()
val gameAdPromiseSnapshots = ConcurrentHashMap<String, GameAdPromiseSnapshot>()
val recentGameAdTargets: MutableMap<Any, Long> = Collections.synchronizedMap(WeakHashMap())
val recentGameAdPayloads: MutableList<GameAdPayloadSnapshot> = Collections.synchronizedList(ArrayList())
val hookHitCounters = ConcurrentHashMap<String, AtomicInteger>()
val gameAdSurfaceHooksInstalled = AtomicInteger(0)
val gameAdResultHooksInstalled = AtomicInteger(0)
val gameAdServiceDispatchHooksInstalled = AtomicInteger(0)
val gameAdSystemDiagnosticsInstalled = AtomicInteger(0)
val gameAdDynamicDiagnosticsInstalled = AtomicInteger(0)
val audienceNetworkViewDiagnosticsInstalled = AtomicInteger(0)
val audienceNetworkRewardHooksInstalled = AtomicInteger(0)
val lastGameAdActivityCloseMs = AtomicLong(0L)
val lastUnavailableGameAdMs = AtomicLong(0L)
val lastGameAdDiagnosticFlowMs = AtomicLong(0L)
val gameAdDiagnosticLogCount = AtomicInteger(0)
val scheduledGameAdActivityCloses: MutableMap<Activity, Long> = Collections.synchronizedMap(WeakHashMap())
val scheduledAudienceNetworkExitViews: MutableMap<View, Long> = Collections.synchronizedMap(WeakHashMap())
val audienceNetworkRewardClassesHooked: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
val audienceNetworkRewardAdListeners: MutableMap<Any, Any> = Collections.synchronizedMap(WeakHashMap())
val gameAdDiagnosticClassesHooked: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
val gameAdDiagnosticClassesLogged: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
val audienceNetworkViewListenerClassesHooked: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
val audienceNetworkActivityStateDumps: MutableMap<Activity, Long> = Collections.synchronizedMap(WeakHashMap())
val storyAdProviderClassesHooked: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
val feedCsrMethodsHooked: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
val lateFeedMethodsHooked: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
val sponsoredPoolMethodsHooked: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
val feedComponentMethodsHooked: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
val visibleAdTraceInstalled = AtomicInteger(0)
val visibleAdViewsTraced = ConcurrentHashMap<Int, Boolean>()
val survivingFeedAdTraceCount = AtomicInteger(0)
val survivingFeedTypeContractsLogged = AtomicInteger(0)
val marketplaceAdsPackCache = ConcurrentHashMap<String, Boolean>()
