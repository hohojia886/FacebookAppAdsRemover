package tn.loukious.facebookappadsremover

import android.app.Application
import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class Module : XposedModule() {

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        super.onPackageLoaded(param)
        // LSPosed API 101 with Static Scope automatically filters package loading.
        if (param.packageName != "com.facebook.katana") return

        initModule(this, param)
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
        private var sClassLoadNotifierUnhook: XposedInterface.HookHandle? = null

        private val sTaskExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "FacebookAdsHookExecutor")
        }

        private fun initModule(module: Module, param: XposedModuleInterface.PackageLoadedParam) {
            debugLogInfo("Loading hooks for package=${param.packageName} process=${param.applicationInfo.processName}")
            installFacebookDexReadyHook(module, param.defaultClassLoader)
            ensureDexKitLoaded()
            if (!sAttachHookInstalled.compareAndSet(false, true)) {
                return
            }

            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            attach.isAccessible = true
            module.hook(attach).intercept { chain ->
                val res = chain.proceed()
                val application = chain.thisObject as Application
                scheduleHookInstallAttempts(module, application.classLoader)
                res
            }
            debugLogInfo("Waiting for Facebook Application.attach before scanning secondary dex")
        }

        private fun debugLogInfo(message: String) {
            Logger.i(TAG, message)
        }

        private fun debugLogError(message: String, throwable: Throwable) {
            Logger.e(TAG, message, throwable)
        }

        private fun installFacebookDexReadyHook(module: Module, classLoader: ClassLoader) {
            if (!sDexReadyHookInstalled.compareAndSet(false, true)) {
                return
            }
            try {
                installFacebookClassLoadNotifierHook(module, classLoader)
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
                module.hook(configure).intercept { chain ->
                    val res = chain.proceed()
                    val configuredLoader = if (chain.thisObject is ClassLoader) {
                        chain.thisObject as ClassLoader
                    } else {
                        classLoader
                    }
                    tryInstallFastFeedHooksAtDexReady(module, configuredLoader, "MultiDex configure")
                    res
                }

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
                        module.hook(method).intercept { chain ->
                            val res = chain.proceed()
                            if (res == true) {
                                tryInstallFastFeedHooksAtDexReady(module, classLoader, "long-tail dex load")
                            }
                            res
                        }
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
        private fun installFacebookClassLoadNotifierHook(module: Module, classLoader: ClassLoader) {
            val notifierClass = Class.forName(
                "com.facebook.common.dextricks.ClassLoadsNotifier",
                false,
                classLoader
            )
            val notifyClassLoaded = notifierClass.getDeclaredMethod("notifyClassLoaded", Class::class.java)
            notifyClassLoaded.isAccessible = true
            sClassLoadNotifierUnhook = module.hook(notifyClassLoaded).intercept { chain ->
                val res = chain.proceed()
                val loadedClass = chain.args[0] as? Class<*>
                if (loadedClass != null && isFastFeedTargetClass(loadedClass.name)) {
                    val targetLoader = loadedClass.classLoader ?: classLoader
                    debugLogInfo(
                        "Observed FB 571 feed source class load=${loadedClass.name} " +
                                "loader=${targetLoader.javaClass.name}"
                    )
                    tryInstallFastFeedHooksAtDexReady(module, targetLoader, "class-load notification")
                }
                res
            }
            debugLogInfo("Waiting for FB 571 feed source class load before installing decoded response hooks")
        }

        private fun isFastFeedTargetClass(className: String): Boolean {
            return className in setOf(
                "X.1fM", "X.21p", "X.baJ", "X.baK", "X.21O", "X.3YX", "X.2OT", "X.2Oc"
            )
        }

        private fun tryInstallFastFeedHooksAtDexReady(module: Module, classLoader: ClassLoader, readinessSource: String) {
            if (!sFastSourceHooksInstalled.get() && sFastInstallInProgress.compareAndSet(false, true)) {
                try {
                    if (installFacebook571FeedSourceFastPath(module, classLoader)) {
                        sFastSourceHooksInstalled.set(true)
                        debugLogInfo("FB 571 decoded response hooks installed synchronously at $readinessSource")
                    }
                } catch (throwable: Throwable) {
                    debugLogError("Failed FB 571 decoded response install at $readinessSource", throwable)
                } finally {
                    sFastInstallInProgress.set(false)
                }
            }
            tryInstallFeedComponentGuard(module, classLoader, readinessSource)
            removeClassLoadNotifierHook()
        }

        private fun removeClassLoadNotifierHook() {
            if (!sFastSourceHooksInstalled.get() || !sFeedComponentGuardInstalled.get()) {
                return
            }
            val handle = sClassLoadNotifierUnhook ?: return
            sClassLoadNotifierUnhook = null
            handle.unhook()
            debugLogInfo("Removed FB 571 class-load notifier after decoded hooks became active")
        }

        private fun scheduleHookInstallAttempts(module: Module, classLoader: ClassLoader) {
            tryInstallFastFeedSourceHooks(module, classLoader, 0)
            tryInstallFeedComponentGuard(module, classLoader, "Application.attach")

            FAST_SOURCE_DELAYS_MS.forEachIndexed { index, delay ->
                val attemptNumber = index + 1
                sTaskExecutor.schedule({
                    tryInstallFastFeedSourceHooks(module, classLoader, attemptNumber)
                }, delay, TimeUnit.MILLISECONDS)
            }

            FAST_COMPONENT_DELAYS_MS.forEachIndexed { index, delay ->
                val attemptNumber = index + 1
                sTaskExecutor.schedule({
                    tryInstallFeedComponentGuard(module, classLoader, "component attempt=$attemptNumber")
                }, delay, TimeUnit.MILLISECONDS)
            }

            INSTALL_DELAYS_MS.forEachIndexed { index, delay ->
                val attemptNumber = index + 1
                sTaskExecutor.schedule({
                    tryInstallHooks(module, classLoader, attemptNumber)
                }, delay, TimeUnit.MILLISECONDS)
            }
        }

        private fun tryInstallFastFeedSourceHooks(module: Module, classLoader: ClassLoader, attemptNumber: Int) {
            if (!sFastSourceHooksInstalled.get() && sFastInstallInProgress.compareAndSet(false, true)) {
                try {
                    if (installFacebook571FeedSourceFastPath(module, classLoader)) {
                        sFastSourceHooksInstalled.set(true)
                        debugLogInfo("FB 571 decoded response hooks installed on attempt=$attemptNumber")
                    }
                } catch (throwable: Throwable) {
                    debugLogError("Failed FB 571 fast decoded response install on attempt=$attemptNumber", throwable)
                } finally {
                    sFastInstallInProgress.set(false)
                }
            }
            tryInstallFeedComponentGuard(module, classLoader, "source attempt=$attemptNumber")
            removeClassLoadNotifierHook()
        }

        private fun tryInstallFeedComponentGuard(module: Module, classLoader: ClassLoader, readinessSource: String) {
            if (sFeedComponentGuardInstalled.get() || !sComponentGuardInstallInProgress.compareAndSet(false, true)) {
                return
            }
            try {
                if (installFacebook571FeedComponentGuard(module, classLoader)) {
                    sFeedComponentGuardInstalled.set(true)
                    debugLogInfo("FB 571 sponsored feed component guard installed at $readinessSource")
                }
            } catch (throwable: Throwable) {
                debugLogError("Failed FB 571 sponsored feed component guard at $readinessSource", throwable)
            } finally {
                sComponentGuardInstallInProgress.set(false)
            }
        }

        private fun tryInstallHooks(module: Module, classLoader: ClassLoader, attemptNumber: Int) {
            if (sHooksInstalled.get() || !sInstallInProgress.compareAndSet(false, true)) {
                return
            }

            try {
                ensureDexKitLoaded()
                DexKitBridge.create(classLoader, false).use { bridge ->
                    debugLogInfo("Scanning Facebook secondary dex, attempt=$attemptNumber")
                    if (installFacebookAdRemover(module, classLoader, bridge)) {
                        sHooksInstalled.set(true)
                        tryInstallFeedComponentGuard(module, classLoader, "full DexKit readiness")
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
