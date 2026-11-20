// Shared connectivity, JSON serialization, type-safe map parsing, and device identity helpers.
package com.olive.app.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

/** Shared Kotlinx JSON codec used for local caches and encrypted persistence. */
internal val oliveJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

/** Encodes a `@Serializable` model into an `org.json.JSONObject` using [oliveJson]. */
internal inline fun <reified T> T.encodeToJsonObject(): JSONObject =
    JSONObject(oliveJson.encodeToString(this))

/** Decodes a `@Serializable` model from an `org.json.JSONObject` using [oliveJson]. */
internal inline fun <reified T> JSONObject.decodeJsonObject(): T =
    oliveJson.decodeFromString(toString())

/** Converts an untyped map payload into a string-keyed map, or an empty map if malformed. */
internal fun Any?.asStringKeyedMap(): Map<String, Any?> =
    (this as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()

/** Requires an untyped Cloud Function or Firestore response to be a map. */
internal fun Any?.requireStringKeyedMap(label: String = "response"): Map<String, Any?> =
    (this as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        ?: error("Invalid $label")

/** Reads a complete string-keyed record without trusting erased Firebase generic types. */
internal fun Any?.cloudStringMap(): Map<String, String>? {
    val fields = this as? Map<*, *> ?: return null
    return buildMap {
        for ((key, value) in fields) {
            if (key !is String || value !is String) return null
            put(key, value)
        }
    }
}

/** Reads a required [String] field from an untyped map or throws [IllegalStateException]. */
internal fun Map<*, *>.requireString(name: String): String =
    get(name) as? String ?: error("Missing $name")

/** Reads an optional [String] field from an untyped map, falling back to [default]. */
internal fun Map<*, *>.optionalString(name: String, default: String = ""): String =
    get(name) as? String ?: default

/** Reads a required numeric field as a [Long] from an untyped map or throws. */
internal fun Map<*, *>.requireLong(name: String): Long =
    (get(name) as? Number)?.toLong() ?: error("Missing $name")

/** Reads an optional numeric field as an [Int] from an untyped map, falling back to [default]. */
internal fun Map<*, *>.optionalInt(name: String, default: Int = 0): Int =
    (get(name) as? Number)?.toInt() ?: default

/** Reads a finite non-negative [Float] from an untyped map, clamping invalid values to `0f`. */
internal fun Map<*, *>.nonNegativeFloat(name: String): Float =
    (get(name) as? Number)?.toFloat()?.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f

/** Reads an optional nested `Map<String, String>` field from an untyped map, validating entry types. */
internal fun Map<*, *>.stringMap(name: String): Map<String, String> =
    (get(name) as? Map<*, *>)?.entries?.associate { entry ->
        (entry.key as? String ?: error("Invalid $name key")) to
            (entry.value as? String ?: error("Invalid $name value"))
    }.orEmpty()

/** Reads a required list of strings from an untyped map, filtering out non-string items. */
internal fun Map<*, *>.requireStringList(name: String): List<String> =
    (get(name) as? List<*>)?.filterIsInstance<String>() ?: error("Missing $name")

/** Reads a finite non-negative [Float] from a [JSONObject], clamping invalid values to `0f`. */
internal fun JSONObject.nonNegativeFloat(name: String): Float =
    optDouble(name, 0.0).toFloat().takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f

/** Encodes raw bytes as lowercase hexadecimal characters. */
internal fun ByteArray.toHexString(): String =
    joinToString("") { "%02x".format(it.toInt() and 255) }

/** Computes the lowercase hexadecimal SHA-256 digest of a UTF-8 string. */
internal fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHexString()

private val installationIdLock = Any()

/** Returns a stable, lazily generated per-app-install UUID stored in private preferences. */
internal fun persistentInstallationId(context: Context, preferencesName: String, key: String): String =
    synchronized(installationIdLock) {
        val appContext = context.applicationContext ?: context
        val preferences = appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        preferences.getString(key, null)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also { preferences.edit { putString(key, it) } }
    }

/**
 * Checks whether validated internet connection.
 *
 * @param context Android context used to access system services, resources, or storage.
 * @return `true` if has validated internet connection, or `false` otherwise.
 */
internal fun hasValidatedInternetConnection(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

