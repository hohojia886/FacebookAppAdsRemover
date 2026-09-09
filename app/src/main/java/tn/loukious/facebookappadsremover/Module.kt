package tn.loukious.facebookappadsremover

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import java.lang.Boolean
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

class Module : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "FacebookAppAdsRemover"
        private val EARLY_GUARD_DELAYS_MS = longArrayOf(100L, 250L, 750L, 1_500L, 2_500L)
        private val FAST_COMPONENT_DELAYS_MS = longArrayOf(3_500L, 5_000L, 7_500L)
        private val INSTALL_DELAYS_MS = longArrayOf(3_000L, 10_000L, 25_000L)
        @Volatile
        private var sDexKitLoaded = false
        private val sAttachHookInstalled = AtomicBoolean(false)
        private val sDexReadyHookInstalled = AtomicBoolean(false)
        private val sComponentGuardInstallInProgress = AtomicBoolean(false)
        private val sFeedComponentGuardInstalled = AtomicBoolean(false)
        private val sMarketplaceNetGuardInstalled = AtomicBoolean(false)
        private val sMarketplaceNetGuardInProgress = AtomicBoolean(false)
        private val sInstallInProgress = AtomicBoolean(false)
        private val sHooksInstalled = AtomicBoolean(false)
        @Volatile
        private var sClassLoadNotifierUnhook: XC_MethodHook.Unhook? = null
        @Volatile
        private var sApplication: Application? = null
        @Volatile
        private var sHostVersionName: String? = null

        private fun debugLogInfo(message: String) {
            if (BuildConfig.DEBUG) {
                Log.i(TAG, message)
            }
        }

        private fun debugLogError(message: String, throwable: Throwable?) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, message, throwable)
            }
        }

        private fun loadCachedFeedGuardCandidates(application: Application) {
            try {
                val registered = loadCachedFeedGuardCandidates(
                    application,
                    application.classLoader,
                    resolveHostVersionName(application)
                )
                if (registered > 0) {
                    debugLogInfo("Registered $registered cached feed guard candidate(s)")
                }
            } catch (throwable: Throwable) {
                debugLogError("Failed to load cached feed guard candidates", throwable)
            }
        }

        private fun resolveHostVersionName(application: Application): String {
            var versionName = sHostVersionName
            if (versionName != null) {
                return versionName
            }
            try {
                versionName = application.packageManager
                    .getPackageInfo(application.packageName, 0).versionName ?: ""
            } catch (throwable: Throwable) {
                debugLogError("Failed to resolve host version name", throwable)
                versionName = ""
            }
            sHostVersionName = versionName
            return versionName
        }

        private fun saveFeedGuardCandidateCache() {
            val application = sApplication ?: return
            try {
                saveFeedGuardCandidateCache(application, resolveHostVersionName(application))
            } catch (throwable: Throwable) {
                debugLogError("Failed to save feed guard candidates", throwable)
            }
        }

        private fun loadCachedReelsGuard(application: Application) {
            try {
                installReelsGuardFromCache(
                    application,
                    application.classLoader,
                    resolveHostVersionName(application)
                )
            } catch (throwable: Throwable) {
                debugLogError("Failed to load cached reels guard", throwable)
            }
        }

        private fun saveReelsGuardCache() {
            val application = sApplication ?: return
            try {
                saveReelsGuardCache(application, resolveHostVersionName(application))
            } catch (throwable: Throwable) {
                debugLogError("Failed to save reels guard cache", throwable)
            }
        }

        private fun loadCachedMarketplaceNetGuard(application: Application) {
            try {
                if (installMarketplaceNetGuardFromCache(
                        application,
                        application.classLoader,
                        resolveHostVersionName(application)
                    )
                ) {
                    sMarketplaceNetGuardInstalled.set(true)
                    debugLogInfo("Marketplace net guard installed from cache at attach")
                }
            } catch (throwable: Throwable) {
                debugLogError("Failed to load cached marketplace net guard", throwable)
            }
        }

        private fun retryCachedMarketplaceNetGuard(classLoader: ClassLoader) {
            if (sMarketplaceNetGuardInstalled.get()) {
                return
            }
            if (!sMarketplaceNetGuardInProgress.compareAndSet(false, true)) {
                return
            }
            try {
                val application = sApplication
                if (application != null && installMarketplaceNetGuardFromCache(
                        application,
                        classLoader,
                        resolveHostVersionName(application)
                    )
                ) {
                    sMarketplaceNetGuardInstalled.set(true)
                    debugLogInfo("Marketplace net guard installed from cache on retry")
                }
            } catch (throwable: Throwable) {
                debugLogError("Failed to retry cached marketplace net guard", throwable)
            } finally {
                sMarketplaceNetGuardInProgress.set(false)
            }
        }

        private fun saveMarketplaceNetGuardCache() {
            val application = sApplication ?: return
            try {
                saveMarketplaceNetGuardCache(
                    application,
                    resolveHostVersionName(application)
                )
            } catch (throwable: Throwable) {
                debugLogError("Failed to save marketplace net guard cache", throwable)
            }
        }

        private fun installFacebookDexReadyHook(classLoader: ClassLoader) {
            if (!sDexReadyHookInstalled.compareAndSet(false, true)) {
                return
            }
            try {
                installFacebookClassLoadNotifierHook(classLoader)
                val multiDexClassLoader = Class.forName(
                    "com.facebook.common.dextricks.MultiDexClassLoaderJava",
                    false,
                    classLoader
                )
                var configure: Method? = null
                for (method in multiDexClassLoader.declaredMethods) {
                    if ("configure" == method.name && method.parameterCount == 1) {
                        configure = method
                        break
                    }
                }
                if (configure == null) {
                    debugLogInfo("Facebook MultiDex configure method not found; using timed feed hook fallback")
                    return
                }
                configure.isAccessible = true
                XposedBridge.hookMethod(configure, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val configuredLoader = if (param.thisObject is ClassLoader)
                            param.thisObject as ClassLoader
                        else
                            classLoader
                        tryInstallFastFeedHooksAtDexReady(configuredLoader, "MultiDex configure")
                    }
                })
                var fallbackHooks = 0
                val multiDexBase = Class.forName(
                    "com.facebook.common.dextricks.MultiDexClassLoader",
                    false,
                    classLoader
                )
                for (method in multiDexBase.declaredMethods) {
                    if ("maybeFallbackLoadDexes" == method.name &&
                        method.parameterCount >= 1 &&
                        method.parameterTypes[0] == String::class.java
                    ) {
                        method.isAccessible = true
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (Boolean.TRUE == param.result) {
                                    tryInstallFastFeedHooksAtDexReady(classLoader, "long-tail dex load")
                                }
                            }
                        })
                        fallbackHooks++
                    }
                }
                debugLogInfo(
                    "Waiting for Facebook MultiDex configure/long-tail load before installing decoded response hooks; " +
                            "fallbackHooks=$fallbackHooks"
                )
            } catch (throwable: Throwable) {
                debugLogError("Failed to hook Facebook MultiDex readiness; using timed fallback", throwable)
            }
        }

        private fun installFacebookClassLoadNotifierHook(classLoader: ClassLoader) {
            val notifierClass = Class.forName(
                "com.facebook.common.dextricks.ClassLoadsNotifier",
                false,
                classLoader
            )
            val notifyClassLoaded = notifierClass.getDeclaredMethod("notifyClassLoaded", Class::class.java)
            notifyClassLoaded.isAccessible = true
            sClassLoadNotifierUnhook = XposedBridge.hookMethod(notifyClassLoaded, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val loadedClass = if (param.args.getOrNull(0) is Class<*>)
                        param.args[0] as Class<*>
                    else
                        null
                    if (loadedClass == null) {
                        return
                    }
                    val componentName = lithoComponentNameOf(loadedClass)
                    if (componentName == null || !registerFeedGuardCandidate(loadedClass, componentName)) {
                        return
                    }

                    var targetLoader = loadedClass.classLoader
                    if (targetLoader == null) {
                        targetLoader = classLoader
                    }
                    debugLogInfo(
                        "Observed feed component class load=" + loadedClass.name +
                                " component=" + componentName +
                                " loader=" + targetLoader.javaClass.name
                    )
                    tryInstallFastFeedHooksAtDexReady(targetLoader, "class-load notification")
                }
            })
            debugLogInfo("Waiting for feed component class load before installing the component guard")
        }

        private fun tryInstallFastFeedHooksAtDexReady(
            classLoader: ClassLoader,
            readinessSource: String
        ) {
            tryInstallFeedComponentGuard(classLoader, readinessSource)
            removeClassLoadNotifierHook()
        }

        private fun removeClassLoadNotifierHook() {
            if (!sFeedComponentGuardInstalled.get()) {
                return
            }
            val unhook = sClassLoadNotifierUnhook ?: return
            sClassLoadNotifierUnhook = null
            unhook.unhook()
            debugLogInfo("Removed class-load notifier after the component guard became active")
        }

        private fun scheduleHookInstallAttempts(classLoader: ClassLoader) {
            val handler = Handler(Looper.getMainLooper())
            tryInstallFeedComponentGuard(classLoader, "Application.attach")
            for (attempt in EARLY_GUARD_DELAYS_MS.indices) {
                val attemptNumber = attempt + 1
                handler.postDelayed({
                    Thread({
                        tryInstallFeedComponentGuard(
                            classLoader,
                            "component attempt=$attemptNumber"
                        )
                    }, "FacebookFeedComponentInit-$attemptNumber").start()
                }, EARLY_GUARD_DELAYS_MS[attempt])
                handler.postDelayed({
                    Thread({
                        retryCachedMarketplaceNetGuard(classLoader)
                    }, "FacebookMarketplaceNetInit-$attemptNumber").start()
                }, EARLY_GUARD_DELAYS_MS[attempt])
            }
            for (attempt in FAST_COMPONENT_DELAYS_MS.indices) {
                val attemptNumber = attempt + 1
                handler.postDelayed({
                    Thread({
                        tryInstallFeedComponentGuard(
                            classLoader,
                            "late component attempt=$attemptNumber"
                        )
                        retryCachedMarketplaceNetGuard(classLoader)
                    }, "FacebookFeedComponentLate-$attemptNumber").start()
                }, FAST_COMPONENT_DELAYS_MS[attempt])
            }
            for (attempt in INSTALL_DELAYS_MS.indices) {
                val attemptNumber = attempt + 1
                handler.postDelayed({
                    Thread({
                        tryInstallHooks(classLoader, attemptNumber)
                    }, "FacebookAdsHookInit-$attemptNumber").start()
                }, INSTALL_DELAYS_MS[attempt])
            }
        }

        private fun tryInstallFeedComponentGuard(
            classLoader: ClassLoader,
            readinessSource: String
        ) {
            if (sFeedComponentGuardInstalled.get() ||
                !sComponentGuardInstallInProgress.compareAndSet(false, true)
            ) {
                return
            }
            try {
                if (installFacebookFeedComponentGuard(classLoader)) {
                    sFeedComponentGuardInstalled.set(true)
                    debugLogInfo("Sponsored feed component guard installed at $readinessSource")
                }
            } catch (throwable: Throwable) {
                debugLogError("Failed sponsored feed component guard at $readinessSource", throwable)
            } finally {
                sComponentGuardInstallInProgress.set(false)
            }
        }

        private fun tryInstallHooks(classLoader: ClassLoader, attemptNumber: Int) {
            if (sHooksInstalled.get() || !sInstallInProgress.compareAndSet(false, true)) {
                return
            }

            try {
                DexKitBridge.create(classLoader, true).use { bridge ->
                    debugLogInfo("Scanning Facebook secondary dex, attempt=$attemptNumber")
                    if (installFacebookAdRemover(classLoader, bridge)) {
                        sHooksInstalled.set(true)
                        saveFeedGuardCandidateCache()
                        saveReelsGuardCache()
                        saveMarketplaceNetGuardCache()
                        tryInstallFeedComponentGuard(classLoader, "full DexKit readiness")
                        removeClassLoadNotifierHook()
                        debugLogInfo("Facebook ad remover hooks installed on attempt=$attemptNumber")
                    }
                }
            } catch (throwable: Throwable) {
                debugLogError("Failed to install Facebook ad remover on attempt=$attemptNumber", throwable)
            } finally {
                sInstallInProgress.set(false)
            }
        }

        private fun ensureDexKitLoaded() {
            if (sDexKitLoaded) {
                return
            }
            synchronized(Module::class.java) {
                if (!sDexKitLoaded) {
                    System.loadLibrary("dexkit")
                    sDexKitLoaded = true
                }
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if ("com.facebook.katana" != lpparam.packageName) {
            return
        }

        debugLogInfo("Loading hooks for package=${lpparam.packageName} process=${lpparam.processName}")
        installFacebookDexReadyHook(lpparam.classLoader)
        ensureDexKitLoaded()
        if (!sAttachHookInstalled.compareAndSet(false, true)) {
            return
        }

        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        attach.isAccessible = true
        XposedBridge.hookMethod(attach, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val application = param.thisObject as Application
                sApplication = application
                loadCachedFeedGuardCandidates(application)
                loadCachedReelsGuard(application)
                loadCachedMarketplaceNetGuard(application)
                // The game webview can register its Javascript bridge before the
                // DexKit scan installs the main hooks; watch for it immediately.
                try {
                    installGameAdJavascriptInterfaceBridgeHook()
                } catch (throwable: Throwable) {
                    debugLogError("Failed to install game bridge watcher", throwable)
                }
                // The framework-only view-level safety net (marker-based ad
                // hiding) must be active before the first feed/reels/marketplace
                // content mounts, which happens well before the DexKit scan.
                try {
                    installGlobalAdSurfaceFallbacksEarly()
                } catch (throwable: Throwable) {
                    debugLogError("Failed to install early ad surface fallbacks", throwable)
                }
                scheduleHookInstallAttempts(application.classLoader)
            }
        })
        debugLogInfo("Waiting for Facebook Application.attach before scanning secondary dex")
    }
}
