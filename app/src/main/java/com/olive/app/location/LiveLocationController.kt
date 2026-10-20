package com.olive.app.location

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.olive.app.data.MapLocationPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object LiveLocationState {
    private val mutable=MutableStateFlow<MapLocationPoint?>(null)
    val current=mutable.asStateFlow()
    internal fun update(value:MapLocationPoint?) { mutable.value=value }
}

/** Persists and controls a live-location session independently from the tracking service lifecycle. */
internal object LiveLocationController {
    private const val PREFS="olive_location_sharing"

    fun active(context:Context):Boolean {
        val preferences=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        return preferences.getBoolean("live_active",false) && expiresAt(context)>System.currentTimeMillis()
    }
    fun selected(context:Context)=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        .getStringSet("live_contacts",emptySet()).orEmpty()
    fun expiresAt(context:Context)=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        .getLong("live_expires_at",0L)

    fun start(context:Context,uid:String,name:String,contactUids:Set<String>,expiresAt:Long) {
        require(contactUids.size<=10)
        require(expiresAt>System.currentTimeMillis())
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putBoolean("live_active",true)
            .putString("live_owner_uid",uid).putString("live_owner_name",name).putStringSet("live_contacts",contactUids)
            .putLong("live_expires_at",expiresAt).apply()
        val intent=Intent(context,LiveLocationService::class.java).putExtra(LiveLocationService.OWNER_UID,uid)
            .putExtra(LiveLocationService.DISPLAY_NAME,name).putExtra(LiveLocationService.CONTACT_UIDS,contactUids.toTypedArray())
            .putExtra(LiveLocationService.EXPIRES_AT,expiresAt)
        ContextCompat.startForegroundService(context,intent)
    }

    fun stop(context:Context) {
        val preferences=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val ownerUid=preferences.getString("live_owner_uid",null)
        val contacts=preferences.getStringSet("live_contacts",emptySet()).orEmpty()
        context.startService(Intent(context,LiveLocationService::class.java).setAction(LiveLocationService.STOP)
            .putExtra(LiveLocationService.OWNER_UID,ownerUid).putExtra(LiveLocationService.CONTACT_UIDS,contacts.toTypedArray()))
        preferences.edit().putBoolean("live_active",false).remove("live_owner_uid").remove("live_owner_name")
            .remove("live_contacts").remove("live_expires_at").apply()
    }
}
