package tn.loukious.facebookappadsremover

import android.util.Log as AndroidLog

object Logger {
    fun i(tag: String, msg: String): Int = if (BuildConfig.DEBUG) AndroidLog.i(tag, msg) else 0

    fun w(tag: String, msg: String): Int = if (BuildConfig.DEBUG) AndroidLog.w(tag, msg) else 0

    fun w(tag: String, msg: String, throwable: Throwable): Int =
        if (BuildConfig.DEBUG) AndroidLog.w(tag, msg, throwable) else 0

    fun e(tag: String, msg: String): Int = if (BuildConfig.DEBUG) AndroidLog.e(tag, msg) else 0

    fun e(tag: String, msg: String, throwable: Throwable): Int =
        if (BuildConfig.DEBUG) AndroidLog.e(tag, msg, throwable) else 0

    fun missing(tag: String, hookName: String): Int =
        if (BuildConfig.DEBUG) AndroidLog.w(tag, "Hook target not found: $hookName") else 0

    fun resolutionFailure(tag: String, msg: String, throwable: Throwable): Int =
        if (BuildConfig.DEBUG) AndroidLog.e(tag, msg, throwable) else 0
}
