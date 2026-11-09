// All app roles: downloads and verifies signed release updates before Android can install them.
package com.olive.app.data.update

import android.content.Context
import android.content.pm.PackageManager
import com.olive.app.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

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
        val directory = File(context.filesDir, "updates").apply { mkdirs() }
        directory.listFiles()?.filter { it.name != fileName(release.versionCode) }?.forEach(File::delete)
        val target = File(directory, fileName(release.versionCode))
        if (target.isFile && verifyFile(target, release)) return target
        target.delete()
        val temporary = File(directory, "${target.name}.part")
        temporary.delete()
        val connection = openHttps(release.apkUrl)
        connection.setRequestProperty("Accept", "application/vnd.android.package-archive")
        connection.connect()
        check(connection.responseCode == HttpURLConnection.HTTP_OK) {
            "APK server returned ${connection.responseCode}"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        connection.inputStream.use { input ->
            temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    check(total <= release.sizeBytes) { "APK is larger than declared" }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        }
        check(total == release.sizeBytes) { "APK size does not match the release manifest" }
        check(digest.digest().toHex() == release.sha256) { "APK checksum does not match" }
        check(temporary.renameTo(target)) { "Unable to finish the APK download" }
        check(verifyArchive(target, release)) { "APK identity or signing certificate does not match" }
        return target
    }

    // Rechecks a cached APK before the system installer receives its FileProvider URI.
    fun verifyFile(file: File, release: AppReleaseManifest): Boolean {
        if (!file.isFile || file.length() != release.sizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex() == release.sha256 && verifyArchive(file, release)
    }

    // Android exposes the APK manifest and signer without installing it, so a different app or
    // certificate cannot be passed to the package installer by a compromised download host.
    private fun verifyArchive(file: File, release: AppReleaseManifest): Boolean {
        val archive = archivePackage(file) ?: return false
        if (archive.packageName != release.packageName || archive.longVersionCode != release.versionCode) return false
        val installed = installedPackage()
        val archiveSigners = archive.signingInfo?.apkContentsSigners.orEmpty().map { it.toByteArray().sha256() }.toSet()
        val installedSigners = installed.signingInfo?.apkContentsSigners.orEmpty().map { it.toByteArray().sha256() }.toSet()
        return archiveSigners.isNotEmpty() && archiveSigners == installedSigners
    }

    // Android 13 introduced typed package-info flags; older supported phones use the int overload.
    @Suppress("DEPRECATION")
    private fun archivePackage(file: File) =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    // Reads the certificate of the running app in the form supported by the current Android version.
    @Suppress("DEPRECATION")
    private fun installedPackage() =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    private fun openHttps(rawUrl: String): HttpURLConnection {
        val uri = URI(rawUrl)
        require(uri.scheme == "https") { "Only HTTPS release URLs are accepted" }
        require(uri.host in setOf("yourolivebranch.ca", "www.yourolivebranch.ca", "firebasestorage.googleapis.com")) {
            "Release host is not trusted"
        }
        return (uri.toURL().openConnection() as HttpURLConnection).also {
                it.connectTimeout = 10_000
                it.readTimeout = 60_000
                it.instanceFollowRedirects = false
            }
    }

    companion object {
        fun fileName(versionCode: Long) = "olive-update-$versionCode.apk"
    }
}

private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this).toHex()
private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
