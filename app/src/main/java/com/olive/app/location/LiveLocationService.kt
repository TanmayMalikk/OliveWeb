package com.olive.app.location

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.firebase.database.ServerValue
import com.google.firebase.database.DatabaseReference
import com.olive.app.R
import com.olive.app.data.LocationDatabase
import com.olive.app.data.MapLocationPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object LiveLocationState {
    private val mutable=MutableStateFlow<MapLocationPoint?>(null)
    val current=mutable.asStateFlow()
    internal fun update(value:MapLocationPoint?) { mutable.value=value }
}

object LiveLocationController {
    private const val PREFS="olive_location_sharing"
    fun active(context:Context):Boolean {
        val preferences=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        return preferences.getBoolean("live_active",false) && expiresAt(context)>System.currentTimeMillis()
    }
    fun selected(context:Context)=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getStringSet("live_contacts",emptySet()).orEmpty()
    fun expiresAt(context:Context)=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getLong("live_expires_at",0L)
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

// Foreground tracking adapts network uploads while Android continues sampling for renewed movement.
class LiveLocationService:Service() {
    private lateinit var fused:FusedLocationProviderClient
    private var ownerUid="";private var displayName="";private var authorized=emptySet<String>()
    private var expiresAt=0L
    private val expiryHandler=Handler(Looper.getMainLooper())
    private val expireSharing=Runnable { stopSharing() }
    private var liveReference:DatabaseReference?=null
    private var lastUploaded:Location?=null;private var lastUploadAt=0L;private var lastMotionAt=0L
    private val callback=object:LocationCallback() {
        override fun onLocationResult(result:LocationResult) { result.lastLocation?.let(::handleLocation) }
    }

    override fun onCreate() { super.onCreate();fused=LocationServices.getFusedLocationProviderClient(this);createChannel() }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP) {
            intent.getStringExtra(OWNER_UID)?.let { ownerUid=it }
            intent.getStringArrayExtra(CONTACT_UIDS)?.let { authorized=it.toSet() }
            stopSharing();return START_NOT_STICKY
        }
        if(intent?.action==PING) {
            lastMotionAt=System.currentTimeMillis()
            lastUploadAt=0L
            requestImmediateLocation()
            return START_STICKY
        }
        val preferences=getSharedPreferences("olive_location_sharing",Context.MODE_PRIVATE)
        ownerUid=intent?.getStringExtra(OWNER_UID) ?: preferences.getString("live_owner_uid",null).orEmpty()
        displayName=intent?.getStringExtra(DISPLAY_NAME) ?: preferences.getString("live_owner_name",null).orEmpty()
        authorized=intent?.getStringArrayExtra(CONTACT_UIDS)?.take(10)?.toSet()
            ?: preferences.getStringSet("live_contacts",emptySet()).orEmpty().take(10).toSet()
        expiresAt=intent?.getLongExtra(EXPIRES_AT,0L)?.takeIf { it>0L } ?: preferences.getLong("live_expires_at",0L)
        if(ownerUid.isBlank() || expiresAt<=System.currentTimeMillis()) { stopSharing();return START_NOT_STICKY }
        expiryHandler.removeCallbacks(expireSharing)
        expiryHandler.postDelayed(expireSharing,(expiresAt-System.currentTimeMillis()).coerceAtLeast(1L))
        liveReference=runCatching { LocationDatabase.instance().reference.child("liveLocations").child(ownerUid) }.getOrNull()
        liveReference?.onDisconnect()?.updateChildren(mapOf("active" to false))
        startForeground(NOTIFICATION_ID,notification())
        if(!hasPermission()) { stopSharing();return START_NOT_STICKY }
        val request=LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY,5_000L)
            .setMinUpdateIntervalMillis(5_000L).setMaxUpdateDelayMillis(10_000L).setMinUpdateDistanceMeters(3f)
            .setWaitForAccurateLocation(true).build()
        fused.removeLocationUpdates(callback)
        try { fused.requestLocationUpdates(request,callback,mainLooper) }
        catch(_:SecurityException) { stopSharing();return START_NOT_STICKY }
        return START_STICKY
    }

    private fun handleLocation(location:Location) {
        val now=System.currentTimeMillis();val moved=lastUploaded?.distanceTo(location)?.let { it>=10f } ?: true
        if(expiresAt<=now) { stopSharing();return }
        if(moved || location.speed>=.7f) lastMotionAt=now
        if(lastMotionAt==0L) lastMotionAt=now
        val stationary=now-lastMotionAt>=120_000L
        val interval=if(stationary) 180_000L else 10_000L
        if(lastUploaded!=null && !moved && now-lastUploadAt<interval) return
        lastUploaded=location;lastUploadAt=now
        val point=MapLocationPoint(ownerUid,displayName,location.latitude,location.longitude,now,true,location.accuracy)
        LiveLocationState.update(point)
        liveReference?.setValue(mapOf(
            "displayName" to displayName,"latitude" to location.latitude,"longitude" to location.longitude,
            "accuracy" to location.accuracy.toDouble(),"updatedAt" to ServerValue.TIMESTAMP,"active" to true,
            "authorized" to authorized.associateWith { true }))
    }

    private fun stopSharing() {
        expiryHandler.removeCallbacks(expireSharing)
        if(::fused.isInitialized) fused.removeLocationUpdates(callback)
        if(ownerUid.isNotBlank()) {
            val reference=liveReference
                ?: runCatching { LocationDatabase.instance().reference.child("liveLocations").child(ownerUid) }.getOrNull()
            reference?.onDisconnect()?.cancel()
            reference?.updateChildren(mapOf("active" to false))
        }
        getSharedPreferences("olive_location_sharing",Context.MODE_PRIVATE).edit().putBoolean("live_active",false)
            .remove("live_owner_uid").remove("live_owner_name").remove("live_contacts").remove("live_expires_at").apply()
        LiveLocationState.update(null);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    private fun requestImmediateLocation() {
        if(!hasPermission()) return
        val request=LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY,0L)
            .setMaxUpdates(1).setWaitForAccurateLocation(true).build()
        try { fused.requestLocationUpdates(request,callback,mainLooper) }
        catch(_:SecurityException) {}
    }
    override fun onDestroy() {
        expiryHandler.removeCallbacks(expireSharing)
        if(::fused.isInitialized) fused.removeLocationUpdates(callback)
        super.onDestroy()
    }
    private fun hasPermission()=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID,
            getString(R.string.location_notification_channel),NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification():Notification {
        val stop=PendingIntent.getService(this,1,Intent(this,LiveLocationService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this,CHANNEL_ID).setSmallIcon(R.drawable.ic_app_info_blank)
            .setContentTitle(getString(R.string.location_notification_title)).setContentText(getString(R.string.location_notification_text))
            .setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0,getString(R.string.stop_sharing_location),stop).build()
    }

    companion object {
        const val OWNER_UID="owner_uid";const val DISPLAY_NAME="display_name";const val CONTACT_UIDS="contact_uids"
        const val EXPIRES_AT="expires_at";const val STOP="stop";const val PING="ping"
        private const val CHANNEL_ID="olive_live_location";private const val NOTIFICATION_ID=4102
    }
}
