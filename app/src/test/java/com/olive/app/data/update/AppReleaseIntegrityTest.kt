package com.olive.app.data.update

import android.content.ContextWrapper
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppReleaseIntegrityTest {
    @Test
    fun verifiesTheBytesAndThenRequiresTheExpectedAndroidPackageAndVersion() {
        val app = RuntimeEnvironment.getApplication()
        val packages = mock(PackageManager::class.java)
        val context = object : ContextWrapper(app) {
            override fun getPackageManager() = packages
        }
        val client = AppUpdateClient(context)
        val apk = File.createTempFile("synthetic-release-", ".apk", app.cacheDir)
        try {
            apk.writeText("Synthetic APK fixture")
            val archive = PackageInfo().apply {
                packageName = "com.olive.app"
                setLongVersionCode(202L)
            }
            `when`(packages.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_ACTIVITIES)).thenReturn(archive)
            val release = AppReleaseManifest.fromJson(manifest(apk.readBytes()).toString())
            assertTrue(client.verifyFile(apk, release))
            assertFalse(client.verifyFile(apk, release.copy(versionCode = 203L)))
            assertFalse(client.verifyFile(apk, release.copy(packageName = "example.wrong.package")))
            assertFalse(client.verifyFile(apk, release.copy(sizeBytes = release.sizeBytes - 1)))
            apk.writeText("Modified APK contents")
            assertFalse(client.verifyFile(apk, release))
            verify(packages, times(3)).getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_ACTIVITIES)
            assertEquals("olive-202.apk", AppUpdateClient.fileName(release.versionCode))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun releaseManifestRejectsUntrustedEndpointsAndMissingIntegrityDataBeforeDownloading() {
        val payload = manifest(byteArrayOf(1))
        val parsed = AppReleaseManifest.fromJson(payload.toString())
        assertEquals(listOf("Synthetic release details"), parsed.releaseNotes)
        assertEquals(1L, parsed.minimumSupportedVersionCode)
        assertFalse(parsed.mandatory)
        val invalid = mapOf(
            "apkUrl" to listOf("http://yourolivebranch.ca/app.apk", "https://yourolivebranch.ca.example.test/app.apk"),
            "sha256" to listOf("", "bad-checksum"),
            "sizeBytes" to listOf(0, Long.MAX_VALUE),
            "minimumSupportedVersionCode" to listOf(0, 203),
        )
        invalid.forEach { (key, values) ->
            values.forEach { value ->
                val malformed = JSONObject(payload.toString()).put(key, value)
                assertThrows(IllegalArgumentException::class.java) { AppReleaseManifest.fromJson(malformed.toString()) }
            }
        }
    }

    @Test
    fun downloadsVerifySizeAndChecksumBeforeReplacingAnExistingVerifiedApk() {
        val app = RuntimeEnvironment.getApplication()
        val packages = mock(PackageManager::class.java)
        val context = object : ContextWrapper(app) {
            override fun getPackageManager() = packages
        }
        val archive = PackageInfo().apply {
            packageName = "com.olive.app"
            setLongVersionCode(202L)
        }
        val apkBytes = "Synthetic release bytes".toByteArray()
        var response = apkBytes
        val created = mutableListOf<HttpURLConnection>()
        val client = AppUpdateClient(context) {
            val connection = mock(HttpURLConnection::class.java)
            `when`(connection.responseCode).thenReturn(200)
            `when`(connection.inputStream).thenReturn(response.inputStream())
            created += connection
            connection
        }
        val release = AppReleaseManifest.fromJson(manifest(apkBytes).toString())
        val destination = File(app.filesDir, "updates/${AppUpdateClient.fileName(release.versionCode)}")
        val temporary = File(destination.parentFile, destination.name + ".download")
        `when`(packages.getPackageArchiveInfo(destination.absolutePath, PackageManager.GET_ACTIVITIES)).thenReturn(archive)
        try {
            destination.parentFile!!.mkdirs()
            destination.writeBytes(apkBytes)
            for (invalid in listOf(apkBytes.copyOf(apkBytes.size - 1), apkBytes + 1.toByte(),
                apkBytes.copyOf().also { it[0] = 0 })) {
                response = invalid
                assertThrows(IllegalStateException::class.java) { client.download(release) }
                assertArrayEquals(apkBytes, destination.readBytes())
            }
            response = apkBytes
            assertEquals(destination, client.download(release))
            assertArrayEquals(apkBytes, destination.readBytes())
            assertFalse(temporary.exists())
            created.forEach { verify(it).instanceFollowRedirects = false }
        } finally {
            destination.delete()
            temporary.delete()
        }
    }

    @Test
    fun releaseManifestReadsOnlyTheConfiguredSecureEndpointAndRejectsRedirects() {
        val context = RuntimeEnvironment.getApplication()
        val rejected = mock(HttpURLConnection::class.java)
        val ok = mock(HttpURLConnection::class.java)
        `when`(rejected.responseCode).thenReturn(302)
        `when`(ok.responseCode).thenReturn(200)
        val data = manifest("fixture".toByteArray())
        val stream = data.toString().byteInputStream()
        `when`(ok.inputStream).thenReturn(stream)
        var request = rejected
        val client = AppUpdateClient(context) { url ->
            assertEquals("https", url.protocol)
            request
        }
        assertThrows(IllegalStateException::class.java) { client.fetchLatest() }
        verify(rejected, never()).inputStream
        request = ok
        assertEquals(202L, client.fetchLatest().versionCode)
        verify(ok).setRequestProperty("Accept", "application/json")
        verify(ok).instanceFollowRedirects = false
    }

    private fun manifest(bytes: ByteArray) = JSONObject()
        .put("packageName", "com.olive.app")
        .put("versionCode", 202)
        .put("versionName", "2.0.2")
        .put("apkUrl", "https://yourolivebranch.ca/fixture.apk")
        .put("sizeBytes", bytes.size)
        .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        .put("releaseNotes", JSONArray(listOf("Synthetic release details", " ")))
}
