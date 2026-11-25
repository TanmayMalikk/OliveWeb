// All app roles: downloads and verifies signed release updates before Android can install them.
package com.olive.app.data.update

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.edit
import com.olive.app.BuildConfig
import com.olive.app.data.network.oliveJson
import com.olive.app.data.network.toHexString
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.json.JSONObject

// Downloads and verifies signed release updates before Android can install them.
internal class AppUpdateClient(
    private val context: Context,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    /**
     * Reads the current release description without accepting redirects to an unrelated host.
     *
     * @return The resulting [AppReleaseManifest] for fetch latest.
     */
    fun fetchLatest(): AppReleaseManifest {
        val connection = openHttps(BuildConfig.UPDATE_MANIFEST_URL)
        try {
            connection.setRequestProperty("Accept", "application/json")
            connection.connect()
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Release server returned ${connection.responseCode}"
            }
            val text = connection.inputStream.bufferedReader().use { reader ->
                val chars = CharArray(MAX_MANIFEST_LENGTH + 1)
                val length = reader.readNCharsCompat(chars)
                check(length <= MAX_MANIFEST_LENGTH) { "Release response is too large" }
                chars.concatToString(0, length)
            }
            return AppReleaseManifest.fromJson(text)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Prepares the target.
     *
     * @param release Release supplied to [prepare].
     * @return The resulting [File] instance.
     */
    fun prepare(release: AppReleaseManifest): File = synchronized(downloadLock) {
        release.requireValid()
        val cached = File(context.filesDir, "updates/${fileName(release.versionCode)}")
        if (verifyFile(cached, release)) cached else download(release)
    }

    /**
     * Saves the APK to private app storage while checking its declared size and SHA-256 checksum.
     *
     * @param release Release supplied to [download].
     * @return The resulting [File] instance.
     */
    fun download(release: AppReleaseManifest): File {
        release.requireValid()
        val destination = File(context.filesDir, "updates/${fileName(release.versionCode)}")
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, "${destination.name}.download")
        if (temp.exists()) temp.delete()
        val connection = openHttps(release.apkUrl)
        connection.connect()
        check(connection.responseCode == HttpURLConnection.HTTP_OK) {
            "APK download returned ${connection.responseCode}"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        temp.outputStream().use { output ->
            connection.inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    totalBytes += read
                    check(totalBytes <= release.sizeBytes) { "APK download exceeded declared size" }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        }
        check(totalBytes == release.sizeBytes) { "APK download size did not match release manifest" }
        check(digest.digest().toHexString().equals(release.sha256, ignoreCase = true)) {
            "APK checksum did not match release manifest"
        }
        if (destination.exists()) destination.delete()
        check(temp.renameTo(destination)) { "Could not move verified APK into place" }
        check(verifyFile(destination, release)) { "Verified APK failed validation on disk" }
        return destination
    }

    /**
     * Validates an already downloaded release APK against the manifest before Android installs it.
     *
     * @param file Local file to read, hash, process, or write.
     * @param release Release supplied to [verifyFile].
     * @return `true` if verify file, or `false` otherwise.
     */
    fun verifyFile(file: File, release: AppReleaseManifest): Boolean {
        if (!file.isFile || file.length() != release.sizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        if (!digest.digest().toHexString().equals(release.sha256, ignoreCase = true)) return false
        val packageArchive = context.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_ACTIVITIES,
        ) ?: return false
        val versionCode = packageArchive.longVersionCodeCompat()
        return packageArchive.packageName == release.packageName && versionCode == release.versionCode
    }

    /**
     * Opens HTTPS.
     *
     * @param url HTTPS URL to validate, fetch, or open.
     * @return The resulting [HttpURLConnection] for open HTTPS.
     */
    private fun openHttps(url: String): HttpURLConnection {
        val uri = URI(url)
        check(uri.scheme == "https") { "Only HTTPS update endpoints are permitted" }
        return openConnection(uri.toURL()).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
        }
    }

    /**
     * Computes long version code compat for this android content pm package info.
     *
     * @return The computed long version code compat numeric value.
     */
    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        longVersionCode

    companion object {
        private const val MAX_MANIFEST_LENGTH = 64 * 1024
        private val downloadLock = Any()
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        /**
         * Computes file name.
         *
         * @param versionCode Version code supplied to [fileName].
         * @return The resulting file name string.
         */
        fun fileName(versionCode: Long): String = "olive-$versionCode.apk"
    }
}

/**
 * Reads n chars compat on java io reader.
 *
 * @param buffer Buffer supplied to [readNCharsCompat].
 * @return The computed read n chars compat numeric value.
 */
private fun java.io.Reader.readNCharsCompat(buffer: CharArray): Int {
    var length = 0
    while (length < buffer.size) {
        val count = read(buffer, length, buffer.size - length)
        if (count < 0) break
        length += count
    }
    return length
}

// Describes one signed Olive APK published by the release server.
@Serializable
internal data class AppReleaseManifest(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minimumSupportedVersionCode: Long = 1L,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val publishedAt: String = "",
    val mandatory: Boolean = false,
    val releaseNotes: List<String> = emptyList(),
) {
    /**
     * Computes requires update.
     *
     * @param installedVersion Installed version supplied to [requiresUpdate].
     * @return The resulting requires update.
     */
    fun requiresUpdate(installedVersion: Long) =
        versionCode > installedVersion && (mandatory || installedVersion < minimumSupportedVersionCode)

    /**
     * Serializes this app release manifest into a JSON object.
     *
     * @return JSON representation of this object.
     */
    fun toJson(): String = oliveJson.encodeToString(this)

    companion object {
        /**
         * Converts the small public REST response into values the updater can validate safely.
         *
         * @param text Text string to display, parse, or send.
         * @return Parsed instance constructed from the JSON data.
         */
        fun fromJson(text: String): AppReleaseManifest {
            val decoded = oliveJson.decodeFromString<AppReleaseManifest>(text)
            return decoded.copy(
                packageName = decoded.packageName.trim(),
                versionName = decoded.versionName.trim(),
                apkUrl = decoded.apkUrl.trim(),
                sha256 = decoded.sha256.lowercase().trim(),
                publishedAt = decoded.publishedAt.trim(),
                releaseNotes = decoded.releaseNotes.map(String::trim).filter(String::isNotEmpty),
            ).also(AppReleaseManifest::requireValid)
        }
    }

    /**
     * Rejects incomplete or unexpectedly large releases before any APK bytes are downloaded.
     */
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

internal data class AppUpdateOffer(
    val release: AppReleaseManifest,
    val required: Boolean,
)

/** Persists the latest available release across sessions. */
internal class AppUpdateStore(
    context: Context,
    private val installedVersion: Long = BuildConfig.VERSION_CODE.toLong(),
) {
    private val preferences = context.getSharedPreferences("olive_app_update", Context.MODE_PRIVATE)

    /**
     * Refreshes the app update store.
     *
     * @param client Client supplied to [refresh].
     * @return The resulting [AppReleaseManifest] instance.
     */
    fun refresh(client: AppUpdateClient): AppReleaseManifest = synchronized(refreshLock) {
        client.fetchLatest().also(::publish)
    }

    /**
     * Returns the offer.
     *
     * @return The resulting [AppUpdateOffer], or `null` if unavailable.
     */
    fun offer(): AppUpdateOffer? {
        val raw = preferences.getString(RELEASE, null) ?: return null
        val release = runCatching { AppReleaseManifest.fromJson(raw) }.getOrNull()
            ?.takeIf { it.versionCode > installedVersion } ?: return null
        return AppUpdateOffer(release, required = release.requiresUpdate(installedVersion))
    }

    /**
     * Publishes the app update store.
     *
     * @param release Release supplied to [publish].
     */
    fun publish(release: AppReleaseManifest) {
        preferences.edit {
            if (release.versionCode > installedVersion) putString(RELEASE, release.toJson())
            else remove(RELEASE)
        }
    }

    /**
     * Starts observing the app update store.
     *
     * @param onOffer Callback invoked when offer changes or occurs.
     * @return Subscription or registration handle to close when observation is no longer needed.
     */
    fun observe(onOffer: (AppUpdateOffer?) -> Unit): AutoCloseable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onOffer(offer()) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onOffer(offer())
        return AutoCloseable { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        val refreshLock = Any()
        const val RELEASE = "release"
    }
}
