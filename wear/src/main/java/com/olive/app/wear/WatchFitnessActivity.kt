// Watch fitness users: requests sensor access and opens the Watch6 fitness dashboard.
package com.olive.app.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel

class WatchFitnessActivity : ComponentActivity() {
    private val fitnessViewModel by viewModels<WatchFitnessViewModel>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        fitnessViewModel.restartSensors()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OliveWatchFitnessApp(fitnessViewModel.state.value) }
        requestMissingHealthPermissions()
    }

    override fun onResume() {
        super.onResume()
        fitnessViewModel.startSensors()
    }

    override fun onPause() {
        fitnessViewModel.stopSensors()
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

// Watch fitness users: retains sensor state when Android recreates the watch activity.
internal class WatchFitnessViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val mainHandler = Handler(Looper.getMainLooper())
    val state = androidx.compose.runtime.mutableStateOf(WatchFitnessState())
    private val repository = WatchFitnessRepository(application.applicationContext) { updated ->
        mainHandler.post { state.value = updated }
    }

    fun startSensors() = repository.start()

    fun stopSensors() = repository.stop()

    fun restartSensors() {
        repository.stop()
        repository.start()
    }

    override fun onCleared() {
        repository.stop()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
