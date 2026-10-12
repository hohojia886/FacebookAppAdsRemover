package tn.loukious.facebookappadsremover.hooks

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import java.lang.reflect.Modifier

/** Replaces browser launch intents before either in-app browser is created. */
object ExternalBrowserHook {
    private const val TAG = "FBAR.Browser"
    private val installed = HashSet<String>()

    fun install(module: XposedInterface, context: Context) {
        // Shared by Activity and application-context starts, including
        // startActivityForResult and startActivities. No host dex scan.
        val methods = Instrumentation::class.java.declaredMethods.filter { method ->
            method.name.startsWith("execStartActivit") &&
            !Modifier.isAbstract(method.modifiers) && method.parameterTypes.any {
                it == Intent::class.java || it == Array<Intent>::class.java
            }
        }
        for (method in methods) {
            val signature = method.toGenericString()
            if (signature in installed) continue
            runCatching {
                module.hook(method).intercept(object : Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        if (!Settings.getBoolean(Settings.LINKS_EXTERNAL_BROWSER, true))
                            return chain.proceed()
                        val args = chain.args
                        val caller = args.firstOrNull { it is Context } as? Context ?: context
                        var newArgs: Array<Any?>? = null
                        val prepared = runCatching {
                            for (i in args.indices) {
                                when (val argument = args[i]) {
                                    is Intent -> ExternalBrowserIntents.replacement(caller, argument)?.let { rep ->
                                        if (newArgs == null) newArgs = args.toTypedArray()
                                        newArgs!![i] = rep
                                    }
                                    is Array<*> -> if (argument.isArrayOf<Intent>()) {
                                        var arrayChanged = false
                                        val newArray = argument.map { value ->
                                            val intent = value as Intent
                                            ExternalBrowserIntents.replacement(caller, intent)?.also {
                                                arrayChanged = true
                                            } ?: intent
                                        }.toTypedArray()
                                        if (arrayChanged) {
                                            if (newArgs == null) newArgs = args.toTypedArray()
                                            newArgs!![i] = newArray
                                        }
                                    }
                                }
                            }
                        }.onFailure { L.w(TAG, "external browser resolution failed", it) }
                        val finalArgs = newArgs ?: return chain.proceed()
                        if (prepared.isFailure) return chain.proceed()
                        L.i(TAG, "opening outbound link in external browser")
                        return chain.proceed(finalArgs)
                    }
                })
                installed.add(signature)
            }.onFailure { L.w(TAG, "browser launch hook failed: ${method.name}", it) }
        }
        L.i(TAG, "external browser option armed on ${installed.size} launch overload(s)")
    }
}
