package com.olive.app.data.location

import com.google.firebase.Timestamp
import com.google.firebase.database.*
import com.olive.app.data.account.*
import com.olive.app.data.messaging.Friend
import com.olive.app.security.*
import com.olive.app.support.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class LocationSharingRepositorySdkTest {
    private val transport = FirestoreTestDriver()
    private val repository = LocationSharingRepository(RuntimeEnvironment.getApplication(), transport.db)
    private val friend = Friend("accepted", "friend", "friend_fixture")
    private fun register() { transport.records["messageDirectory/friend_fixture"] = mapOf("uid" to "friend", "publicKey" to java.util.Base64.getEncoder().encodeToString(KeyManager.getOrCreatePublicKey().encoded)) }

    @Test
    fun liveRecipientsRotateKeysRemoveOldGrantsAndRejectUnverifiedIdentities() = runBlocking<Unit> {
        TestAndroidKeyStore.install().use {
            register()
            val path = "liveLocationShares/owner/recipients/friend"
            transport.records["liveLocationShares/owner/recipients/old"] = mapOf("ownerUid" to "owner")
            val key = repository.setLiveRecipients("owner", listOf(friend, friend))!!
            assertFalse(transport.records.containsKey("liveLocationShares/owner/recipients/old"))
            val row = transport.records.getValue(path)
            assertArrayEquals(key.encoded, KeyManager.unwrapKey(row["wrappedKey"] as String).encoded)
            transport.records["messageDirectory/friend_fixture"] = mapOf("uid" to "impostor", "publicKey" to java.util.Base64.getEncoder().encodeToString(KeyManager.getOrCreatePublicKey().encoded))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.setLiveRecipients("owner", listOf(friend)) } }
            assertEquals(row, transport.records[path])
            assertNull(repository.setLiveRecipients("owner", emptyList()))
            assertFalse(transport.records.containsKey(path))
        }
    }

    @Test
    fun savedAddressSharesEncryptedCoordinatesAndDisablingDeletesRecipients() = runBlocking<Unit> {
        TestAndroidKeyStore.install().use {
            register()
            val profile = UserProfile("fixture_owner", "Synthetic Person", "", "", "2000-01-01", "English", "", "Alberta", "", address = DeliveryAddress(latitude = 53.5, longitude = -113.5))
            repository.setStaticSharing(AccountIdentity("owner", "fixture_owner"), profile, listOf(friend), true)
            assertTrue(repository.staticSharingEnabled("owner"))
            val row = transport.records.getValue("sharedLocations/owner/recipients/friend")
            val plain = JSONObject(EncryptionUtils.decryptText(row["ciphertext"] as String, KeyManager.unwrapKey(row["wrappedKey"] as String)))
            assertEquals(53.5, plain.getDouble("latitude"), 0.0)
            assertEquals("Synthetic Person", plain.getString("name"))
            repository.setStaticSharing(AccountIdentity("owner", "fixture_owner"), profile, listOf(friend), false)
            assertFalse(repository.staticSharingEnabled("owner"))
            assertFalse(transport.records.containsKey("sharedLocations/owner/recipients/friend"))
        }
    }

    @Test
    fun savedLocationListenersRemoveRevokedAndCorruptPointsAndStopOnClose() {
        TestAndroidKeyStore.install().use {
            val key = EncryptionUtils.generateSymmetricKey()
            val path = "sharedLocations/friend/recipients/owner"
            transport.records[path] = mapOf("wrappedKey" to KeyManager.wrapKey(key, KeyManager.getOrCreatePublicKey()), "ciphertext" to EncryptionUtils.encryptText("{\"name\":\"Synthetic friend\",\"latitude\":53,\"longitude\":-113}", key), "updatedAt" to Timestamp(Date(500)))
            var latest = emptyList<MapLocationPoint>()
            val subscription = repository.observeStaticContacts("owner", listOf(friend)) { latest = it }
            transport.emit(path)
            assertEquals("Synthetic friend", latest.single().name)
            assertFalse(latest.single().live)
            transport.records[path] = mapOf("wrappedKey" to "corrupt")
            transport.emit(path)
            assertTrue(latest.isEmpty())
            transport.records.remove(path)
            transport.emit(path)
            subscription.close()
            assertTrue(transport.listeners.all { it.closed })
        }
    }

    @Test
    fun liveMapRejectsUnauthorizedLocationsAndAcceptsOnlyFreshAuthenticatedTrailPoints() {
        TestAndroidKeyStore.install().use {
            val key = EncryptionUtils.generateSymmetricKey()
            val path = "liveLocationShares/friend/recipients/owner"
            transport.records[path] = mapOf("wrappedKey" to KeyManager.wrapKey(key, KeyManager.getOrCreatePublicKey()))
            val database = mock(FirebaseDatabase::class.java)
            val refs = mutableMapOf<String, DatabaseReference>()
            val values = mutableMapOf<String, ValueEventListener>()
            var children: ChildEventListener? = null
            fun ref(path: String): DatabaseReference = refs.getOrPut(path) {
                mock(DatabaseReference::class.java, Answer { call -> when(call.method.name) {
                    "child" -> ref(path + "/" + call.getArgument<String>(0))
                    "orderByChild", "startAt" -> refs[path]
                    "addValueEventListener" -> { values[path] = call.getArgument(0); call.arguments[0] }
                    "addChildEventListener" -> { children = call.getArgument(0); call.arguments[0] }
                    else -> RETURNS_DEFAULTS.answer(call)
                } })
            }
            `when`(database.reference).thenReturn(ref(""))
            mockStatic(FirebaseDatabase::class.java).use { static ->
                static.`when`<FirebaseDatabase> { FirebaseDatabase.getInstance(anyString()) }.thenReturn(database)
                var latest = emptyList<MapLocationPoint>()
                val subscription = repository.observeLiveFriends("owner", listOf(friend)) { latest = it }
                transport.emit(path)
                val listener = values.getValue("/liveLocations/friend")
                val now = System.currentTimeMillis()
                fun snapshot(data: Map<String, Any?>, key: String = "point"): DataSnapshot = mock(DataSnapshot::class.java, Answer { call -> when(call.method.name) {
                    "getKey" -> key
                    "child" -> snapshot((data[call.getArgument<String>(0)] as? Map<String, Any?>) ?: mapOf("value" to data[call.getArgument<String>(0)]))
                    "getValue" -> data["value"]
                    else -> RETURNS_DEFAULTS.answer(call)
                } })
                val payload = EncryptionUtils.encryptText("{\"name\":\"Live fixture\",\"latitude\":53,\"longitude\":-113,\"accuracy\":5}", key)
                val live = mapOf("updatedAt" to now, "active" to true, "ciphertext" to payload, "authorized" to mapOf("owner" to true))
                listener.onDataChange(snapshot(live))
                assertTrue(latest.single().live)
                assertEquals(5f, latest.single().accuracy)
                val point = snapshot(mapOf("updatedAt" to now, "ciphertext" to payload))
                children!!.onChildAdded(point, null)
                assertEquals(1, latest.single().trail.size)
                children!!.onChildChanged(point, null)
                children!!.onChildRemoved(point)
                assertTrue(latest.single().trail.isEmpty())
                children!!.onChildAdded(snapshot(mapOf("updatedAt" to 1L, "ciphertext" to payload)), null)
                assertTrue(latest.single().trail.isEmpty())
                listener.onDataChange(snapshot(live + ("active" to false)))
                assertTrue(latest.single().lastKnown)
                listener.onDataChange(snapshot(live + ("authorized" to emptyMap<String, Boolean>())))
                assertTrue(latest.isEmpty())
                listener.onCancelled(DatabaseError.fromCode(DatabaseError.PERMISSION_DENIED))
                subscription.close()
                assertTrue(transport.listeners.all { it.closed })
            }
        }
    }
}
