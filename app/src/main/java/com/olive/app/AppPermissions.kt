package com.olive.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

internal enum class OnboardingPermission { Notifications, Location, Done }
internal fun nextOnboardingPermission(notificationsNeeded:Boolean,notificationsAsked:Boolean,
    locationGranted:Boolean,locationAsked:Boolean)=when {
    notificationsNeeded && !notificationsAsked -> OnboardingPermission.Notifications
    !locationGranted && !locationAsked -> OnboardingPermission.Location
    else -> OnboardingPermission.Done
}

// One owner serializes onboarding prompts and explicit location requests; neither permission is required to use Olive.
internal class AppPermissions(private val activity:ComponentActivity,private val notificationsEnabled:()->Boolean,private val locationEnabled:()->Boolean,
    private val onChanged:()->Unit) {
    private val preferences by lazy { activity.getSharedPreferences("olive_permissions",android.content.Context.MODE_PRIVATE) }
    private var notificationPrompt=false
    private var locationPrompt=false
    private var locationResult:CompletableDeferred<Boolean>?=null
    val awaitingResult get()=notificationPrompt || locationPrompt
    private val notifications=activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationPrompt=false;onChanged()
        afterOnboarding()
    }
    private val location=activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationPrompt=false
        val pending=locationResult;locationResult=null
        pending?.complete(hasLocation())
        onChanged()
    }
    fun hasLocation()=listOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION).any {
        ContextCompat.checkSelfPermission(activity,it)==PackageManager.PERMISSION_GRANTED
    }
    fun afterOnboarding() {
        activity.lifecycleScope.launch {
            activity.lifecycle.withResumed {
                if(awaitingResult) return@withResumed
                val needsNotifications=notificationsEnabled() && Build.VERSION.SDK_INT>=33 &&
                    ContextCompat.checkSelfPermission(activity,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
                when(nextOnboardingPermission(needsNotifications,preferences.getBoolean("notifications_asked",false),
                    hasLocation() || !locationEnabled(),preferences.getBoolean("location_asked",false))) {
                    OnboardingPermission.Notifications -> if(Build.VERSION.SDK_INT>=33) {
                        preferences.edit { putBoolean("notifications_asked",true) }
                        notificationPrompt=true
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    OnboardingPermission.Location -> launchLocation()
                    OnboardingPermission.Done -> Unit
                }
            }
        }
    }
    suspend fun requestLocation():Boolean {
        if(hasLocation()) return true
        locationResult?.let { return it.await() }
        val pending=CompletableDeferred<Boolean>().also { locationResult=it }
        try {
            activity.lifecycle.withResumed { launchLocation() }
            return pending.await()
        } finally {
            if(!locationPrompt && locationResult===pending) locationResult=null
        }
    }
    private fun launchLocation() {
        preferences.edit { putBoolean("location_asked",true) }
        locationPrompt=true
        if(locationResult==null) locationResult=CompletableDeferred()
        try { location.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)) }
        catch(_:IllegalStateException) {
            locationPrompt=false;locationResult?.complete(false);locationResult=null
        }
    }
}
