// All app roles: hands a verified APK to Android's protected package installer.
package com.olive.app.data.update

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.core.content.FileProvider
import com.olive.app.R
import java.io.File
import androidx.core.net.toUri

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
                    "package:$packageName".toUri(),
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

