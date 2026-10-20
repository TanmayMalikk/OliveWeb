package com.olive.app.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.database.*
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

data class MapLocationPoint(val uid:String,val name:String,val latitude:Double,val longitude:Double,
    val updatedAt:Long,val live:Boolean,val accuracy:Float?=null,val lastKnown:Boolean=false)

internal object LocationDatabase {
    fun instance():FirebaseDatabase {
        val configured=com.olive.app.BuildConfig.REALTIME_DATABASE_URL
        if(configured.isNotBlank()) return FirebaseDatabase.getInstance(configured)
        val project=FirebaseApp.getInstance().options.projectId ?: error("Firebase project is unavailable")
        return FirebaseDatabase.getInstance("https://$project-default-rtdb.firebaseio.com")
    }
}

// Static safe-contact pins live in Firestore; only User live coordinates use Realtime Database.
internal class LocationSharingRepository(private val context:Context,private val firestore:FirebaseFirestore=FirebaseFirestore.getInstance()) {
    private val preferences=context.getSharedPreferences("olive_location_sharing",Context.MODE_PRIVATE)
    fun staticSharingEnabled(uid:String)=preferences.getBoolean("static_$uid",false)

    suspend fun setStaticSharing(identity:AccountIdentity,profile:UserProfile,friends:List<Friend>,enabled:Boolean) {
        val collection=firestore.collection("sharedLocations").document(identity.uid).collection("recipients")
        val old=collection.get().await()
        val batch=firestore.batch();old.documents.forEach { batch.delete(it.reference) }
        if(enabled) {
            val coordinates=GeoapifyClient.coordinates(profile.address) ?: DeviceLocation.coordinatesForAddress(context,profile.address)
                ?: error(context.getString(com.olive.app.R.string.location_saved_address_unavailable))
            friends.forEach { friend ->
                batch.set(collection.document(friend.uid),mapOf(
                    "ownerUid" to identity.uid,"recipientUid" to friend.uid,"friendshipId" to friend.friendshipId,
                    "contactName" to profile.fullName,"latitude" to coordinates.first,"longitude" to coordinates.second,
                    "label" to profile.address.generalLocation,"updatedAt" to FieldValue.serverTimestamp()))
            }
        }
        batch.commit().await();preferences.edit().putBoolean("static_${identity.uid}",enabled).apply()
    }

    suspend fun setLiveRecipients(ownerUid:String,friends:List<Friend>) {
        require(friends.size<=10)
        val collection=firestore.collection("liveLocationShares").document(ownerUid).collection("recipients")
        val old=collection.get().await();val batch=firestore.batch();old.documents.forEach { batch.delete(it.reference) }
        friends.forEach { friend -> batch.set(collection.document(friend.uid),mapOf(
            "ownerUid" to ownerUid,"recipientUid" to friend.uid,"friendshipId" to friend.friendshipId,
            "createdAt" to FieldValue.serverTimestamp())) }
        batch.commit().await()
    }

    fun observeStaticContacts(currentUid:String,friends:List<Friend>,onUpdate:(List<MapLocationPoint>)->Unit):ChatSubscription {
        val points=mutableMapOf<String,MapLocationPoint>()
        fun publish()=onUpdate(points.values.sortedBy { it.name })
        val listeners=friends.map { friend ->
            firestore.collection("sharedLocations").document(friend.uid).collection("recipients").document(currentUid)
                .addSnapshotListener { row,_ ->
                    if(row?.exists()==true) {
                        val lat=row.getDouble("latitude");val lon=row.getDouble("longitude")
                        if(lat!=null && lon!=null) points[friend.uid]=MapLocationPoint(friend.uid,row.getString("contactName") ?: friend.username,
                            lat,lon,row.getTimestamp("updatedAt")?.toDate()?.time ?: 0L,false)
                    } else points.remove(friend.uid)
                    publish()
                }
        }
        return ChatSubscription { listeners.forEach { it.remove() } }
    }

    fun observeLiveFriends(currentUid:String,friends:List<Friend>,onUpdate:(List<MapLocationPoint>)->Unit):ChatSubscription {
        val points=mutableMapOf<String,MapLocationPoint>();val database=runCatching { LocationDatabase.instance() }.getOrNull()
            ?: return ChatSubscription { }
        fun publish()=onUpdate(points.values.filter { it.lastKnown || System.currentTimeMillis()-it.updatedAt<5*60_000 }
            .sortedBy { it.name })
        val liveListeners=mutableMapOf<String,Pair<DatabaseReference,ValueEventListener>>()
        val shareListeners=friends.map { friend ->
            firestore.collection("liveLocationShares").document(friend.uid).collection("recipients").document(currentUid)
                .addSnapshotListener { share,_ ->
                    liveListeners.remove(friend.uid)?.let { (reference,listener) -> reference.removeEventListener(listener) }
                    points.remove(friend.uid);publish()
                    if(share?.exists()!=true) return@addSnapshotListener
                    val reference=database.reference.child("liveLocations").child(friend.uid)
                    val listener=object:ValueEventListener {
                        override fun onDataChange(snapshot:DataSnapshot) {
                            val lat=snapshot.child("latitude").getValue(Double::class.java);val lon=snapshot.child("longitude").getValue(Double::class.java)
                            val updated=snapshot.child("updatedAt").getValue(Long::class.java) ?: 0L
                            val active=snapshot.child("active").getValue(Boolean::class.java)==true
                            if(lat!=null && lon!=null && snapshot.child("authorized").child(currentUid).getValue(Boolean::class.java)==true)
                                points[friend.uid]=MapLocationPoint(friend.uid,snapshot.child("displayName").getValue(String::class.java) ?: friend.username,
                                    lat,lon,updated,active,snapshot.child("accuracy").getValue(Double::class.java)?.toFloat(),!active)
                            else points.remove(friend.uid)
                            publish()
                        }
                        override fun onCancelled(error:DatabaseError) { points.remove(friend.uid);publish() }
                    }
                    liveListeners[friend.uid]=reference to listener;reference.addValueEventListener(listener)
                }
        }
        return ChatSubscription {
            shareListeners.forEach { it.remove() }
            liveListeners.values.forEach { (reference,listener) -> reference.removeEventListener(listener) }
        }
    }
}
