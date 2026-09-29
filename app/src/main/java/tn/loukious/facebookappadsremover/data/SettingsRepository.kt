package tn.loukious.facebookappadsremover.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tn.loukious.facebookappadsremover.core.AdSettingsMigration
import tn.loukious.facebookappadsremover.core.Settings

/**
 * Repository abstracting the XposedService IPC connection and remote preferences.
 */
class SettingsRepository {

    private val _isServiceBound = MutableStateFlow(false)
    val isServiceBound: StateFlow<Boolean> = _isServiceBound.asStateFlow()

    private var prefs: SharedPreferences? = null
    private var context: Context? = null

    private val listener = object : XposedServiceHelper.OnServiceListener {
        override fun onServiceBind(service: XposedService) {
            prefs = runCatching { service.getRemotePreferences(Settings.NAME) }.getOrNull()
            migrateFromLocalStorage(context)
            migrateSplitAdToggles()
            _isServiceBound.value = true
        }

        override fun onServiceDied(service: XposedService) {
            prefs = null
            _isServiceBound.value = false
        }
    }

    fun registerService(context: Context) {
        this.context = context.applicationContext
        XposedServiceHelper.registerListener(listener)
    }

    fun getBoolean(key: String, default: Boolean): Boolean {
        return prefs?.getBoolean(key, default) ?: default
    }

    fun setBoolean(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }

    fun getString(key: String, default: String): String {
        return prefs?.getString(key, default) ?: default
    }

    fun setString(key: String, value: String) {
        prefs?.edit()?.putString(key, value)?.apply()
    }

    private fun migrateFromLocalStorage(context: Context?) {
        val remote = prefs ?: return
        val ctx = context ?: return
        runCatching {
            if (remote.all.isNotEmpty()) return
            val legacy = ctx.getSharedPreferences(Settings.NAME, Context.MODE_PRIVATE)
            val old = legacy.all
            if (old.isEmpty()) return
            remote.edit().apply {
                for ((key, value) in old) {
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Set<*> -> @Suppress("UNCHECKED_CAST") (value as? Set<String>)?.let { putStringSet(key, it) }
                    }
                }
            }.apply()
        }
    }

    private fun migrateSplitAdToggles() {
        val remote = prefs ?: return
        runCatching {
            val updates = AdSettingsMigration.updates(remote.all)
            if (updates.isEmpty() && !remote.contains(AdSettingsMigration.RETIRED_SHOPPING)) {
                return
            }
            remote.edit().apply {
                for ((key, value) in updates) putBoolean(key, value)
                remove(AdSettingsMigration.RETIRED_SHOPPING)
                remove(Settings.LEGACY_ADS_ENABLED)
            }.apply()
        }.onFailure {
            Log.w("FBAR.Settings", "Ad toggle migration failed", it)
        }
    }
}
