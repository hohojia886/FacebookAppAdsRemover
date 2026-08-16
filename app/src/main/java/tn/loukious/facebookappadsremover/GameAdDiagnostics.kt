package tn.loukious.facebookappadsremover

import android.os.Bundle
import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.lang.reflect.Method

internal fun hookGameAdSystemDiagnostics(module: XposedModule, classLoader: ClassLoader) {
    if (!ENABLE_GAME_AD_DIAGNOSTICS || gameAdSystemDiagnosticsInstalled.getAndIncrement() != 0) return

    // Logic for system-level game ad diagnostics
    Logger.i(TAG, "Game ad system diagnostics active")
}

internal fun markGameAdDiagnosticFlow(tag: String) {
    lastGameAdDiagnosticFlowMs.set(System.currentTimeMillis())
}

internal fun logGameAdDiagnostic(tag: String, message: String) {
    if (!ENABLE_GAME_AD_DIAGNOSTICS) return
    Logger.i(TAG, "[DIAG] $tag: $message")
}

internal fun formatDiagArgs(args: Array<Any?>?): String {
    if (args == null) return "null"
    return args.joinToString(prefix = "(", postfix = ")") { formatDiagValue(it) }
}

internal fun formatDiagValue(value: Any?, depth: Int = 0): String {
    if (value == null) return "null"
    if (depth > 3) return value.javaClass.simpleName
    
    return when (value) {
        is String -> "\"$value\""
        is Number, is Boolean -> value.toString()
        is JSONObject -> "JSONObject{${value.toString().take(100)}}"
        is Bundle -> "Bundle{keys=${value.keySet().joinToString()}}"
        else -> value.javaClass.simpleName
    }
}

internal fun formatDiagThrowable(t: Throwable?): String {
    if (t == null) return "none"
    return "${t.javaClass.name}: ${t.message}"
}
