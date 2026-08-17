package tn.loukious.facebookappadsremover

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

@Suppress("DEPRECATION")
fun bundleToJsonObject(bundle: Bundle): JSONObject {
    val json = JSONObject()
    runCatching { bundle.keySet().toList() }
        .getOrDefault(emptyList())
        .forEach { key ->
            val value = runCatching { bundle.get(key) }.getOrNull()
            putJsonCompatibleValue(json, key, value)
        }
    return json
}

fun putJsonCompatibleValue(json: JSONObject, key: String, value: Any?) {
    when (value) {
        null -> json.put(key, JSONObject.NULL)
        is String -> json.put(key, value)
        is Boolean -> json.put(key, value)
        is Number -> json.put(key, value)
        is JSONObject -> json.put(key, value)
        is JSONArray -> json.put(key, value)
        is Bundle -> json.put(key, bundleToJsonObject(value))
        else -> json.put(key, value.toString())
    }
}

fun copyJsonObject(source: JSONObject): JSONObject {
    val result = JSONObject()
    val keys = source.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        result.put(key, source.opt(key))
    }
    return result
}
