// Users and approved supporters: reads and writes live location data for approved viewers.
package com.olive.app.data.location

import android.content.Context
import androidx.core.content.edit
import com.google.firebase.FirebaseApp
import com.google.firebase.database.*
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.olive.app.BuildConfig
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.messaging.Friend
import com.olive.app.data.account.UserProfile
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.KeyManager
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.net.URL

data class LocationTrailPoint(val latitude: Double, val longitude: Double, val updatedAt: Long)

data class MapLocationPoint(
    val uid: String, val name: String, val latitude: Double, val longitude: Double,
    val updatedAt: Long, val live: Boolean, val accuracy: Float? = null,
    val lastKnown: Boolean = false, val trail: List<LocationTrailPoint> = emptyList()
)

internal object LocationDatabase {
    /**
     * Returns the instance used by location sharing.
     *
     * @return The resulting [FirebaseDatabase] instance.
     */
    fun instance(): FirebaseDatabase {
        val configured = BuildConfig.REALTIME_DATABASE_URL
        if (configured.isNotBlank()) return FirebaseDatabase.getInstance(configured)
        val project = runCatching { FirebaseApp.getInstance().options.projectId }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "olive-21239"
        return FirebaseDatabase.getInstance("https://$project-default-rtdb.firebaseio.com")
    }
}

internal class SavedLocationUnavailableException :
    IllegalStateException("Saved location is unavailable")

internal interface LocationSharingPreferences {
    /**
     * Returns whether saved-address sharing is enabled for this account.
     *
     * @param uid Firebase UID of the target user account.
     * @return `true` if static sharing enabled, or `false` otherwise.
     */
    fun staticSharingEnabled(uid: String): Boolean
    /**
     * Turns saved-address sharing on or off for one trusted contact.
     *
     * @param uid Firebase UID of the target user account.
     * @param enabled Whether this feature or control is currently enabled.
     */
    fun setStaticSharingEnabled(uid: String, enabled: Boolean)
}

private class AndroidLocationSharingPreferences(context: Context) : LocationSharingPreferences {
    private val values =
        context.getSharedPreferences("olive_location_sharing", Context.MODE_PRIVATE)

    /**
     * Location sharing: Reports whether static sharing enabled. It reads the value saved on this device.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting static sharing enabled.
     */
    override fun staticSharingEnabled(uid: String) = values.getBoolean("static_$uid", false)
    /**
     * Saves whether trusted contacts may see this account's saved address.
     *
     * @param uid Firebase UID of the target user account.
     * @param enabled Whether this feature or control is currently enabled.
     */
    override fun setStaticSharingEnabled(uid: String, enabled: Boolean) {
        values.edit { putBoolean("static_$uid", enabled) }
    }
}

// Location sharing: Defines address lookup for saved locations. Implementations return map coordinates for a delivery address.
internal fun interface AddressCoordinateResolver {
    /**
     * Reads valid latitude and longitude values from this location record.
     *
     * @param address Address supplied to [coordinates].
     * @return The resulting [Pair], or `null` if unavailable.
     */
    suspend fun coordinates(address: DeliveryAddress): Pair<Double, Double>?
}

internal class AndroidAddressCoordinateResolver(private val context: Context) :
    AddressCoordinateResolver {
    /**
     * Reads valid latitude and longitude values from a route point.
     *
     * @param address Address supplied to [coordinates].
     * @return The resulting coordinates.
     */
    override suspend fun coordinates(address: DeliveryAddress) = GeoapifyClient.coordinates(address)
        ?: DeviceLocation.coordinatesForAddress(context, address)
}

private class StaticLocationSharingDataSource(
    private val preferences: LocationSharingPreferences,
    private val resolver: AddressCoordinateResolver,
    private val firestore: FirebaseFirestore,
) {
    /**
     * Returns true when live sharing is switched on and has not expired.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting enabled.
     */
    fun enabled(uid: String) = preferences.staticSharingEnabled(uid)

    /**
     * Saves one live-location point to the protected Firebase record.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @param profile Account profile details.
     * @param friends List of friend records.
     * @param enabled Whether this feature or control is currently enabled.
     */
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

    /**
     * Listens for live-location changes and removes the listener when closed.
     *
     * @param currentUid Current user ID supplied to [observe].
     * @param friends List of friend records.
     * @param onUpdate Callback invoked when updated data is available.
     * @return Subscription or registration handle to close when observation is no longer needed.
     */
    fun observe(
        currentUid: String,
        friends: List<Friend>,
        onUpdate: (List<MapLocationPoint>) -> Unit
    ): ChatSubscription {
        val points = mutableMapOf<String, MapLocationPoint>()
        /**
         * Sends the latest sorted locations to the map whenever one friend changes.
         *
         * @return The resulting publish.
         */
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
    /**
     * Replaces the trusted contacts allowed to receive live location.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @param friends List of friend records.
     * @return The resulting [SecretKey], or `null` if unavailable.
     */
    suspend fun setRecipients(ownerUid: String, friends: List<Friend>): SecretKey? {
        require(friends.none { it.uid == ownerUid })
        val recipients = friends.distinctBy { it.uid }
        val collection = firestore.collection("liveLocationShares").document(ownerUid).collection("recipients")
        val old = collection.get().await()
        val key = if (recipients.isEmpty()) null else EncryptionUtils.generateSymmetricKey()
        // Resolve every selected friend's key before making any changes.
        val wrapped = if (key == null) emptyList() else recipients.chunked(10).flatMap { group ->
            coroutineScope {
                group.map { friend ->
                    async { friend to KeyManager.wrapKey(key, friendPublicKey(firestore, friend)) }
                }.awaitAll()
            }
        }
        val selectedIds = recipients.mapTo(mutableSetOf()) { it.uid }
        old.documents.filterNot { it.id in selectedIds }.chunked(500).forEach { group ->
            val batch = firestore.batch()
            group.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
        // Rules can check the friendship three times per write. Six writes stay below
        // Firestore's 20 access-call limit even when rule reads are not cached.
        wrapped.chunked(6).forEach { group ->
            val batch = firestore.batch()
            group.forEach { (friend, envelope) ->
                batch.set(
                    collection.document(friend.uid), mapOf(
                        "ownerUid" to ownerUid,
                        "recipientUid" to friend.uid,
                        "friendshipId" to friend.friendshipId,
                        "wrappedKey" to envelope,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.commit().await()
        }
        return key
    }

    /**
     * Records active live-location metadata so backend notification triggers know session timing and recipients.
     *
     * @param ownerUid The UID of the account that owns the data.
     * @param recipientUids The UIDs of the accounts receiving access.
     * @param expiresAt The expiration time as Unix epoch milliseconds.
     * @param active Whether the sharing session is active.
     * @param startedAt The session start time as Unix epoch milliseconds.
     */
    suspend fun setSessionState(ownerUid: String, recipientUids: List<String>, expiresAt: Long, active: Boolean, startedAt: Long = System.currentTimeMillis()) {
        val doc = firestore.collection("liveLocationSessions").document(ownerUid)
        val cleanRecipients = recipientUids.filter { it.isNotBlank() && it != ownerUid }.distinct().take(25)
        if (active && cleanRecipients.isNotEmpty() && startedAt > 0L && expiresAt > startedAt) {
            doc.set(
                mapOf(
                    "ownerUid" to ownerUid,
                    "sessionId" to UUID.randomUUID().toString(),
                    "recipientUids" to cleanRecipients,
                    "active" to true,
                    "startedAt" to startedAt,
                    "expiresAt" to expiresAt,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
        } else {
            runCatching { doc.delete().await() }
        }
    }

    /**
     * Listens for live-location changes and removes the listener when closed.
     *
     * @param currentUid Current user ID supplied to [observe].
     * @param friends List of friend records.
     * @param onUpdate Callback invoked when updated data is available.
     * @return Subscription or registration handle to close when observation is no longer needed.
     */
    fun observe(
        currentUid: String,
        friends: List<Friend>,
        onUpdate: (List<MapLocationPoint>) -> Unit
    ): ChatSubscription {
        val points = mutableMapOf<String, MapLocationPoint>()
        val database = runCatching(databaseProvider).getOrNull()
            ?: return ChatSubscription { }
        val trails = mutableMapOf<String, MutableMap<String, LocationTrailPoint>>()
        /**
         * Sends recent or last-known locations to the map after an update.
         *
         * @return The resulting publish.
         */
        fun publish() =
            onUpdate(points.values.filter { it.lastKnown || System.currentTimeMillis() - it.updatedAt < 5 * 60_000 }
                .sortedBy { it.name })

        /**
         * Adds the recent route trail to a live-location update when it is available.
         *
         * @param uid Firebase UID of the target user account.
         */
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
                        /**
                         * Updates the map when Firebase reports a new live location.
                         *
                         * @param snapshot Firestore or data snapshot containing the latest state.
                         */
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

                        /**
                         * Reports a Firebase listener error and stops waiting for location updates.
                         *
                         * @param error Throwable or error message describing a failure.
                         */
                        override fun onCancelled(error: DatabaseError) {
                            points.remove(friend.uid)
                            publish()
                        }
                    }
                    val trailListener = object : ChildEventListener {
                        /**
                         * Adds a new route point from Firebase to the map trail.
                         *
                         * @param snapshot Firestore or data snapshot containing the latest state.
                         * @param previousChildName Optional previous child name supplied to [onChildAdded].
                         */
                        override fun onChildAdded(
                            snapshot: DataSnapshot,
                            previousChildName: String?
                        ) = update(snapshot)

                        /**
                         * Replaces a route point after Firebase reports an edit.
                         *
                         * @param snapshot Firestore or data snapshot containing the latest state.
                         * @param previousChildName Optional previous child name supplied to [onChildChanged].
                         */
                        override fun onChildChanged(
                            snapshot: DataSnapshot,
                            previousChildName: String?
                        ) = update(snapshot)

                        /**
                         * Removes a route point that was deleted from Firebase.
                         *
                         * @param snapshot Firestore or data snapshot containing the latest state.
                         */
                        override fun onChildRemoved(snapshot: DataSnapshot) {
                            trails[friend.uid]?.remove(snapshot.key)
                            attachTrail(friend.uid)
                        }

                        /**
                         * Reorders a route point when Firebase changes its position.
                         *
                         * @param snapshot Firestore or data snapshot containing the latest state.
                         * @param previousChildName Optional previous child name supplied to [onChildMoved].
                         */
                        override fun onChildMoved(
                            snapshot: DataSnapshot,
                            previousChildName: String?
                        ) = Unit

                        /**
                         * Reports a Firebase listener error and stops waiting for location updates.
                         *
                         * @param error Throwable or error message describing a failure.
                         */
                        override fun onCancelled(error: DatabaseError) = Unit
                        /**
                         * Rebuilds the visible route trail whenever Firebase changes a point.
                         *
                         * @param snapshot Firestore or data snapshot containing the latest state.
                         */
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
        /**
         * Location sharing: Closes the active feature. It listens for new data and stops listening when the screen closes.
         */
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
    /**
     * Location sharing: Reports whether static sharing enabled.
     *
     * @param uid Firebase UID of the target user account.
     * @return `true` if static sharing enabled, or `false` otherwise.
     */
    fun staticSharingEnabled(uid: String): Boolean
    /**
     * Publishes whether this account shares its saved address.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @param profile Account profile details.
     * @param friends List of friend records.
     * @param enabled Whether this feature or control is currently enabled.
     */
    suspend fun setStaticSharing(
        identity: AccountIdentity,
        profile: UserProfile,
        friends: List<Friend>,
        enabled: Boolean
    )

    /**
     * Updates which trusted contacts may read the current live location.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @param friends List of friend records.
     * @return The resulting [SecretKey], or `null` if unavailable.
     */
    suspend fun setLiveRecipients(ownerUid: String, friends: List<Friend>): SecretKey?
    /**
     * Records or clears active live-location session metadata for notification scheduling.
     *
     * @param ownerUid The UID of the account that owns the data.
     * @param recipientUids The UIDs of the accounts receiving access.
     * @param expiresAt The expiration time as Unix epoch milliseconds.
     * @param active Whether the sharing session is active.
     * @param startedAt The session start time as Unix epoch milliseconds.
     */
    suspend fun setLiveSessionState(ownerUid: String, recipientUids: List<String>, expiresAt: Long, active: Boolean, startedAt: Long = System.currentTimeMillis()) = Unit
    /**
     * Watches static contacts for changes.
     *
     * @param currentUid Current user ID supplied to [observeStaticContacts].
     * @param friends List of friend records.
     * @param onUpdate Callback invoked when updated data is available.
     * @return Subscription or registration handle to close when observation is no longer needed.
     */
    fun observeStaticContacts(
        currentUid: String,
        friends: List<Friend>,
        onUpdate: (List<MapLocationPoint>) -> Unit
    ): ChatSubscription

    /**
     * Watches live friends for changes.
     *
     * @param currentUid Current user ID supplied to [observeLiveFriends].
     * @param friends List of friend records.
     * @param onUpdate Callback invoked when updated data is available.
     * @return Subscription or registration handle to close when observation is no longer needed.
     */
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

    /**
     * Location sharing: Reports whether static sharing enabled.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting static sharing enabled.
     */
    override fun staticSharingEnabled(uid: String) = staticLocations.enabled(uid)
    /**
     * Publishes whether this account shares its saved address.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @param profile Account profile details.
     * @param friends List of friend records.
     * @param enabled Whether this feature or control is currently enabled.
     */
    override suspend fun setStaticSharing(
        identity: AccountIdentity,
        profile: UserProfile,
        friends: List<Friend>,
        enabled: Boolean
    ) =
        staticLocations.update(identity, profile, friends, enabled)

    /**
     * Updates which trusted contacts may read the current live location.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @param friends List of friend records.
     * @return The encryption key for the updated recipients, or null when no key is available.
     */
    override suspend fun setLiveRecipients(ownerUid: String, friends: List<Friend>) =
        liveLocations.setRecipients(ownerUid, friends)

    /**
     * Records or clears live-location session timing and recipient metadata.
     *
     * @param ownerUid The UID of the account that owns the data.
     * @param recipientUids The UIDs of the accounts receiving access.
     * @param expiresAt The expiration time as Unix epoch milliseconds.
     * @param active Whether the sharing session is active.
     * @param startedAt The session start time as Unix epoch milliseconds.
     */
    override suspend fun setLiveSessionState(ownerUid: String, recipientUids: List<String>, expiresAt: Long, active: Boolean, startedAt: Long) =
        liveLocations.setSessionState(ownerUid, recipientUids, expiresAt, active, startedAt)

    /**
     * Watches static contacts for changes.
     *
     * @param currentUid Current user ID supplied to [observeStaticContacts].
     * @param friends List of friend records.
     * @param onUpdate Callback invoked when updated data is available.
     * @return The resulting observe static contacts.
     */
    override fun observeStaticContacts(
        currentUid: String,
        friends: List<Friend>,
        onUpdate: (List<MapLocationPoint>) -> Unit
    ) =
        staticLocations.observe(currentUid, friends, onUpdate)

    /**
     * Watches live friends for changes.
     *
     * @param currentUid Current user ID supplied to [observeLiveFriends].
     * @param friends List of friend records.
     * @param onUpdate Callback invoked when updated data is available.
     * @return The resulting observe live friends.
     */
    override fun observeLiveFriends(
        currentUid: String,
        friends: List<Friend>,
        onUpdate: (List<MapLocationPoint>) -> Unit
    ) =
        liveLocations.observe(currentUid, friends, onUpdate)
}

/**
 * Keeps the saved friend public key name in one place for location sharing.
 *
 * @param firestore Firestore supplied to [friendPublicKey].
 * @param friend Friend relationship record.
 * @return The resulting friend public key.
 */
private suspend fun friendPublicKey(firestore: FirebaseFirestore, friend: Friend) =
    firestore.collection("messageDirectory").document(friend.username.lowercase()).get().await()
        .let { row ->
            require(row.getString("uid") == friend.uid) { "Contact encryption identity is unavailable" }
            KeyManager.decodePublicKey(row.getString("publicKey").orEmpty().ifBlank {
                error("Contact encryption key is unavailable")
            })
        }

// Users and approved supporters: looks up addresses and place suggestions.

data class AddressSuggestion(
    val id: String,
    val primary: String,
    val secondary: String,
    val address: DeliveryAddress
)

// Builds an address-suggestion request for Geoapify.
internal object GeoapifyClient {
    private const val MAX_CACHE_ENTRIES = 64
    private val cache = object : LinkedHashMap<String, List<AddressSuggestion>>(MAX_CACHE_ENTRIES, 0.75f, true) {
        /**
         * Evicts the least-recently-used Geoapify cache entry past [MAX_CACHE_ENTRIES].
         *
         * @param eldest The least recently used cache entry being considered for eviction.
         * @return True when the cache entry should be evicted to enforce the size limit.
         */
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<AddressSuggestion>>): Boolean =
            size > MAX_CACHE_ENTRIES
    }
    private val cacheLock = Any()
    private val sharedHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    val configured get() = BuildConfig.GEOAPIFY_API_KEY.isNotBlank()

    /**
     * Sends the typed address to Geoapify and converts its suggestions.
     *
     * @param query Search query string entered by the user.
     * @param cityOnly Whether to apply city only.
     * @param minLength The minimum required number of characters.
     * @param apiKey The credential used to make the API request.
     * @param openConnection The injected factory used to open an HTTPS connection.
     * @return List of resulting autocomplete items.
     */
    suspend fun autocomplete(
        query: String,
        cityOnly: Boolean,
        minLength: Int = 2,
        apiKey: String = BuildConfig.GEOAPIFY_API_KEY,
        openConnection: ((URL) -> HttpsURLConnection)? = null,
    ): List<AddressSuggestion> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (apiKey.isBlank() || trimmed.length < minLength) return@withContext emptyList()
        val key = "${trimmed.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")}|$cityOnly"
        synchronized(cacheLock) { cache[key] }?.let { return@withContext it }

        val parameters = buildList {
            add("text=${encode(trimmed)}")
            add("format=json")
            add("filter=countrycode:ca")
            add("limit=5")
            if (cityOnly) add("type=city")
            add("apiKey=${encode(apiKey)}")
        }.joinToString("&")
        val url = "https://api.geoapify.com/v1/geocode/autocomplete?$parameters"
        val (succeeded, results) = if (openConnection == null) {
            fetchWithOkHttp(url)
        } else {
            fetchWithConnection(url, openConnection)
        }
        if (succeeded) {
            synchronized(cacheLock) { cache[key] = results }
        }
        results
    }

    /**
     * Executes a Geoapify autocomplete HTTP request asynchronously via the shared OkHttp client.
     *
     * @param url The request URL to open or fetch.
     * @return The request success flag and list of Geoapify suggestions.
     */
    private suspend fun fetchWithOkHttp(url: String): Pair<Boolean, List<AddressSuggestion>> {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        val call = sharedHttpClient.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                /**
                 * Propagates the HTTP failure exception to the suspended caller.
                 *
                 * @param call The OkHttp call that completed or failed.
                 * @param e The network exception reported by OkHttp.
                 */
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                /**
                 * Parses the HTTP response body into address suggestions and resumes the caller.
                 *
                 * @param call The OkHttp call that completed or failed.
                 * @param response The upstream response to read and validate.
                 */
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val outcome = response.use { res ->
                            if (!res.isSuccessful) false to emptyList()
                            else true to parseGeoapifySuggestions(res.body.string())
                        }
                        if (continuation.isActive) continuation.resume(outcome)
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            })
        }
    }

    /**
     * Executes a Geoapify autocomplete request using an injected [HttpsURLConnection] factory.
     *
     * @param url The request URL to open or fetch.
     * @param openConnection The injected factory used to open an HTTPS connection.
     * @return The request success flag and list of Geoapify suggestions.
     */
    private suspend fun fetchWithConnection(
        url: String,
        openConnection: (URL) -> HttpsURLConnection,
    ): Pair<Boolean, List<AddressSuggestion>> {
        currentCoroutineContext().ensureActive()
        val connection = openConnection(URL(url)).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode !in 200..299) false to emptyList()
            else true to parseGeoapifySuggestions(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /** Clears all cached Geoapify address suggestions. */
    internal fun clearCache() {
        synchronized(cacheLock) { cache.clear() }
    }

    /**
     * Converts Geoapify result fields into map coordinates.
     *
     * @param address Address supplied to [coordinates].
     * @return The resulting [Pair], or `null` if unavailable.
     */
    suspend fun coordinates(address: DeliveryAddress): Pair<Double, Double>? {
        address.latitude?.let { lat -> address.longitude?.let { return lat to it } }
        val query = listOf(address.line1, address.city, address.province, address.postalCode, address.country)
            .filter(String::isNotBlank).joinToString(", ")
        return autocomplete(query, false).firstOrNull()?.address?.let { value ->
            value.latitude?.let { lat -> value.longitude?.let { lat to it } }
        }
    }

    /**
     * Converts Geoapify results into the address suggestions shown by the app.
     *
     * @param raw Raw supplied to [parseGeoapifySuggestions].
     * @return List of resulting parse geoapify suggestions items.
     */
    internal fun parseGeoapifySuggestions(raw: String): List<AddressSuggestion> = runCatching {
        val rows = JSONObject(raw).optJSONArray("results") ?: return@runCatching emptyList()
        (0 until rows.length()).mapNotNull { index ->
            suggestion(rows.optJSONObject(index) ?: return@mapNotNull null)
        }
    }.getOrDefault(emptyList())

    /**
     * Gives location sharing the suggestion it needs.
     *
     * @param row Row supplied to [suggestion].
     * @return The resulting [AddressSuggestion], or `null` if unavailable.
     */
    private fun suggestion(row: JSONObject): AddressSuggestion? {
        val lat = row.optDouble("lat", Double.NaN)
        val lon = row.optDouble("lon", Double.NaN)
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val city = listOf(
            "city",
            "town",
            "village",
            "municipality",
            "county"
        ).firstNotNullOfOrNull { key -> row.optString(key).takeIf(String::isNotBlank) }.orEmpty()
        val line1 = row.optString("address_line1").ifBlank { row.optString("street") }
        val address = DeliveryAddress(
            line1 = line1.take(160),
            city = city.take(160),
            province = row.optString("state_code").ifBlank { row.optString("state") }.take(160),
            postalCode = row.optString("postcode").take(160),
            country = row.optString("country").ifBlank { "Canada" }.take(160),
            latitude = lat,
            longitude = lon
        )
        val formatted = row.optString("formatted").ifBlank {
            listOf(line1, city, address.province).filter(String::isNotBlank).joinToString(", ")
        }
        val primary = line1.ifBlank { city.ifBlank { formatted.substringBefore(',') } }
        return AddressSuggestion(
            row.optString("place_id", formatted),
            primary,
            formatted.removePrefix(primary).trimStart(',', ' '),
            address
        )
    }

    /**
     * Formats a Geoapify result as a readable address.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting encode.
     */
    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
}
