package com.olive.app.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.database.*
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

data class LocationTrailPoint(val latitude:Double,val longitude:Double,val updatedAt:Long)

data class MapLocationPoint(val uid:String,val name:String,val latitude:Double,val longitude:Double,
    val updatedAt:Long,val live:Boolean,val accuracy:Float?=null,val lastKnown:Boolean=false,
    val trail:List<LocationTrailPoint> = emptyList())

internal object LocationDatabase {
    fun instance():FirebaseDatabase {
        val configured=com.olive.app.BuildConfig.REALTIME_DATABASE_URL
        if(configured.isNotBlank()) return FirebaseDatabase.getInstance(configured)
        val project=FirebaseApp.getInstance().options.projectId ?: error("Firebase project is unavailable")
        return FirebaseDatabase.getInstance("https://$project-default-rtdb.firebaseio.com")
    }
}

internal class SavedLocationUnavailableException:IllegalStateException("Saved location is unavailable")

internal interface LocationSharingPreferences {
    fun staticSharingEnabled(uid:String):Boolean
    fun setStaticSharingEnabled(uid:String,enabled:Boolean)
}

private class AndroidLocationSharingPreferences(context:Context):LocationSharingPreferences {
    private val values=context.getSharedPreferences("olive_location_sharing",Context.MODE_PRIVATE)
    override fun staticSharingEnabled(uid:String)=values.getBoolean("static_$uid",false)
    override fun setStaticSharingEnabled(uid:String,enabled:Boolean) { values.edit().putBoolean("static_$uid",enabled).apply() }
}

internal fun interface AddressCoordinateResolver {
    suspend fun coordinates(address:DeliveryAddress):Pair<Double,Double>?
}

private class AndroidAddressCoordinateResolver(private val context:Context):AddressCoordinateResolver {
    override suspend fun coordinates(address:DeliveryAddress)=GeoapifyClient.coordinates(address)
        ?: DeviceLocation.coordinatesForAddress(context,address)
}

private class StaticLocationSharingDataSource(
    private val preferences:LocationSharingPreferences,
    private val resolver:AddressCoordinateResolver,
    private val firestore:FirebaseFirestore,
) {
    fun enabled(uid:String)=preferences.staticSharingEnabled(uid)

    suspend fun update(identity:AccountIdentity,profile:UserProfile,friends:List<Friend>,enabled:Boolean) {
        val collection=firestore.collection("sharedLocations").document(identity.uid).collection("recipients")
        val old=collection.get().await()
        val batch=firestore.batch();old.documents.forEach { batch.delete(it.reference) }
        if(enabled) {
            val coordinates=resolver.coordinates(profile.address) ?: throw SavedLocationUnavailableException()
            friends.forEach { friend ->
                batch.set(collection.document(friend.uid),mapOf(
                    "ownerUid" to identity.uid,"recipientUid" to friend.uid,"friendshipId" to friend.friendshipId,
                    "contactName" to profile.fullName,"latitude" to coordinates.first,"longitude" to coordinates.second,
                    "label" to profile.address.generalLocation,"updatedAt" to FieldValue.serverTimestamp()))
            }
        }
        batch.commit().await()
        preferences.setStaticSharingEnabled(identity.uid,enabled)
    }

    fun observe(currentUid:String,friends:List<Friend>,onUpdate:(List<MapLocationPoint>)->Unit):ChatSubscription {
        val points=mutableMapOf<String,MapLocationPoint>()
        fun publish()=onUpdate(points.values.sortedBy { it.name })
        val listeners=friends.map { friend ->
            firestore.collection("sharedLocations").document(friend.uid).collection("recipients").document(currentUid)
                .addSnapshotListener { row,_ ->
                    if(row?.exists()==true) {
                        val latitude=row.getDouble("latitude");val longitude=row.getDouble("longitude")
                        if(latitude!=null && longitude!=null) points[friend.uid]=MapLocationPoint(friend.uid,
                            row.getString("contactName") ?: friend.username,latitude,longitude,
                            row.getTimestamp("updatedAt")?.toDate()?.time ?: 0L,false)
                    } else points.remove(friend.uid)
                    publish()
                }
        }
        return ChatSubscription { listeners.forEach { it.remove() } }
    }
}

private class LiveLocationSharingDataSource(
    private val firestore:FirebaseFirestore,
    private val databaseProvider:()->FirebaseDatabase,
) {
    suspend fun setRecipients(ownerUid:String,friends:List<Friend>) {
        require(friends.size<=10)
        val collection=firestore.collection("liveLocationShares").document(ownerUid).collection("recipients")
        val old=collection.get().await();val batch=firestore.batch();old.documents.forEach { batch.delete(it.reference) }
        friends.forEach { friend -> batch.set(collection.document(friend.uid),mapOf(
            "ownerUid" to ownerUid,"recipientUid" to friend.uid,"friendshipId" to friend.friendshipId,
            "createdAt" to FieldValue.serverTimestamp())) }
        batch.commit().await()
    }

    fun observe(currentUid:String,friends:List<Friend>,onUpdate:(List<MapLocationPoint>)->Unit):ChatSubscription {
        val points=mutableMapOf<String,MapLocationPoint>();val database=runCatching(databaseProvider).getOrNull()
            ?: return ChatSubscription { }
        val trails=mutableMapOf<String,MutableMap<String,LocationTrailPoint>>()
        fun publish()=onUpdate(points.values.filter { it.lastKnown || System.currentTimeMillis()-it.updatedAt<5*60_000 }.sortedBy { it.name })
        fun attachTrail(uid:String) {
            val trail=trails[uid].orEmpty().values.sortedBy { it.updatedAt }
            points[uid]?.let { points[uid]=it.copy(trail=trail);publish() }
        }
        val liveListeners=mutableMapOf<String,LiveSubscription>()
        val shareListeners=friends.map { friend ->
            firestore.collection("liveLocationShares").document(friend.uid).collection("recipients").document(currentUid)
                .addSnapshotListener { share,_ ->
                    liveListeners.remove(friend.uid)?.close()
                    points.remove(friend.uid);trails.remove(friend.uid);publish()
                    if(share?.exists()!=true) return@addSnapshotListener
                    val reference=database.reference.child("liveLocations").child(friend.uid)
                    val trailQuery=database.reference.child("liveLocationTrails").child(friend.uid)
                        .orderByChild("updatedAt").startAt((System.currentTimeMillis()-TRAIL_WINDOW_MILLIS).toDouble())
                    val listener=object:ValueEventListener {
                        override fun onDataChange(snapshot:DataSnapshot) {
                            val latitude=snapshot.child("latitude").getValue(Double::class.java)
                            val longitude=snapshot.child("longitude").getValue(Double::class.java)
                            val updated=snapshot.child("updatedAt").getValue(Long::class.java) ?: 0L
                            val active=snapshot.child("active").getValue(Boolean::class.java)==true
                            if(latitude!=null && longitude!=null && snapshot.child("authorized").child(currentUid).getValue(Boolean::class.java)==true)
                                points[friend.uid]=MapLocationPoint(friend.uid,snapshot.child("displayName").getValue(String::class.java)
                                    ?: friend.username,latitude,longitude,updated,active,
                                    snapshot.child("accuracy").getValue(Double::class.java)?.toFloat(),!active,
                                    trails[friend.uid].orEmpty().values.sortedBy { it.updatedAt })
                            else points.remove(friend.uid)
                            publish()
                        }
                        override fun onCancelled(error:DatabaseError) { points.remove(friend.uid);publish() }
                    }
                    val trailListener=object:ChildEventListener {
                        override fun onChildAdded(snapshot:DataSnapshot,previousChildName:String?)=update(snapshot)
                        override fun onChildChanged(snapshot:DataSnapshot,previousChildName:String?)=update(snapshot)
                        override fun onChildRemoved(snapshot:DataSnapshot) {
                            trails[friend.uid]?.remove(snapshot.key);attachTrail(friend.uid)
                        }
                        override fun onChildMoved(snapshot:DataSnapshot,previousChildName:String?)=Unit
                        override fun onCancelled(error:DatabaseError)=Unit
                        private fun update(snapshot:DataSnapshot) {
                            val latitude=snapshot.child("latitude").getValue(Double::class.java) ?: return
                            val longitude=snapshot.child("longitude").getValue(Double::class.java) ?: return
                            val updated=snapshot.child("updatedAt").getValue(Long::class.java) ?: return
                            if(updated<System.currentTimeMillis()-TRAIL_WINDOW_MILLIS) return
                            trails.getOrPut(friend.uid) { mutableMapOf() }[snapshot.key.orEmpty()]=
                                LocationTrailPoint(latitude,longitude,updated)
                            attachTrail(friend.uid)
                        }
                    }
                    liveListeners[friend.uid]=LiveSubscription(reference,listener,trailQuery,trailListener)
                    reference.addValueEventListener(listener);trailQuery.addChildEventListener(trailListener)
                }
        }
        return ChatSubscription {
            shareListeners.forEach { it.remove() }
            liveListeners.values.forEach(LiveSubscription::close)
        }
    }

    private data class LiveSubscription(val location:DatabaseReference,val locationListener:ValueEventListener,
        val trail:Query,val trailListener:ChildEventListener) {
        fun close() { location.removeEventListener(locationListener);trail.removeEventListener(trailListener) }
    }

    private companion object { const val TRAIL_WINDOW_MILLIS=30*60_000L }
}

internal interface LocationSharingDataSource {
    fun staticSharingEnabled(uid:String):Boolean
    suspend fun setStaticSharing(identity:AccountIdentity,profile:UserProfile,friends:List<Friend>,enabled:Boolean)
    suspend fun setLiveRecipients(ownerUid:String,friends:List<Friend>)
    fun observeStaticContacts(currentUid:String,friends:List<Friend>,onUpdate:(List<MapLocationPoint>)->Unit):ChatSubscription
    fun observeLiveFriends(currentUid:String,friends:List<Friend>,onUpdate:(List<MapLocationPoint>)->Unit):ChatSubscription
}

/** Small facade over independent static-pin and live-location stores. */
internal class LocationSharingRepository private constructor(
    private val staticLocations:StaticLocationSharingDataSource,
    private val liveLocations:LiveLocationSharingDataSource,
):LocationSharingDataSource {
    constructor(context:Context,firestore:FirebaseFirestore=FirebaseFirestore.getInstance()):this(
        StaticLocationSharingDataSource(AndroidLocationSharingPreferences(context.applicationContext),
            AndroidAddressCoordinateResolver(context.applicationContext),firestore),
        LiveLocationSharingDataSource(firestore,LocationDatabase::instance),
    )

    override fun staticSharingEnabled(uid:String)=staticLocations.enabled(uid)
    override suspend fun setStaticSharing(identity:AccountIdentity,profile:UserProfile,friends:List<Friend>,enabled:Boolean)=
        staticLocations.update(identity,profile,friends,enabled)
    override suspend fun setLiveRecipients(ownerUid:String,friends:List<Friend>)=liveLocations.setRecipients(ownerUid,friends)
    override fun observeStaticContacts(currentUid:String,friends:List<Friend>,onUpdate:(List<MapLocationPoint>)->Unit)=
        staticLocations.observe(currentUid,friends,onUpdate)
    override fun observeLiveFriends(currentUid:String,friends:List<Friend>,onUpdate:(List<MapLocationPoint>)->Unit)=
        liveLocations.observe(currentUid,friends,onUpdate)
}
