// All app roles: describes one signed Olive APK published by the release server.
package com.olive.app.data.update

import java.net.URI
import org.json.JSONObject

// Describes one signed Olive APK published by the release server.
internal data class AppReleaseManifest(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minimumSupportedVersionCode: Long,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val publishedAt: String,
    val mandatory: Boolean,
    val releaseNotes: List<String>,
) {
    companion object {
        // Converts the small public REST response into values the updater can validate safely.
        fun fromJson(text: String): AppReleaseManifest {
            val source = JSONObject(text)
            val notes = source.optJSONArray("releaseNotes")
            val releaseNotes = buildList {
                if (notes != null) for (index in 0 until notes.length()) {
                    notes.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
                }
            }
            return AppReleaseManifest(
                packageName = source.getString("packageName").trim(),
                versionCode = source.getLong("versionCode"),
                versionName = source.getString("versionName").trim(),
                minimumSupportedVersionCode = source.optLong("minimumSupportedVersionCode", 1L),
                apkUrl = source.getString("apkUrl").trim(),
                sha256 = source.getString("sha256").lowercase().trim(),
                sizeBytes = source.getLong("sizeBytes"),
                publishedAt = source.optString("publishedAt").trim(),
                mandatory = source.optBoolean("mandatory", false),
                releaseNotes = releaseNotes,
            ).also(AppReleaseManifest::requireValid)
        }
    }

    // Rejects incomplete or unexpectedly large releases before any APK bytes are downloaded.
    fun requireValid() {
        require(packageName == "com.olive.app") { "Unexpected Android package" }
        require(versionCode > 0L) { "Invalid version code" }
        require(versionName.length in 1..40) { "Invalid version name" }
        require(minimumSupportedVersionCode > 0L) { "Invalid minimum version" }
        require(minimumSupportedVersionCode <= versionCode) { "Minimum version is newer than the release" }
        require(sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid APK checksum" }
        require(sizeBytes in 1L..700L * 1024L * 1024L) { "Invalid APK size" }
        val url = URI(apkUrl)
        require(url.scheme == "https") { "APK must use HTTPS" }
        require(url.host in setOf("yourolivebranch.ca", "www.yourolivebranch.ca", "firebasestorage.googleapis.com")) {
            "APK host is not trusted"
        }
    }
}

