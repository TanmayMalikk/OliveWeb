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

/**
 * Encodes a `@Serializable` model into an `org.json.JSONObject` using [oliveJson].
 *
 * @receiver The value to serialize to a JSON object.
 * @param T The type of the value to serialize.
 * @return The serialized value as a JSON object.
 */
internal inline fun <reified T> T.encodeToJsonObject(): JSONObject =
    JSONObject(oliveJson.encodeToString(this))

/**
 * Decodes a `@Serializable` model from an `org.json.JSONObject` using [oliveJson].
 *
 * @receiver The JSON object containing the stored fields.
 * @param T The type to deserialize from the JSON object.
 * @return The deserialized value of the requested type.
 */
internal inline fun <reified T> JSONObject.decodeJsonObject(): T =
    oliveJson.decodeFromString(toString())

/**
 * Converts an untyped map payload into a string-keyed map, or an empty map if malformed.
 *
 * @receiver The untyped Firebase value to validate as a map.
 * @return The string-keyed payload, or an empty map when the input is malformed.
 */
internal fun Any?.asStringKeyedMap(): Map<String, Any?> =
    (this as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()

/**
 * Requires an untyped Cloud Function or Firestore response to be a map.
 *
 * @receiver The untyped Firebase value to validate as a map.
 * @param label The label displayed for the control.
 * @return The response as a map with string keys.
 */
internal fun Any?.requireStringKeyedMap(label: String = "response"): Map<String, Any?> =
    (this as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        ?: error("Invalid $label")

/**
 * Reads a complete string-keyed record without trusting erased Firebase generic types.
 *
 * @receiver The untyped Firebase value to validate as a map.
 * @return The complete string-to-string map, or null if its shape is invalid.
 */
internal fun Any?.cloudStringMap(): Map<String, String>? {
    val fields = this as? Map<*, *> ?: return null
    return buildMap {
        for ((key, value) in fields) {
            if (key !is String || value !is String) return null
            put(key, value)
        }
    }
}

/**
 * Reads a required [String] field from an untyped map or throws [IllegalStateException].
 *
 * @receiver The untyped map containing the stored response fields.
 * @param name The name of the field to read from the payload.
 * @return The required string value.
 */
internal fun Map<*, *>.requireString(name: String): String =
    get(name) as? String ?: error("Missing $name")

/**
 * Reads an optional [String] field from an untyped map, falling back to [default].
 *
 * @receiver The untyped map containing the stored response fields.
 * @param name The name of the field to read from the payload.
 * @param default The fallback used when the supplied value is absent or invalid.
 * @return The string field value, or the supplied default.
 */
internal fun Map<*, *>.optionalString(name: String, default: String = ""): String =
    get(name) as? String ?: default

/**
 * Reads a required numeric field as a [Long] from an untyped map or throws.
 *
 * @receiver The untyped map containing the stored response fields.
 * @param name The name of the field to read from the payload.
 * @return The required numeric field converted to a Long.
 */
internal fun Map<*, *>.requireLong(name: String): Long =
    (get(name) as? Number)?.toLong() ?: error("Missing $name")

/**
 * Reads an optional numeric field as an [Int] from an untyped map, falling back to [default].
 *
 * @receiver The untyped map containing the stored response fields.
 * @param name The name of the field to read from the payload.
 * @param default The fallback used when the supplied value is absent or invalid.
 * @return The numeric field converted to an Int, or the supplied default.
 */
internal fun Map<*, *>.optionalInt(name: String, default: Int = 0): Int =
    (get(name) as? Number)?.toInt() ?: default

/**
 * Reads a finite non-negative [Float] from an untyped map, clamping invalid values to `0f`.
 *
 * @receiver The untyped map containing the stored response fields.
 * @param name The name of the field to read from the payload.
 * @return The finite non-negative number, or zero when it is missing or invalid.
 */
internal fun Map<*, *>.nonNegativeFloat(name: String): Float =
    (get(name) as? Number)?.toFloat()?.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f

/**
 * Reads an optional nested `Map<String, String>` field from an untyped map, validating entry types.
 *
 * @receiver The untyped map containing the stored response fields.
 * @param name The name of the field to read from the payload.
 * @return The nested string-to-string record, omitting entries with incompatible types.
 */
internal fun Map<*, *>.stringMap(name: String): Map<String, String> =
    (get(name) as? Map<*, *>)?.entries?.associate { entry ->
        (entry.key as? String ?: error("Invalid $name key")) to
            (entry.value as? String ?: error("Invalid $name value"))
    }.orEmpty()

/**
 * Reads a required list of strings from an untyped map, filtering out non-string items.
 *
 * @receiver The untyped map containing the stored response fields.
 * @param name The name of the field to read from the payload.
 * @return The string elements of the required list.
 */
internal fun Map<*, *>.requireStringList(name: String): List<String> =
    (get(name) as? List<*>)?.filterIsInstance<String>() ?: error("Missing $name")

/**
 * Reads a finite non-negative [Float] from a [JSONObject], clamping invalid values to `0f`.
 *
 * @receiver The JSON object containing the stored fields.
 * @param name The name of the field to read from the payload.
 * @return The finite non-negative number, or zero when it is missing or invalid.
 */
internal fun JSONObject.nonNegativeFloat(name: String): Float =
    optDouble(name, 0.0).toFloat().takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f

/**
 * Encodes raw bytes as lowercase hexadecimal characters.
 *
 * @receiver The bytes to represent as hexadecimal text.
 * @return The lowercase hexadecimal representation of the bytes.
 */
internal fun ByteArray.toHexString(): String =
    joinToString("") { "%02x".format(it.toInt() and 255) }

/**
 * Computes the lowercase hexadecimal SHA-256 digest of a UTF-8 string.
 *
 * @param value The text whose UTF-8 bytes are hashed.
 * @return The lowercase hexadecimal SHA-256 digest of the UTF-8 input.
 */
internal fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHexString()

private val installationIdLock = Any()

/**
 * Returns a stable, lazily generated per-app-install UUID stored in private preferences.
 *
 * @param context The Android context used for resources or system services.
 * @param preferencesName The name of the private preferences file.
 * @param key The key used to look up this entry.
 * @return The saved installation UUID, generated and persisted if it did not already exist.
 */
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

