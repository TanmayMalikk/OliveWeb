// Users and approved supporters: shares a user location while sharing is enabled.
package com.olive.app.location

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.gms.location.*
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.database.DatabaseReference
import com.google.firebase.firestore.FirebaseFirestore
import com.olive.app.R
import com.olive.app.data.location.LocationDatabase
import com.olive.app.data.location.LocationTrailPoint
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.notification.OliveNotificationManager
import com.olive.app.security.EncryptedVault
import com.olive.app.security.EncryptionUtils
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

// Foreground tracking adapts network uploads while Android continues sampling for renewed movement.
class LiveLocationService : Service() {
    private lateinit var fused: FusedLocationProviderClient
    private var ownerUid = ""
    private var displayName = ""
    private var authorized = emptySet<String>()
    private var expiresAt = 0L
    private var sessionKey: SecretKey? = null
    private val expiryHandler = Handler(Looper.getMainLooper())
    private val expireSharing = Runnable { stopSharing() }
    private var rootReference: DatabaseReference? = null
    private var liveReference: DatabaseReference? = null
    private var trailReference: DatabaseReference? = null
    private var lastUploaded: Location? = null
    private var lastUploadAt = 0L
    private var lastMotionAt = 0L
    private var lastTrailPruneAt = 0L
    private val callback = object : LocationCallback() {
        /**
         * Publishes each fresh GPS point while live sharing is active.
         *
         * @param result Result payload delivered to the callback.
         */
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::handleLocation)
        }
    }

    /**
     * Prepares the foreground notification and GPS client for live sharing.
     */
    override fun onCreate() {
        super.onCreate()
        fused =
            LocationServices.getFusedLocationProviderClient(this)
        OliveNotificationManager.createNotificationChannels(this)
    }

    /**
     * This foreground location service is command-based, so screens do not bind to it.
     *
     * @param intent Android intent containing the action and optional extras for this component.
     * @return The resulting [IBinder], or `null` if unavailable.
     */
    override fun onBind(intent: Intent?): IBinder? = null
    /**
     * Starts or stops GPS updates from the action sent to the foreground service.
     *
     * @param intent Android intent containing the action and optional extras for this component.
     * @param flags Flags supplied to [onStartCommand].
     * @param startId Start ID supplied to [onStartCommand].
     * @return The computed on start command numeric value.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Stop location sharing immediately when the notification action asks for it.
        if (intent?.action == STOP) {
            intent.getStringExtra(OWNER_UID)?.let { ownerUid = it }
            stopSharing()
            return START_NOT_STICKY
        }
        if (intent?.action == PING) {
            lastMotionAt = System.currentTimeMillis()
            lastUploadAt = 0L
            requestImmediateLocation()
            return START_REDELIVER_INTENT
        }
        val preferences = getSharedPreferences("olive_location_sharing", MODE_PRIVATE)
        ownerUid =
            intent?.getStringExtra(OWNER_UID) ?: preferences.getString("live_owner_uid", null)
                .orEmpty()
        displayName =
            intent?.getStringExtra(DISPLAY_NAME) ?: preferences.getString("live_owner_name", null)
                .orEmpty()
        authorized = preferences.getStringSet("live_contacts", emptySet()).orEmpty().toSet()
        expiresAt = intent?.getLongExtra(EXPIRES_AT, 0L)?.takeIf { it > 0L } ?: preferences.getLong(
            "live_expires_at",
            0L
        )
        sessionKey =
            ownerUid.takeIf(String::isNotBlank)?.let { LiveLocationKeyStore(this).load(it) }
        if (ownerUid.isBlank() || sessionKey == null || expiresAt <= System.currentTimeMillis()) {
            stopSharing()
            return START_NOT_STICKY
        }
        expiryHandler.removeCallbacks(expireSharing)
        expiryHandler.postDelayed(
            expireSharing,
            (expiresAt - System.currentTimeMillis()).coerceAtLeast(1L)
        )
        rootReference = runCatching { LocationDatabase.instance().reference }.getOrNull()
        liveReference = rootReference?.child("liveLocations")?.child(ownerUid)
        trailReference = rootReference?.child("liveLocationTrails")?.child(ownerUid)
        if (intent?.getBooleanExtra(RESET_TRAIL, false) == true) {
            trailReference?.removeValue()
            LiveLocationState.update(null)
        }
        liveReference?.onDisconnect()?.updateChildren(mapOf("active" to false))
        startForeground(NOTIFICATION_ID, notification())
        if (!hasPermission()) {
            stopSharing()
            return START_NOT_STICKY
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, MOVING_SAMPLE_MILLIS)
            .setMinUpdateIntervalMillis(1_000L).setMaxUpdateDelayMillis(3_000L)
            .setMinUpdateDistanceMeters(1f)
            .setWaitForAccurateLocation(true).build()
        fused.removeLocationUpdates(callback)
        // Use the fallback when on start command is unavailable.
        try {
            fused.requestLocationUpdates(request, callback, mainLooper)
        } catch (_: SecurityException) {
            stopSharing()
            return START_NOT_STICKY
        }
        // Redeliver the encrypted session configuration if Android reclaims this process. The service is
        // independent of the activity task, so swiping the app away does not end an active timed share.
        return START_REDELIVER_INTENT
    }

    /**
     * Stops expired shares, tracks movement, and uploads a fresh encrypted location when needed.
     *
     * @param location Location supplied to [handleLocation].
     */
    private fun handleLocation(location: Location) {
        val now = System.currentTimeMillis()
        val moved = lastUploaded?.distanceTo(location)?.let { it >= 3f } ?: true
        if (expiresAt <= now) {
            stopSharing()
            return
        }
        if (moved || location.speed >= .7f) lastMotionAt = now
        if (lastMotionAt == 0L) lastMotionAt = now
        val stationary = now - lastMotionAt >= 120_000L
        val interval = if (stationary) STATIONARY_UPLOAD_MILLIS else MOVING_UPLOAD_MILLIS
        if (lastUploaded != null && !moved && now - lastUploadAt < interval) return
        lastUploaded = location
        lastUploadAt = now
        val point = MapLocationPoint(
            ownerUid,
            displayName,
            location.latitude,
            location.longitude,
            now,
            true,
            location.accuracy
        )
        val key = sessionKey ?: return
        val locationPayload =
            JSONObject().put("name", displayName).put("latitude", location.latitude)
                .put("longitude", location.longitude).put("accuracy", location.accuracy.toDouble())
                .toString()
        val trailPayload =
            JSONObject().put("latitude", location.latitude).put("longitude", location.longitude)
                .toString()
        rootReference?.updateChildren(
            mapOf(
                "liveLocations/$ownerUid" to mapOf(
                    "ciphertext" to EncryptionUtils.encryptText(locationPayload, key),
                    "updatedAt" to now,
                    "active" to true,
                    "authorized" to authorized.associateWith { true }),
                "liveLocationTrails/$ownerUid/$now" to mapOf(
                    "ciphertext" to EncryptionUtils.encryptText(trailPayload, key),
                    "updatedAt" to now
                )
            )
        )
            ?.addOnSuccessListener { LiveLocationState.update(point) }
        pruneExpiredTrail(now)
    }

    /**
     * Removes expired trail from location sharing.
     *
     * @param now Current epoch timestamp in milliseconds.
     */
    private fun pruneExpiredTrail(now: Long) {
        if (now - lastTrailPruneAt < TRAIL_PRUNE_INTERVAL_MILLIS) return
        lastTrailPruneAt = now
        trailReference?.orderByKey()?.endAt((now - TRAIL_WINDOW_MILLIS).toString())?.get()
            ?.addOnSuccessListener { snapshot ->
                val removals = snapshot.children.associate { it.key.orEmpty() to null }
                if (removals.isNotEmpty()) trailReference?.updateChildren(removals)
            }
    }

    /**
     * Stops GPS uploads when live sharing ends or expires.
     */
    private fun stopSharing() {
        expiryHandler.removeCallbacks(expireSharing)
        if (::fused.isInitialized) fused.removeLocationUpdates(callback)
        if (ownerUid.isNotBlank()) {
            val reference = liveReference
                ?: runCatching {
                    LocationDatabase.instance().reference.child("liveLocations").child(ownerUid)
                }.getOrNull()
            reference?.onDisconnect()?.cancel()
            reference?.updateChildren(mapOf("active" to false))
            runCatching { FirebaseFirestore.getInstance().collection("liveLocationSessions").document(ownerUid).delete() }
            LiveLocationKeyStore(this).remove(ownerUid)
        }
        getSharedPreferences("olive_location_sharing", MODE_PRIVATE).edit {
            putBoolean("live_active", false).remove("live_owner_uid").remove("live_owner_name")
                .remove("live_contacts").remove("live_expires_at")
        }
        LiveLocationState.update(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Requests immediate location for live location service.
     */
    private fun requestImmediateLocation() {
        if (!hasPermission()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 0L)
            .setMaxUpdates(1).setWaitForAccurateLocation(true).build()
        try {
            fused.requestLocationUpdates(request, callback, mainLooper)
        } catch (_: SecurityException) {
        }
    }

    /**
     * Stops GPS callbacks when Android destroys the live-location service.
     */
    override fun onDestroy() {
        expiryHandler.removeCallbacks(expireSharing)
        if (::fused.isInitialized) fused.removeLocationUpdates(callback)
        super.onDestroy()
    }

    /**
     * Checks whether Android still allows this service to read location.
     *
     * @return `true` if has permission, or `false` otherwise.
     */
    private fun hasPermission() = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * Builds the notification shown while location sharing is running.
     *
     * @return The resulting [Notification] instance.
     */
    private fun notification(): Notification {
        return NotificationCompat.Builder(this, OliveNotificationManager.CHANNEL_LOCATION)
            .setSmallIcon(R.drawable.ic_notification_olive_branch)
            .setContentTitle(getString(R.string.location_notification_title))
            .setContentText(getString(R.string.location_notification_sharing_text))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val OWNER_UID = "owner_uid"
        const val DISPLAY_NAME = "display_name"
        const val EXPIRES_AT = "expires_at"
        const val STOP = "stop"
        const val PING = "ping"
        const val RESET_TRAIL = "reset_trail"
        private const val NOTIFICATION_ID = 4102
        private const val MOVING_SAMPLE_MILLIS = 2_000L
        private const val MOVING_UPLOAD_MILLIS = 2_000L
        private const val STATIONARY_UPLOAD_MILLIS = 180_000L
        private const val TRAIL_WINDOW_MILLIS = 30 * 60_000L
        private const val TRAIL_PRUNE_INTERVAL_MILLIS = 60_000L
    }
}

// Users: publishes a live location feed that Firebase allows administrator accounts to read.

/** Runs the user location feed even when the cover app is not on screen. */
class AdminLocationService : Service() {
    private val fusedDelegate = lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val fused by fusedDelegate
    private var ownerUid = ""
    private var displayName = ""
    private var reference: DatabaseReference? = null
    private var lastUploaded: Location? = null
    private var lastUploadAt = 0L
    private val callback = object : LocationCallback() {
        /**
         * Processes updated device coordinates delivered by the location provider.
         *
         * @param result Result payload delivered to the callback.
         */
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::publish)
        }
    }

    /**
     * Initializes the component and sets up its state or UI when created by Android.
     */
    override fun onCreate() {
        super.onCreate()
        OliveNotificationManager.createNotificationChannels(this)
    }

    /**
     * Returns the binder interface for clients connecting to this service.
     *
     * @param intent Android intent containing the action and optional extras for this component.
     * @return The resulting [IBinder], or `null` if unavailable.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Restores the signed-in user and begins high-accuracy updates for the admin map.
     *
     * @param intent Android intent containing the action and optional extras for this component.
     * @param flags Flags supplied to [onStartCommand].
     * @param startId Start ID supplied to [onStartCommand].
     * @return The computed on start command numeric value.
     */
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
        startForeground(NOTIFICATION_ID, notification())
        preferences.edit {
            putBoolean(KEY_ACTIVE, true)
            putString(KEY_UID, ownerUid)
            putString(KEY_NAME, displayName)
        }
        reference = runCatching {
            LocationDatabase.instance().reference.child(ADMIN_LOCATION_PATH).child(ownerUid)
        }.getOrNull()
        reference?.onDisconnect()?.updateChildren(mapOf("active" to false))
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

    /**
     * Publishes movement immediately and refreshes stationary locations once per minute.
     *
     * @param location Location supplied to [publish].
     */
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

    /**
     * Marks the last point offline, removes GPS callbacks, and clears the saved session.
     */
    private fun stopTracking() {
        if (fusedDelegate.isInitialized()) fused.removeLocationUpdates(callback)
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

    /**
     * Cleans up listeners and allocated resources before the component is destroyed.
     */
    override fun onDestroy() {
        if (fusedDelegate.isInitialized()) fused.removeLocationUpdates(callback)
        super.onDestroy()
    }

    /**
     * Checks whether location permission.
     *
     * @return `true` if has location permission, or `false` otherwise.
     */
    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Returns the notification.
     *
     * @return The resulting [Notification] instance.
     */
    private fun notification(): Notification = NotificationCompat.Builder(this, OliveNotificationManager.CHANNEL_LOCATION)
        .setSmallIcon(R.drawable.ic_notification_olive_branch)
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
        private const val NOTIFICATION_ID = 4103
        private const val SAMPLE_MILLIS = 2_000L
        private const val MIN_SAMPLE_MILLIS = 1_000L
        private const val MAX_DELAY_MILLIS = 3_000L
        private const val MIN_DISTANCE_METRES = 3f
        private const val STATIONARY_UPLOAD_MILLIS = 60_000L

        /**
         * Starts or refreshes tracking for the currently signed-in user account.
         *
         * @param context Android context used to access system services, resources, or storage.
         * @param uid Firebase UID of the target user account.
         * @param name Field, key, or display name.
         */
        fun start(context: Context, uid: String, name: String) {
            // Reject before requesting a foreground service. Stopping an unpromoted request can
            // cause ForegroundServiceDidNotStartInTimeException on newer Android versions.
            if (uid.isBlank() || !canStart(context)) return
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit {
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

        /**
         * Checks whether the caller can start.
         *
         * @param context Android context used to access system services, resources, or storage.
         * @return `true` if can start, or `false` otherwise.
         */
        internal fun canStart(context: Context): Boolean {
            val foreground = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            val background = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            return foreground && background
        }

        /**
         * Stops the admin location feed when the user signs out of this phone.
         *
         * @param context Android context used to access system services, resources, or storage.
         */
        fun stop(context: Context) {
            context.startService(
                Intent(context, AdminLocationService::class.java).setAction(ACTION_STOP)
            )
        }

        /**
         * Returns true when this phone has a user location feed to restore.
         *
         * @param context Android context used to access system services, resources, or storage.
         * @return The resulting active.
         */
        fun active(context: Context) = context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getBoolean(KEY_ACTIVE, false)
    }
}

// Users and approved supporters: stores live location key state.

/** Keeps an active location-session key encrypted by Android Keystore across service restarts. */
internal class LiveLocationKeyStore(context: Context) {
    private val vault = EncryptedVault(context.applicationContext, "live-location")

    /**
     * Saves the live-location encryption key in protected local storage.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @param key Lookup key or cryptographic key.
     */
    fun save(ownerUid: String, key: SecretKey) =
        vault.update { it.put(ownerUid, EncryptionUtils.encodeKeyToString(key)) }

    /**
     * Loads the live-location encryption key from protected local storage.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @return The resulting [SecretKey], or `null` if unavailable.
     */
    fun load(ownerUid: String): SecretKey? =
        vault.read().optString(ownerUid).takeIf(String::isNotBlank)
            ?.let(EncryptionUtils::decodeStringToKey)

    /**
     * Live location key store: Removes the selected item.
     *
     * @param ownerUid Firebase UID of the resource owner.
     */
    fun remove(ownerUid: String) = vault.update { it.remove(ownerUid) }
}

internal object LiveLocationState {
    private val mutable = MutableStateFlow<MapLocationPoint?>(null)
    val current = mutable.asStateFlow()
    /**
     * Updates the visible live-sharing status and expiry time.
     *
     * @param value Input value to store, parse, or format, or null when not provided.
     */
    internal fun update(value: MapLocationPoint?) {
        if (value == null) {
            mutable.value = null
            return
        }
        val cutoff = value.updatedAt - TRAIL_WINDOW_MILLIS
        val prior = mutable.value?.takeIf { it.uid == value.uid }?.trail.orEmpty()
            .filter { it.updatedAt >= cutoff }
        val next = LocationTrailPoint(value.latitude, value.longitude, value.updatedAt)
        mutable.value = value.copy(trail = (prior + next).distinctBy { it.updatedAt }
            .takeLast(MAX_TRAIL_POINTS))
    }

    private const val TRAIL_WINDOW_MILLIS = 30 * 60_000L
    private const val MAX_TRAIL_POINTS = 1_000
}

/** Persists and controls a live-location session independently from the tracking service lifecycle. */
internal object LiveLocationController {
    private const val PREFS = "olive_location_sharing"

    /**
     * Returns true while the current live-location session has not expired.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @return `true` if active, or `false` otherwise.
     */
    fun active(context: Context): Boolean {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return preferences.getBoolean(
            "live_active",
            false
        ) && expiresAt(context) > System.currentTimeMillis()
    }

    /**
     * Reads the saved contact IDs that may receive this user's live location.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @return The resulting selected.
     */
    fun selected(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet("live_contacts", emptySet()).orEmpty()

    /**
     * Calculates when the current live-location sharing session must stop.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @return The resulting expires at.
     */
    fun expiresAt(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getLong("live_expires_at", 0L)

    /**
     * Starts live location for the chosen duration and stores when it must stop.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param uid Firebase UID of the target user account.
     * @param name Field, key, or display name.
     * @param contactUids Collection of contact uids values to process.
     * @param expiresAt Expires at supplied to [start].
     * @param key Lookup key or cryptographic key.
     */
    fun start(
        context: Context,
        uid: String,
        name: String,
        contactUids: Set<String>,
        expiresAt: Long,
        key: SecretKey
    ) {
        require(contactUids.isNotEmpty() && uid !in contactUids)
        require(expiresAt > System.currentTimeMillis())
        LiveLocationKeyStore(context).save(uid, key)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean("live_active", true).putString("live_owner_uid", uid)
                .putString("live_owner_name", name)
                .putStringSet("live_contacts", contactUids).putLong("live_expires_at", expiresAt)
        }
        val intent = Intent(context, LiveLocationService::class.java).putExtra(
            LiveLocationService.OWNER_UID,
            uid
        )
            .putExtra(LiveLocationService.DISPLAY_NAME, name)
            .putExtra(LiveLocationService.EXPIRES_AT, expiresAt)
            .putExtra(LiveLocationService.RESET_TRAIL, true)
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Stops live sharing and clears its saved expiry time.
     *
     * @param context Android context used to access system services, resources, or storage.
     */
    fun stop(context: Context) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ownerUid = preferences.getString("live_owner_uid", null)
        context.startService(
            Intent(context, LiveLocationService::class.java).setAction(LiveLocationService.STOP)
                .putExtra(LiveLocationService.OWNER_UID, ownerUid)
        )
        preferences.edit {
            putBoolean("live_active", false).remove("live_owner_uid").remove("live_owner_name")
                .remove("live_contacts").remove("live_expires_at")
        }
        ownerUid?.let { LiveLocationKeyStore(context).remove(it) }
    }
}
