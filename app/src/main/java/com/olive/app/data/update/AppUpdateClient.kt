// All app roles: downloads and verifies signed release updates before Android can install them.
package com.olive.app.data.update

import android.content.Context
import android.content.pm.PackageManager
import com.olive.app.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

// Downloads and verifies signed release updates before Android can install them.
internal class AppUpdateClient(private val context: Context) {
    // Reads the current release description without accepting redirects to an unrelated host.
    fun fetchLatest(): AppReleaseManifest {
        val connection = openHttps(BuildConfig.UPDATE_MANIFEST_URL)
        connection.setRequestProperty("Accept", "application/json")
        connection.connect()
        check(connection.responseCode == HttpURLConnection.HTTP_OK) {
            "Release server returned ${connection.responseCode}"
        }
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        check(text.length <= 64 * 1024) { "Release response is too large" }
        return AppReleaseManifest.fromJson(text)
    }

    // Saves the APK to private app storage while checking its declared size and SHA-256 checksum.
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
        check(digest.digest().toHex().equals(release.sha256, ignoreCase = true)) {
            "APK checksum did not match release manifest"
        }
        if (destination.exists()) destination.delete()
        check(temp.renameTo(destination)) { "Could not move verified APK into place" }
        check(verifyFile(destination, release)) { "Verified APK failed validation on disk" }
        return destination
    }

    // Validates an already downloaded release APK against the manifest before Android installs it.
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
        if (!digest.digest().toHex().equals(release.sha256, ignoreCase = true)) return false
        val packageArchive = context.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_ACTIVITIES,
        ) ?: return false
        val versionCode = packageArchive.longVersionCodeCompat()
        return packageArchive.packageName == release.packageName && versionCode == release.versionCode
    }

    private fun openHttps(url: String): HttpURLConnection {
        val uri = URI(url)
        check(uri.scheme == "https") { "Only HTTPS update endpoints are permitted" }
        return (uri.toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
        }
    }

    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        longVersionCode

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        fun fileName(versionCode: Long): String = "olive-$versionCode.apk"
    }
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
