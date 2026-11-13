// Users and approved supporters: manages live location actions.
package com.olive.app.location

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.location.LocationTrailPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.crypto.SecretKey

internal object LiveLocationState {
    private val mutable = MutableStateFlow<MapLocationPoint?>(null)
    val current = mutable.asStateFlow()
    // Updates the visible live-sharing status and expiry time.
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

    // Returns true while the current live-location session has not expired.
    fun active(context: Context): Boolean {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return preferences.getBoolean(
            "live_active",
            false
        ) && expiresAt(context) > System.currentTimeMillis()
    }

    // Reads the saved contact IDs that may receive this user's live location.
    fun selected(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet("live_contacts", emptySet()).orEmpty()

    // Calculates when the current live-location sharing session must stop.
    fun expiresAt(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getLong("live_expires_at", 0L)

    // Starts live location for the chosen duration and stores when it must stop.
    fun start(
        context: Context,
        uid: String,
        name: String,
        contactUids: Set<String>,
        expiresAt: Long,
        key: SecretKey
    ) {
        require(contactUids.size <= 10)
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
            .putExtra(LiveLocationService.CONTACT_UIDS, contactUids.toTypedArray())
            .putExtra(LiveLocationService.EXPIRES_AT, expiresAt)
            .putExtra(LiveLocationService.RESET_TRAIL, true)
        ContextCompat.startForegroundService(context, intent)
    }

    // Stops live sharing and clears its saved expiry time.
    fun stop(context: Context) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ownerUid = preferences.getString("live_owner_uid", null)
        val contacts = preferences.getStringSet("live_contacts", emptySet()).orEmpty()
        context.startService(
            Intent(context, LiveLocationService::class.java).setAction(LiveLocationService.STOP)
                .putExtra(LiveLocationService.OWNER_UID, ownerUid)
                .putExtra(LiveLocationService.CONTACT_UIDS, contacts.toTypedArray())
        )
        preferences.edit {
            putBoolean("live_active", false).remove("live_owner_uid").remove("live_owner_name")
                .remove("live_contacts").remove("live_expires_at")
        }
        ownerUid?.let { LiveLocationKeyStore(context).remove(it) }
    }
}
