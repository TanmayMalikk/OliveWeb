// Users and approved supporters: reads and writes live location data for approved viewers.
package com.olive.app.data

import android.content.Context
import androidx.core.content.edit
import com.google.firebase.FirebaseApp
import com.google.firebase.database.*
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.KeyManager
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import javax.crypto.SecretKey

data class LocationTrailPoint(val latitude: Double, val longitude: Double, val updatedAt: Long)

data class MapLocationPoint(
    val uid: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val updatedAt: Long,
    val live: Boolean,
    val accuracy: Float? = null,
    val lastKnown: Boolean = false,
    val trail: List<LocationTrailPoint> = emptyList()
)

internal object LocationDatabase {
    // Returns the instance used by location sharing.
    fun instance(): FirebaseDatabase {
        val configured = com.olive.app.BuildConfig.REALTIME_DATABASE_URL
        if (configured.isNotBlank()) return FirebaseDatabase.getInstance(configured)
        val project =
            FirebaseApp.getInstance().options.projectId ?: error("Firebase project is unavailable")
        return FirebaseDatabase.getInstance("https://$project-default-rtdb.firebaseio.com")
    }
}

internal class SavedLocationUnavailableException :
    IllegalStateException("Saved location is unavailable")

internal interface LocationSharingPreferences {
    // Returns whether saved-address sharing is enabled for this account.
    fun staticSharingEnabled(uid: String): Boolean
    // Turns saved-address sharing on or off for one trusted contact.
    fun setStaticSharingEnabled(uid: String, enabled: Boolean)
}

private class AndroidLocationSharingPreferences(context: Context) : LocationSharingPreferences {
    private val values =
        context.getSharedPreferences("olive_location_sharing", Context.MODE_PRIVATE)

    // Location sharing: Reports whether static sharing enabled. It reads the value saved on this device.
    override fun staticSharingEnabled(uid: String) = values.getBoolean("static_$uid", false)
    // Saves whether trusted contacts may see this account's saved address.
    override fun setStaticSharingEnabled(uid: String, enabled: Boolean) {
        values.edit { putBoolean("static_$uid", enabled) }
    }
}

// Location sharing: Defines address lookup for saved locations. Implementations return map coordinates for a delivery address.
internal fun interface AddressCoordinateResolver {
    // Reads valid latitude and longitude values from this location record.
    suspend fun coordinates(address: DeliveryAddress): Pair<Double, Double>?
}

private class AndroidAddressCoordinateResolver(private val context: Context) :
    AddressCoordinateResolver {
    // Reads valid latitude and longitude values from a route point.
    override suspend fun coordinates(address: DeliveryAddress) = GeoapifyClient.coordinates(address)
        ?: DeviceLocation.coordinatesForAddress(context, address)
}

private class StaticLocationSharingDataSource(
    private val preferences: LocationSharingPreferences,
    private val resolver: AddressCoordinateResolver,
    private val firestore: FirebaseFirestore,
) {
    // Returns true when live sharing is switched on and has not expired.
    fun enabled(uid: String) = preferences.staticSharingEnabled(uid)

    // Saves one live-location point to the protected Firebase record.
    suspend fun update(
        identity: AccountIdentity,
        profile: UserProfile,
        friends: List<Friend>,
        enabled: Boolean
    ) {
        val collection =
            firestore.collection("sharedLocations").document(identity.uid).collection("recipients")
        val old = collection.get().await()
        val batch = firestore.batch()
        // Add each changed location field to the Firebase update.
        old.documents.forEach { batch.delete(it.reference) }
        // Only do this when enabled.
        if (enabled) {
            val coordinates =
                resolver.coordinates(profile.address) ?: throw SavedLocationUnavailableException()
            friends.forEach { friend ->
                val key = EncryptionUtils.generateSymmetricKey()
                val publicKey = friendPublicKey(firestore, friend)
                val payload =
                    JSONObject().put("name", profile.fullName).put("latitude", coordinates.first)
                        .put("longitude", coordinates.second)
                        .put("label", profile.address.generalLocation).toString()
                batch.set(
                    collection.document(friend.uid), mapOf(
                        "ownerUid" to identity.uid,
                        "recipientUid" to friend.uid,
                        "friendshipId" to friend.friendshipId,
                        "ciphertext" to EncryptionUtils.encryptText(payload, key),
                        "wrappedKey" to KeyManager.wrapKey(key, publicKey),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
        }
        batch.commit().await()
        preferences.setStaticSharingEnabled(identity.uid, enabled)
    }

    // Listens for live-location changes and removes the listener when closed.
    fun observe(
        currentUid: String,
        friends: List<Friend>,
        onUpdate: (List<MapLocationPoint>) -> Unit
    ): ChatSubscription {
        val points = mutableMapOf<String, MapLocationPoint>()
        // Sends the latest sorted locations to the map whenever one friend changes.
        fun publish() = onUpdate(points.values.sortedBy { it.name })
        val listeners = friends.map { friend ->
            firestore.collection("sharedLocations").document(friend.uid).collection("recipients")
                .document(currentUid)
                .addSnapshotListener { row, _ ->
                    // Update the map only after Firebase has returned a location row.
                    if (row?.exists() == true) {
                        val decoded = runCatching {
                            val key =
                                KeyManager.unwrapKey(checkNotNull(row.getString("wrappedKey")))
                            JSONObject(
                                EncryptionUtils.decryptText(
                                    checkNotNull(row.getString("ciphertext")),
                                    key
                                )
                            )
                        }.getOrNull()
                        if (decoded != null) points[friend.uid] = MapLocationPoint(
                            friend.uid,
                            decoded.optString("name", friend.username),
                            decoded.getDouble("latitude"),
                            decoded.getDouble("longitude"),
                            row.getTimestamp("updatedAt")?.toDate()?.time ?: 0L,
                            false
                        )
                        else points.remove(friend.uid)
                    } else points.remove(friend.uid)
                    publish()
                }
        }
        return ChatSubscription { listeners.forEach { it.remove() } }
    }
}

private class LiveLocationSharingDataSource(
    private val firestore: FirebaseFirestore,
    private val databaseProvider: () -> FirebaseDatabase,
) {
    // Replaces the trusted contacts allowed to receive live location.
    suspend fun setRecipients(ownerUid: String, friends: List<Friend>): SecretKey? {
        require(friends.size <= 10)
        val collection =
            firestore.collection("liveLocationShares").document(ownerUid).collection("recipients")
        val old = collection.get().await()
        val batch = firestore.batch()
        old.documents.forEach { batch.delete(it.reference) }
        if (friends.isEmpty()) {
            batch.commit().await()
            return null
        }
        val key = EncryptionUtils.generateSymmetricKey()
        friends.forEach { friend ->
            batch.set(
                collection.document(friend.uid), mapOf(
                    "ownerUid" to ownerUid,
                    "recipientUid" to friend.uid,
                    "friendshipId" to friend.friendshipId,
                    "wrappedKey" to KeyManager.wrapKey(key, friendPublicKey(firestore, friend)),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
        }
        batch.commit().await()
        return key
    }

    // Listens for live-location changes and removes the listener when closed.
    fun observe(
        currentUid: String,
        friends: List<Friend>,
        onUpdate: (List<MapLocationPoint>) -> Unit
    ): ChatSubscription {
        val points = mutableMapOf<String, MapLocationPoint>()
        val database = runCatching(databaseProvider).getOrNull()
            ?: return ChatSubscription { }
        val trails = mutableMapOf<String, MutableMap<String, LocationTrailPoint>>()
        // Sends recent or last-known locations to the map after an update.
        fun publish() =
            onUpdate(points.values.filter { it.lastKnown || System.currentTimeMillis() - it.updatedAt < 5 * 60_000 }
                .sortedBy { it.name })

        // Adds the recent route trail to a live-location update when it is available.
        fun attachTrail(uid: String) {
            val trail = trails[uid].orEmpty().values.sortedBy { it.updatedAt }
            points[uid]?.let {
                points[uid] = it.copy(trail = trail)
                publish()
            }
        }

        val liveListeners = mutableMapOf<String, LiveSubscription>()
        val shareListeners = friends.map { friend ->
            firestore.collection("liveLocationShares").document(friend.uid).collection("recipients")
                .document(currentUid)
                .addSnapshotListener { share, _ ->
                    liveListeners.remove(friend.uid)?.close()
                    points.remove(friend.uid)
                    trails.remove(friend.uid)
                    publish()
                    if (share?.exists() != true) return@addSnapshotListener
                    val key =
                        runCatching { KeyManager.unwrapKey(checkNotNull(share.getString("wrappedKey"))) }.getOrNull()
                            ?: return@addSnapshotListener
                    val reference = database.reference.child("liveLocations").child(friend.uid)
                    val trailQuery =
                        database.reference.child("liveLocationTrails").child(friend.uid)
                            .orderByChild("updatedAt")
                            .startAt((System.currentTimeMillis() - TRAIL_WINDOW_MILLIS).toDouble())
                    val listener = object : ValueEventListener {
                        // Updates the map when Firebase reports a new live location.
                        override fun onDataChange(snapshot: DataSnapshot) {
                            val updated =
                                snapshot.child("updatedAt").getValue(Long::class.java) ?: 0L
                            val active =
                                snapshot.child("active").getValue(Boolean::class.java) == true
                            val decoded = runCatching {
                                JSONObject(
                                    EncryptionUtils.decryptText(
                                        checkNotNull(
                                            snapshot.child("ciphertext")
                                                .getValue(String::class.java)
                                        ), key
                                    )
                                )
                            }.getOrNull()
                            if (decoded != null && snapshot.child("authorized").child(currentUid)
                                    .getValue(Boolean::class.java) == true
                            )
                                points[friend.uid] = MapLocationPoint(
                                    friend.uid,
                                    decoded.optString("name", friend.username),
                                    decoded.getDouble("latitude"),
                                    decoded.getDouble("longitude"),
                                    updated,
                                    active,
                                    decoded.optDouble("accuracy").takeIf { !it.isNaN() }?.toFloat(),
                                    !active,
                                    trails[friend.uid].orEmpty().values.sortedBy { it.updatedAt })
                            else points.remove(friend.uid)
                            publish()
                        }

                        // Reports a Firebase listener error and stops waiting for location updates.
                        override fun onCancelled(error: DatabaseError) {
                            points.remove(friend.uid)
                            publish()
                        }
                    }
                    val trailListener = object : ChildEventListener {
                        // Adds a new route point from Firebase to the map trail.
                        override fun onChildAdded(
                            snapshot: DataSnapshot,
                            previousChildName: String?
                        ) = update(snapshot)

                        // Replaces a route point after Firebase reports an edit.
                        override fun onChildChanged(
                            snapshot: DataSnapshot,
                            previousChildName: String?
                        ) = update(snapshot)

                        // Removes a route point that was deleted from Firebase.
                        override fun onChildRemoved(snapshot: DataSnapshot) {
                            trails[friend.uid]?.remove(snapshot.key)
                            attachTrail(friend.uid)
                        }

                        // Reorders a route point when Firebase changes its position.
                        override fun onChildMoved(
                            snapshot: DataSnapshot,
                            previousChildName: String?
                        ) = Unit

                        // Reports a Firebase listener error and stops waiting for location updates.
                        override fun onCancelled(error: DatabaseError) = Unit
                        // Rebuilds the visible route trail whenever Firebase changes a point.
                        private fun update(snapshot: DataSnapshot) {
                            val updated =
                                snapshot.child("updatedAt").getValue(Long::class.java) ?: return
                            if (updated < System.currentTimeMillis() - TRAIL_WINDOW_MILLIS) return
                            val decoded = runCatching {
                                JSONObject(
                                    EncryptionUtils.decryptText(
                                        checkNotNull(
                                            snapshot.child("ciphertext")
                                                .getValue(String::class.java)
                                        ), key
                                    )
                                )
                            }.getOrNull() ?: return
                            trails.getOrPut(friend.uid) { mutableMapOf() }[snapshot.key.orEmpty()] =
                                LocationTrailPoint(
                                    decoded.getDouble("latitude"),
                                    decoded.getDouble("longitude"),
                                    updated
                                )
                            attachTrail(friend.uid)
                        }
                    }
                    liveListeners[friend.uid] =
                        LiveSubscription(reference, listener, trailQuery, trailListener)
                    reference.addValueEventListener(listener)
                    trailQuery.addChildEventListener(
                        trailListener
                    )
                }
        }
        return ChatSubscription {
            shareListeners.forEach { it.remove() }
            liveListeners.values.forEach(LiveSubscription::close)
        }
    }

    private data class LiveSubscription(
        val location: DatabaseReference, val locationListener: ValueEventListener,
        val trail: Query, val trailListener: ChildEventListener
    ) {
        // Location sharing: Closes the active feature. It listens for new data and stops listening when the screen closes.
        fun close() {
            location.removeEventListener(locationListener)
            trail.removeEventListener(trailListener)
        }
    }

    private companion object {
        const val TRAIL_WINDOW_MILLIS = 30 * 60_000L
    }
}

internal interface LocationSharingDataSource {
    // Location sharing: Reports whether static sharing enabled.
    fun staticSharingEnabled(uid: String): Boolean
    // Publishes whether this account shares its saved address.
    suspend fun setStaticSharing(
        identity: AccountIdentity,
        profile: UserProfile,
        friends: List<Friend>,
        enabled: Boolean
    )

    // Updates which trusted contacts may read the current live location.
    suspend fun setLiveRecipients(ownerUid: String, friends: List<Friend>): SecretKey?
    // Watches static contacts for changes.
    fun observeStaticContacts(
        currentUid: String,
        friends: List<Friend>,
        onUpdate: (List<MapLocationPoint>) -> Unit
    ): ChatSubscription

    // Watches live friends for changes.
    fun observeLiveFriends(
        currentUid: String,
        friends: List<Friend>,
        onUpdate: (List<MapLocationPoint>) -> Unit
    ): ChatSubscription
}

/** Small facade over independent static-pin and live-location stores. */
internal class LocationSharingRepository private constructor(
    private val staticLocations: StaticLocationSharingDataSource,
    private val liveLocations: LiveLocationSharingDataSource,
) : LocationSharingDataSource {
    constructor(
        context: Context,
        firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    ) : this(
        StaticLocationSharingDataSource(
            AndroidLocationSharingPreferences(context.applicationContext),
            AndroidAddressCoordinateResolver(context.applicationContext), firestore
        ),
        LiveLocationSharingDataSource(firestore, LocationDatabase::instance),
    )

    // Location sharing: Reports whether static sharing enabled.
    override fun staticSharingEnabled(uid: String) = staticLocations.enabled(uid)
    // Publishes whether this account shares its saved address.
    override suspend fun setStaticSharing(
        identity: AccountIdentity,
        profile: UserProfile,
        friends: List<Friend>,
        enabled: Boolean
    ) =
        staticLocations.update(identity, profile, friends, enabled)

    // Updates which trusted contacts may read the current live location.
    override suspend fun setLiveRecipients(ownerUid: String, friends: List<Friend>) =
        liveLocations.setRecipients(ownerUid, friends)

    // Watches static contacts for changes.
    override fun observeStaticContacts(
        currentUid: String,
        friends: List<Friend>,
        onUpdate: (List<MapLocationPoint>) -> Unit
    ) =
        staticLocations.observe(currentUid, friends, onUpdate)

    // Watches live friends for changes.
    override fun observeLiveFriends(
        currentUid: String,
        friends: List<Friend>,
        onUpdate: (List<MapLocationPoint>) -> Unit
    ) =
        liveLocations.observe(currentUid, friends, onUpdate)
}

// Keeps the saved friend public key name in one place for location sharing.
private suspend fun friendPublicKey(firestore: FirebaseFirestore, friend: Friend) =
    firestore.collection("messageDirectory").document(friend.username.lowercase()).get().await()
        .let { row ->
            require(row.getString("uid") == friend.uid) { "Contact encryption identity is unavailable" }
            KeyManager.decodePublicKey(row.getString("publicKey").orEmpty().ifBlank {
                error("Contact encryption key is unavailable")
            })
        }
