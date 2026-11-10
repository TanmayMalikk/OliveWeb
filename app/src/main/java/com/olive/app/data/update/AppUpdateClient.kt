// All app roles: downloads, verifies, and schedules signed release updates before Android can install them.
package com.olive.app.data.update

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.*
import com.olive.app.BuildConfig
import com.olive.app.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.json.JSONArray
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

    // Checks the APK manifest and signer without installing it so untrusted APKs are rejected.
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

// Checks for a release update in the background and prepares it without interrupting the current screen.
internal class AppUpdateWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    // A failed network check is retried later; a malformed or untrusted release is never installed.
    override suspend fun doWork(): Result = runCatching {
        val client = AppUpdateClient(applicationContext)
        val release = client.fetchLatest()
        if (release.versionCode <= BuildConfig.VERSION_CODE) return Result.success()
        client.download(release)
        notifyReady(release)
        Result.success()
    }.getOrElse { error ->
        if (runAttemptCount < 3 && error is java.io.IOException) Result.retry() else Result.failure()
    }

    // The notification opens a small installer screen; Android owns the final confirmation.
    private fun notifyReady(release: AppReleaseManifest) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, applicationContext.getString(R.string.ui_app_updates), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val intent = Intent(applicationContext, AppUpdateInstallerActivity::class.java)
            .putExtra(AppUpdateInstallerActivity.EXTRA_RELEASE_JSON, release.toJson())
        val pending = PendingIntent.getActivity(
            applicationContext,
            release.versionCode.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION,
            NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_olive)
                .setContentTitle(applicationContext.getString(R.string.ui_a_new_update_is_available))
                .setContentText(release.versionName)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun AppReleaseManifest.toJson() = JSONObject().apply {
        put("packageName", packageName)
        put("versionCode", versionCode)
        put("versionName", versionName)
        put("minimumSupportedVersionCode", minimumSupportedVersionCode)
        put("apkUrl", apkUrl)
        put("sha256", sha256)
        put("sizeBytes", sizeBytes)
        put("publishedAt", publishedAt)
        put("mandatory", mandatory)
        put("releaseNotes", JSONArray(releaseNotes))
    }.toString()

    companion object {
        private const val CHANNEL = "app-updates"
        private const val NOTIFICATION = 7401
    }
}

// Schedules periodic background update checks over unmetered Wi-Fi.
internal object AppUpdateScheduler {
    // Checks once after startup and then once per day on an unmetered connection because the APK is large.
    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniqueWork(
            "olive-update-startup",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AppUpdateWorker>().setConstraints(constraints).build(),
        )
        manager.enqueueUniquePeriodicWork(
            "olive-update-daily",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AppUpdateWorker>(24, TimeUnit.HOURS).setConstraints(constraints).build(),
        )
    }
}

// Hands a verified APK to Android's protected package installer.
class AppUpdateInstallerActivity : Activity() {
    private var askedForInstallAccess = false

    // Shows a simple status while Android checks whether Olive may request package installation.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = getString(R.string.ui_a_new_update_is_available)
            textSize = 20f
            setPadding(48, 96, 48, 48)
        })
    }

    // Returning from the unknown-apps settings screen continues the same verified install.
    override fun onResume() {
        super.onResume()
        val raw = intent.getStringExtra(EXTRA_RELEASE_JSON) ?: return finish()
        val release = runCatching { AppReleaseManifest.fromJson(raw) }.getOrNull() ?: return finish()
        val file = File(filesDir, "updates/${AppUpdateClient.fileName(release.versionCode)}")
        if (!AppUpdateClient(this).verifyFile(file, release)) return finish()
        if (!packageManager.canRequestPackageInstalls()) {
            if (askedForInstallAccess) return
            askedForInstallAccess = true
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                )
            )
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.documents", file)
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
        finish()
    }

    companion object {
        const val EXTRA_RELEASE_JSON = "release-json"
    }
}

private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this).toHex()
private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
