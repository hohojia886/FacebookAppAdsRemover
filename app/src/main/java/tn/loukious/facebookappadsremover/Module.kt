package tn.loukious.facebookappadsremover

import android.app.Application
import android.content.Context
import android.os.Looper
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class Module : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // LSPosed API 101 with Static Scope automatically filters package loading.
        if (lpparam.packageName != "com.facebook.katana") return

        initModule(lpparam)
    }

    companion object {
        private const val TAG = "FacebookAppAdsRemover"
        private val FAST_SOURCE_DELAYS_MS = longArrayOf(100L, 250L, 750L, 1_500L, 2_500L)
        private val FAST_COMPONENT_DELAYS_MS = longArrayOf(3_500L, 5_000L, 7_500L)
        private val INSTALL_DELAYS_MS = longArrayOf(3_000L, 10_000L, 25_000L)

        @Volatile
        private var sDexKitLoaded = false
        private val sAttachHookInstalled = AtomicBoolean(false)
        private val sDexReadyHookInstalled = AtomicBoolean(false)
        private val sFastInstallInProgress = AtomicBoolean(false)
        private val sFastSourceHooksInstalled = AtomicBoolean(false)
        private val sComponentGuardInstallInProgress = AtomicBoolean(false)
        private val sFeedComponentGuardInstalled = AtomicBoolean(false)
        private val sInstallInProgress = AtomicBoolean(false)
        private val sHooksInstalled = AtomicBoolean(false)

        @Volatile
        private var sClassLoadNotifierUnhook: XC_MethodHook.Unhook? = null

        private val sTaskExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "FacebookAdsHookExecutor")
        }

        @JvmStatic
        private fun initModule(lpparam: XC_LoadPackage.LoadPackageParam) {
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
                    scheduleHookInstallAttempts(application.classLoader)
                }
            })
            debugLogInfo("Waiting for Facebook Application.attach before scanning secondary dex")
        }

        private fun debugLogInfo(message: String) {
            Logger.i(TAG, message)
        }

        private fun debugLogError(message: String, throwable: Throwable) {
            Logger.e(TAG, message, throwable)
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
                val configure = multiDexClassLoader.declaredMethods.find {
                    it.name == "configure" && it.parameterCount == 1
                }

                if (configure == null) {
                    debugLogInfo("Facebook MultiDex configure method not found; using timed feed hook fallback")
                    return
                }
                configure.isAccessible = true
                XposedBridge.hookMethod(configure, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val configuredLoader = if (param.thisObject is ClassLoader) {
                            param.thisObject as ClassLoader
                        } else {
                            classLoader
                        }
                        tryInstallFastFeedHooksAtDexReady(configuredLoader, "MultiDex configure")
                    }
                })

                var fallbackHooks = 0
                val multiDexBase = Class.forName(
                    "com.facebook.common.dextricks.MultiDexClassLoader",
                    false,
                    classLoader
                )
                multiDexBase.declaredMethods.forEach { method ->
                    if (method.name == "maybeFallbackLoadDexes" &&
                        method.parameterCount >= 1 &&
                        method.parameterTypes[0] == String::class.java
                    ) {
                        method.isAccessible = true
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (param.result == true) {
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

        @Throws(Exception::class)
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
                    val loadedClass = param.args[0] as? Class<*>
                    if (loadedClass == null || !isFastFeedTargetClass(loadedClass.name)) {
                        return
                    }

                    val targetLoader = loadedClass.classLoader ?: classLoader
                    debugLogInfo(
                        "Observed FB 571 feed source class load=${loadedClass.name} " +
                                "loader=${targetLoader.javaClass.name}"
                    )
                    tryInstallFastFeedHooksAtDexReady(targetLoader, "class-load notification")
                }
            })
            debugLogInfo("Waiting for FB 571 feed source class load before installing decoded response hooks")
        }

        private fun isFastFeedTargetClass(className: String): Boolean {
            return className in setOf(
                "X.1fM", "X.21p", "X.baJ", "X.baK", "X.21O", "X.3YX", "X.2OT", "X.2Oc"
            )
        }

        private fun tryInstallFastFeedHooksAtDexReady(classLoader: ClassLoader, readinessSource: String) {
            if (!sFastSourceHooksInstalled.get() && sFastInstallInProgress.compareAndSet(false, true)) {
                try {
                    if (installFacebook571FeedSourceFastPath(classLoader)) {
                        sFastSourceHooksInstalled.set(true)
                        debugLogInfo("FB 571 decoded response hooks installed synchronously at $readinessSource")
                    }
                } catch (throwable: Throwable) {
                    debugLogError("Failed FB 571 decoded response install at $readinessSource", throwable)
                } finally {
                    sFastInstallInProgress.set(false)
                }
            }
            tryInstallFeedComponentGuard(classLoader, readinessSource)
            removeClassLoadNotifierHook()
        }

        private fun removeClassLoadNotifierHook() {
            if (!sFastSourceHooksInstalled.get() || !sFeedComponentGuardInstalled.get()) {
                return
            }
            val unhook = sClassLoadNotifierUnhook ?: return
            sClassLoadNotifierUnhook = null
            unhook.unhook()
            debugLogInfo("Removed FB 571 class-load notifier after decoded hooks became active")
        }

        private fun scheduleHookInstallAttempts(classLoader: ClassLoader) {
            tryInstallFastFeedSourceHooks(classLoader, 0)
            tryInstallFeedComponentGuard(classLoader, "Application.attach")

            FAST_SOURCE_DELAYS_MS.forEachIndexed { index, delay ->
                val attemptNumber = index + 1
                sTaskExecutor.schedule({
                    tryInstallFastFeedSourceHooks(classLoader, attemptNumber)
                }, delay, TimeUnit.MILLISECONDS)
            }

            FAST_COMPONENT_DELAYS_MS.forEachIndexed { index, delay ->
                val attemptNumber = index + 1
                sTaskExecutor.schedule({
                    tryInstallFeedComponentGuard(classLoader, "component attempt=$attemptNumber")
                }, delay, TimeUnit.MILLISECONDS)
            }

            INSTALL_DELAYS_MS.forEachIndexed { index, delay ->
                val attemptNumber = index + 1
                sTaskExecutor.schedule({
                    tryInstallHooks(classLoader, attemptNumber)
                }, delay, TimeUnit.MILLISECONDS)
            }
        }

        private fun tryInstallFastFeedSourceHooks(classLoader: ClassLoader, attemptNumber: Int) {
            if (!sFastSourceHooksInstalled.get() && sFastInstallInProgress.compareAndSet(false, true)) {
                try {
                    if (installFacebook571FeedSourceFastPath(classLoader)) {
                        sFastSourceHooksInstalled.set(true)
                        debugLogInfo("FB 571 decoded response hooks installed on attempt=$attemptNumber")
                    }
                } catch (throwable: Throwable) {
                    debugLogError("Failed FB 571 fast decoded response install on attempt=$attemptNumber", throwable)
                } finally {
                    sFastInstallInProgress.set(false)
                }
            }
            tryInstallFeedComponentGuard(classLoader, "source attempt=$attemptNumber")
            removeClassLoadNotifierHook()
        }

        private fun tryInstallFeedComponentGuard(classLoader: ClassLoader, readinessSource: String) {
            if (sFeedComponentGuardInstalled.get() || !sComponentGuardInstallInProgress.compareAndSet(false, true)) {
                return
            }
            try {
                if (installFacebook571FeedComponentGuard(classLoader)) {
                    sFeedComponentGuardInstalled.set(true)
                    debugLogInfo("FB 571 sponsored feed component guard installed at $readinessSource")
                }
            } catch (throwable: Throwable) {
                debugLogError("Failed FB 571 sponsored feed component guard at $readinessSource", throwable)
            } finally {
                sComponentGuardInstallInProgress.set(false)
            }
        }

        private fun tryInstallHooks(classLoader: ClassLoader, attemptNumber: Int) {
            if (sHooksInstalled.get() || !sInstallInProgress.compareAndSet(false, true)) {
                return
            }

            try {
                ensureDexKitLoaded()
                DexKitBridge.create(classLoader, false).use { bridge ->
                    debugLogInfo("Scanning Facebook secondary dex, attempt=$attemptNumber")
                    if (installFacebookAdRemover(classLoader, bridge)) {
                        sHooksInstalled.set(true)
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
            if (sDexKitLoaded) return
            synchronized(Module::class.java) {
                if (!sDexKitLoaded) {
                    try {
                        System.loadLibrary("dexkit")
                        sDexKitLoaded = true
                    } catch (t: Throwable) {
                        Logger.e(TAG, "Failed to load dexkit JNI library", t)
                    }
                }
            }
        }
    }
}
