// App updater: checks release fields before any APK download starts.
package com.olive.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppReleaseManifestTest {
    @Test
    fun `valid signed release manifest is parsed`() {
        val release = AppReleaseManifest.fromJson(validJson())
        assertEquals(10001L, release.versionCode)
        assertEquals("1.0.0", release.versionName)
    }

    @Test
    fun `wrong package and host are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppReleaseManifest.fromJson(validJson().replace("com.olive.app", "com.fake.app"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppReleaseManifest.fromJson(validJson().replace("firebasestorage.googleapis.com", "example.com"))
        }
    }

    @Test
    fun `invalid checksum and oversized APK are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppReleaseManifest.fromJson(validJson().replace("${"a".repeat(64)}", "bad"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppReleaseManifest.fromJson(validJson().replace("123456", "9999999999"))
        }
    }

    @Test
    fun `minimum supported version cannot be newer than the release`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppReleaseManifest.fromJson(
                validJson().replace("\"minimumSupportedVersionCode\":1", "\"minimumSupportedVersionCode\":10002")
            )
        }
    }

    @Test
    fun `update topic matches expected fcm release channel`() {
        assertEquals("app_updates", AppUpdateScheduler.TOPIC_APP_UPDATES)
    }

    private fun validJson() = """
        {
          "packageName":"com.olive.app",
          "versionCode":10001,
          "versionName":"1.0.0",
          "minimumSupportedVersionCode":1,
          "apkUrl":"https://firebasestorage.googleapis.com/v0/b/olive-21239.firebasestorage.app/o/public%2Fandroid%2Freleases%2F10001%2Folive.apk?alt=media",
          "sha256":"${"a".repeat(64)}",
          "sizeBytes":123456,
          "publishedAt":"2026-11-09T00:00:00Z",
          "mandatory":false,
          "releaseNotes":["Signed release"]
        }
    """.trimIndent()
}
