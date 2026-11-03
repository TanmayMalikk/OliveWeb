// Users: publishes a live location feed that Firebase allows administrator accounts to read.
package com.olive.app.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.database.DatabaseReference
import com.olive.app.R
import com.olive.app.data.LocationDatabase

/** Runs the user location feed even when the cover app is not on screen. */
class AdminLocationService : Service() {
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var ownerUid = ""
    private var displayName = ""
    private var reference: DatabaseReference? = null
    private var lastUploaded: Location? = null
    private var lastUploadAt = 0L
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::publish)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Restores the signed-in user and begins high-accuracy updates for the admin map.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        ownerUid = intent?.getStringExtra(EXTRA_UID)
            ?: preferences.getString(KEY_UID, null).orEmpty()
        displayName = intent?.getStringExtra(EXTRA_NAME)
            ?: preferences.getString(KEY_NAME, null).orEmpty()
        if (ownerUid.isBlank() || !hasLocationPermission()) {
            stopTracking()
            return START_NOT_STICKY
        }
        preferences.edit {
            putBoolean(KEY_ACTIVE, true)
            putString(KEY_UID, ownerUid)
            putString(KEY_NAME, displayName)
        }
        reference = runCatching {
            LocationDatabase.instance().reference.child(ADMIN_LOCATION_PATH).child(ownerUid)
        }.getOrNull()
        reference?.onDisconnect()?.updateChildren(mapOf("active" to false))
        startForeground(NOTIFICATION_ID, notification())
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, SAMPLE_MILLIS)
            .setMinUpdateIntervalMillis(MIN_SAMPLE_MILLIS)
            .setMaxUpdateDelayMillis(MAX_DELAY_MILLIS)
            .setMinUpdateDistanceMeters(1f)
            .setWaitForAccurateLocation(true)
            .build()
        fused.removeLocationUpdates(callback)
        try {
            fused.requestLocationUpdates(request, callback, mainLooper)
        } catch (_: SecurityException) { // Android can revoke location access while this service is starting.
            stopTracking()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    // Publishes movement immediately and refreshes stationary locations once per minute.
    private fun publish(location: Location) {
        val now = System.currentTimeMillis()
        val moved = lastUploaded?.distanceTo(location)?.let { it >= MIN_DISTANCE_METRES } ?: true
        if (!moved && now - lastUploadAt < STATIONARY_UPLOAD_MILLIS) return
        lastUploaded = location
        lastUploadAt = now
        reference?.setValue(
            mapOf(
                "name" to displayName.ifBlank { ownerUid },
                "latitude" to location.latitude,
                "longitude" to location.longitude,
                "accuracy" to location.accuracy.toDouble(),
                "updatedAt" to now,
                "active" to true,
            )
        )
    }

    // Marks the last point offline, removes GPS callbacks, and clears the saved session.
    private fun stopTracking() {
        fused.removeLocationUpdates(callback)
        reference?.onDisconnect()?.cancel()
        reference?.updateChildren(mapOf("active" to false, "updatedAt" to System.currentTimeMillis()))
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit {
            putBoolean(KEY_ACTIVE, false)
            remove(KEY_UID)
            remove(KEY_NAME)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        super.onDestroy()
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.location_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_app_info_blank)
        .setContentTitle(getString(R.string.location_notification_title))
        .setContentText(getString(R.string.location_notification_text))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    companion object {
        private const val PREFERENCES = "olive_admin_location"
        private const val KEY_ACTIVE = "active"
        private const val KEY_UID = "uid"
        private const val KEY_NAME = "name"
        private const val EXTRA_UID = "uid"
        private const val EXTRA_NAME = "name"
        private const val ACTION_STOP = "com.olive.app.location.STOP_ADMIN_LOCATION"
        private const val ADMIN_LOCATION_PATH = "adminLiveLocations"
        private const val CHANNEL_ID = "olive_admin_location_v1"
        private const val NOTIFICATION_ID = 4103
        private const val SAMPLE_MILLIS = 2_000L
        private const val MIN_SAMPLE_MILLIS = 1_000L
        private const val MAX_DELAY_MILLIS = 3_000L
        private const val MIN_DISTANCE_METRES = 3f
        private const val STATIONARY_UPLOAD_MILLIS = 60_000L

        // Starts or refreshes tracking for the currently signed-in user account.
        fun start(context: Context, uid: String, name: String) {
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit {
                putBoolean(KEY_ACTIVE, true)
                putString(KEY_UID, uid)
                putString(KEY_NAME, name)
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, AdminLocationService::class.java)
                    .putExtra(EXTRA_UID, uid)
                    .putExtra(EXTRA_NAME, name),
            )
        }

        // Stops the admin location feed when the user signs out of this phone.
        fun stop(context: Context) {
            context.startService(
                Intent(context, AdminLocationService::class.java).setAction(ACTION_STOP)
            )
        }

        // Returns true when this phone has a user location feed to restore.
        fun active(context: Context) = context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getBoolean(KEY_ACTIVE, false)
    }
}
