// Watch fitness users: requests sensor access and opens the Watch6 fitness dashboard.
package com.olive.app.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class WatchFitnessActivity : ComponentActivity() {
    private lateinit var repository: WatchFitnessRepository

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        repository.stop()
        repository.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = WatchFitnessRepository(applicationContext) { state ->
            runOnUiThread { WatchFitnessStateHolder.state.value = state }
        }
        setContent { OliveWatchFitnessApp(WatchFitnessStateHolder.state.value) }
        requestMissingHealthPermissions()
    }

    override fun onResume() {
        super.onResume()
        repository.start()
    }

    override fun onPause() {
        repository.stop()
        super.onPause()
    }

    private fun requestMissingHealthPermissions() {
        val permissions = buildList {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            add(
                if (Build.VERSION.SDK_INT >= 36) {
                    "android.permission.health.READ_HEART_RATE"
                } else {
                    Manifest.permission.BODY_SENSORS
                }
            )
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
    }
}

private object WatchFitnessStateHolder {
    val state = androidx.compose.runtime.mutableStateOf(WatchFitnessState())
}
