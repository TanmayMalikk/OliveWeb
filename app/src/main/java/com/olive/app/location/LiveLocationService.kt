package com.olive.app.location

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.gms.location.*
import com.google.firebase.database.DatabaseReference
import com.olive.app.R
import com.olive.app.data.LocationDatabase
import com.olive.app.data.MapLocationPoint
import com.olive.app.security.EncryptionUtils
import org.json.JSONObject
import javax.crypto.SecretKey

// Foreground tracking adapts network uploads while Android continues sampling for renewed movement.
class LiveLocationService:Service() {
    private lateinit var fused:FusedLocationProviderClient
    private var ownerUid="";private var displayName="";private var authorized=emptySet<String>()
    private var expiresAt=0L
    private var sessionKey:SecretKey?=null
    private val expiryHandler=Handler(Looper.getMainLooper())
    private val expireSharing=Runnable { stopSharing() }
    private var rootReference:DatabaseReference?=null
    private var liveReference:DatabaseReference?=null
    private var trailReference:DatabaseReference?=null
    private var lastUploaded:Location?=null;private var lastUploadAt=0L;private var lastMotionAt=0L
    private var lastTrailPruneAt=0L
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
            return START_REDELIVER_INTENT
        }
        val preferences=getSharedPreferences("olive_location_sharing",MODE_PRIVATE)
        ownerUid=intent?.getStringExtra(OWNER_UID) ?: preferences.getString("live_owner_uid",null).orEmpty()
        displayName=intent?.getStringExtra(DISPLAY_NAME) ?: preferences.getString("live_owner_name",null).orEmpty()
        authorized=intent?.getStringArrayExtra(CONTACT_UIDS)?.take(10)?.toSet()
            ?: preferences.getStringSet("live_contacts",emptySet()).orEmpty().take(10).toSet()
        expiresAt=intent?.getLongExtra(EXPIRES_AT,0L)?.takeIf { it>0L } ?: preferences.getLong("live_expires_at",0L)
        sessionKey=ownerUid.takeIf(String::isNotBlank)?.let { LiveLocationKeyStore(this).load(it) }
        if(ownerUid.isBlank() || sessionKey==null || expiresAt<=System.currentTimeMillis()) { stopSharing();return START_NOT_STICKY }
        expiryHandler.removeCallbacks(expireSharing)
        expiryHandler.postDelayed(expireSharing,(expiresAt-System.currentTimeMillis()).coerceAtLeast(1L))
        rootReference=runCatching { LocationDatabase.instance().reference }.getOrNull()
        liveReference=rootReference?.child("liveLocations")?.child(ownerUid)
        trailReference=rootReference?.child("liveLocationTrails")?.child(ownerUid)
        if(intent?.getBooleanExtra(RESET_TRAIL,false)==true) {
            trailReference?.removeValue()
            LiveLocationState.update(null)
        }
        liveReference?.onDisconnect()?.updateChildren(mapOf("active" to false))
        startForeground(NOTIFICATION_ID,notification())
        if(!hasPermission()) { stopSharing();return START_NOT_STICKY }
        val request=LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY,MOVING_SAMPLE_MILLIS)
            .setMinUpdateIntervalMillis(1_000L).setMaxUpdateDelayMillis(3_000L).setMinUpdateDistanceMeters(1f)
            .setWaitForAccurateLocation(true).build()
        fused.removeLocationUpdates(callback)
        try { fused.requestLocationUpdates(request,callback,mainLooper) }
        catch(_:SecurityException) { stopSharing();return START_NOT_STICKY }
        // Redeliver the encrypted session configuration if Android reclaims this process. The service is
        // independent of the activity task, so swiping the app away does not end an active timed share.
        return START_REDELIVER_INTENT
    }

    private fun handleLocation(location:Location) {
        val now=System.currentTimeMillis();val moved=lastUploaded?.distanceTo(location)?.let { it>=3f } ?: true
        if(expiresAt<=now) { stopSharing();return }
        if(moved || location.speed>=.7f) lastMotionAt=now
        if(lastMotionAt==0L) lastMotionAt=now
        val stationary=now-lastMotionAt>=120_000L
        val interval=if(stationary) STATIONARY_UPLOAD_MILLIS else MOVING_UPLOAD_MILLIS
        if(lastUploaded!=null && !moved && now-lastUploadAt<interval) return
        lastUploaded=location;lastUploadAt=now
        val point=MapLocationPoint(ownerUid,displayName,location.latitude,location.longitude,now,true,location.accuracy)
        val key=sessionKey ?: return
        val locationPayload=JSONObject().put("name",displayName).put("latitude",location.latitude)
            .put("longitude",location.longitude).put("accuracy",location.accuracy.toDouble()).toString()
        val trailPayload=JSONObject().put("latitude",location.latitude).put("longitude",location.longitude).toString()
        rootReference?.updateChildren(mapOf(
            "liveLocations/$ownerUid" to mapOf(
                "ciphertext" to EncryptionUtils.encryptText(locationPayload,key),"updatedAt" to now,
                "active" to true,"authorized" to authorized.associateWith { true }),
            "liveLocationTrails/$ownerUid/$now" to mapOf(
                "ciphertext" to EncryptionUtils.encryptText(trailPayload,key),"updatedAt" to now)))
            ?.addOnSuccessListener { LiveLocationState.update(point) }
        pruneExpiredTrail(now)
    }

    private fun pruneExpiredTrail(now:Long) {
        if(now-lastTrailPruneAt<TRAIL_PRUNE_INTERVAL_MILLIS) return
        lastTrailPruneAt=now
        trailReference?.orderByKey()?.endAt((now-TRAIL_WINDOW_MILLIS).toString())?.get()
            ?.addOnSuccessListener { snapshot ->
                val removals=snapshot.children.associate { it.key.orEmpty() to null }
                if(removals.isNotEmpty()) trailReference?.updateChildren(removals)
            }
    }

    private fun stopSharing() {
        expiryHandler.removeCallbacks(expireSharing)
        if(::fused.isInitialized) fused.removeLocationUpdates(callback)
        if(ownerUid.isNotBlank()) {
            val reference=liveReference
                ?: runCatching { LocationDatabase.instance().reference.child("liveLocations").child(ownerUid) }.getOrNull()
            reference?.onDisconnect()?.cancel()
            reference?.updateChildren(mapOf("active" to false))
            LiveLocationKeyStore(this).remove(ownerUid)
        }
        getSharedPreferences("olive_location_sharing",MODE_PRIVATE).edit {
            putBoolean("live_active",false).remove("live_owner_uid").remove("live_owner_name")
                .remove("live_contacts").remove("live_expires_at")
        }
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
        return NotificationCompat.Builder(this,CHANNEL_ID).setSmallIcon(R.drawable.ic_app_info_blank)
            .setContentTitle(getString(R.string.location_notification_title)).setContentText(getString(R.string.location_notification_text))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val OWNER_UID="owner_uid";const val DISPLAY_NAME="display_name";const val CONTACT_UIDS="contact_uids"
        const val EXPIRES_AT="expires_at";const val STOP="stop";const val PING="ping"
        const val RESET_TRAIL="reset_trail"
        private const val CHANNEL_ID="olive_live_location_v3";private const val NOTIFICATION_ID=4102
        private const val MOVING_SAMPLE_MILLIS=2_000L
        private const val MOVING_UPLOAD_MILLIS=2_000L
        private const val STATIONARY_UPLOAD_MILLIS=180_000L
        private const val TRAIL_WINDOW_MILLIS=30*60_000L
        private const val TRAIL_PRUNE_INTERVAL_MILLIS=60_000L
    }
}
