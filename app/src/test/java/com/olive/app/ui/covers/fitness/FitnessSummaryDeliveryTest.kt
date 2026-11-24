package com.olive.app.ui.covers.fitness

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.UploadTask
import com.olive.app.support.FirestoreTestDriver
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class FitnessSummaryDeliveryTest {
    @Test
    fun webSummaryPublishesOnlyForTheAuthenticatedAccountAndDoesNotUploadUnchangedTotalsTwice() =
        signedIn("fitness-summary-fixture") { _, uid ->
            runBlocking {
                val date = LocalDate.now()
                val vitals = HeartRateVitals(date, 114, 65, null, 78, 5, true, 1_700_000_000_000)
                val snapshot = FitnessSnapshot(FitnessProfile(stepGoal = 9000), 5960, emptyList(),
                    heartRateHistory = listOf(vitals), watchConnected = true)
                val history = listOf(FitnessHistoryDay(date, 5960, 4.2f, 195f, 525f, 20f, 60f, 9f))
                val firebase = FirestoreTestDriver()
                val storage = mock(FirebaseStorage::class.java)
                val root = mock(StorageReference::class.java)
                val reference = mock(StorageReference::class.java)
                val completed = mock(UploadTask::class.java)
                var captured: JSONObject? = null
                `when`(storage.reference).thenReturn(root)
                `when`(root.child("accounts/$uid/fitness/summary.json")).thenReturn(reference)
                `when`(completed.isComplete).thenReturn(true)
                doAnswer { call ->
                    val data = call.getArgument<ByteArray>(0)
                    val metadata = call.getArgument<StorageMetadata>(1)
                    assertEquals("application/json", metadata.contentType)
                    captured = JSONObject(data.toString(Charsets.UTF_8))
                    completed
                }.`when`(reference).putBytes(any<ByteArray>(), any<StorageMetadata>())
                mockStatic(FirebaseStorage::class.java).use { sdk ->
                    sdk.`when`<FirebaseStorage> { FirebaseStorage.getInstance() }.thenReturn(storage)
                    mockStatic(FirebaseFirestore::class.java).use { firestore ->
                        firestore.`when`<FirebaseFirestore> { FirebaseFirestore.getInstance() }.thenReturn(firebase.db)
                        FitnessCloudSync.upload("wrong-account", snapshot, history)
                        verifyNoInteractions(reference)
                        FitnessCloudSync.upload(uid, snapshot, history)
                        FitnessCloudSync.upload(uid, snapshot, history)
                        verify(reference).putBytes(any<ByteArray>(), any<StorageMetadata>())
                        val payload = checkNotNull(captured)
                        assertEquals(9000, payload.getInt("stepGoal"))
                        assertEquals(5960, payload.getJSONArray("days").getJSONObject(0).getInt("steps"))
                        assertTrue(payload.getJSONArray("vitals").getJSONObject(0).isNull("restingBpm"))
                        assertEquals(114, payload.getJSONArray("vitals").getJSONObject(0).getInt("peakBpm"))
                        assertTrue(firebase.records.containsKey("accountSync/$uid"))
                    }
                }
            }
        }

    @Test
    fun phoneAcceptsOnlyCurrentWatchReadingsFromTheBoundAccountAndSession() =
        signedIn("fitness-watch-listener") { context, uid ->
            val prefs = context.getSharedPreferences(FitnessWatchSync.SYNC_PREFERENCES, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val today = LocalDate.now()
            val service = Robolectric.buildService(FitnessWatchListenerService::class.java)
            try {
                val listener = service.create().get()
                val local = FitnessStore(context, uid)
                fun deliver(type: Int, path: String, account: String = uid, session: String = "current",
                            date: String = today.toString(), timestamp: Long = now) {
                    val payload = DataMap().apply {
                        putString("accountId", account)
                        putString(FitnessWatchSync.SESSION_ID, session)
                        putString("date", date)
                        putInt("watchSteps", 2400)
                        putInt("heartRate", 73)
                        putLong("updatedAt", timestamp)
                        putLong("heartRateAt", timestamp)
                    }
                    val item = mock(DataItem::class.java)
                    `when`(item.uri).thenReturn(Uri.parse("wear://watch$path"))
                    `when`(item.freeze()).thenReturn(item)
                    `when`(item.data).thenReturn(payload.toByteArray())
                    `when`(item.assets).thenReturn(emptyMap())
                    val event = mock(DataEvent::class.java)
                    `when`(event.type).thenReturn(type)
                    `when`(event.dataItem).thenReturn(item)
                    val buffer = mock(DataEventBuffer::class.java)
                    `when`(buffer.iterator()).thenReturn(mutableListOf(event).iterator())
                    listener.onDataChanged(buffer)
                }

                val path = FitnessWatchSync.WATCH_ACTIVITY_PATH
                deliver(DataEvent.TYPE_DELETED, path)
                deliver(DataEvent.TYPE_CHANGED, "/another-data-item")
                deliver(DataEvent.TYPE_CHANGED, path)
                assertEquals(0, local.snapshot().stepsToday)
                prefs.edit().putString(FitnessWatchSync.ACTIVE_ACCOUNT_ID, uid)
                    .putString(FitnessWatchSync.SESSION_ID, "current").putLong("accountStartedAt", now - 100).commit()
                deliver(DataEvent.TYPE_CHANGED, path, account = "previous-owner")
                deliver(DataEvent.TYPE_CHANGED, path, session = "previous-session")
                deliver(DataEvent.TYPE_CHANGED, path, date = today.minusDays(1).toString())
                deliver(DataEvent.TYPE_CHANGED, path, timestamp = now - 200)
                assertEquals(0, local.snapshot().stepsToday)
                deliver(DataEvent.TYPE_CHANGED, path)
                assertEquals(2400, local.snapshot().stepsToday)
                assertTrue(local.snapshot().watchConnected)
            } finally {
                service.destroy()
            }
        }

    @Test
    fun watchSummaryUsesTheBoundSessionAndSkipsGuestAndRepeatedUpdates() =
        signedIn("fitness-watch-fixture") { context, uid ->
            val wearable = mock(DataClient::class.java)
            val item = mock(DataItem::class.java)
            val payloads = mutableListOf<PutDataRequest>()
            doAnswer { call ->
                payloads += call.getArgument<PutDataRequest>(0)
                Tasks.forResult(item)
            }.`when`(wearable).putDataItem(any<PutDataRequest>())
            mockStatic(Wearable::class.java).use { sdk ->
                sdk.`when`<DataClient> { Wearable.getDataClient(context) }.thenReturn(wearable)
                val snapshot = FitnessSnapshot(FitnessProfile(calorieGoal = 1900), 80, emptyList())
                FitnessWatchSync.publish(context, "guest", snapshot)
                FitnessWatchSync.publish(context, uid, snapshot)
                assertTrue(payloads.isEmpty())
                FitnessWatchSync.bindAccount(context, uid)
                FitnessWatchSync.publish(context, "another-account", snapshot)
                FitnessWatchSync.publish(context, uid, snapshot)
                FitnessWatchSync.publish(context, uid, snapshot)
                assertEquals(2, payloads.size)
                assertEquals(FitnessWatchSync.ACCOUNT_SESSION_PATH, payloads.first().uri.path)
                val session = DataMap.fromByteArray(payloads.first().data!!)
                val daily = DataMap.fromByteArray(payloads.last().data!!)
                assertEquals(uid, daily.getString("accountId"))
                assertEquals(session.getString(FitnessWatchSync.SESSION_ID), daily.getString(FitnessWatchSync.SESSION_ID))
                assertEquals(80, daily.getInt("phoneSteps"))
                assertEquals(1900, daily.getInt("calorieGoal"))
                FitnessWatchSync.bindAccount(context, null)
                FitnessWatchSync.publish(context, uid, snapshot)
                assertEquals(3, payloads.size)
                assertFalse(DataMap.fromByteArray(payloads.last().data!!).getBoolean("signedIn"))
            }
        }

    private fun signedIn(uid: String, test: (Context, String) -> Unit) {
        val auth = mock(FirebaseAuth::class.java)
        val user = mock(FirebaseUser::class.java)
        `when`(user.uid).thenReturn(uid)
        `when`(auth.currentUser).thenReturn(user)
        mockStatic(FirebaseAuth::class.java).use { sdk ->
            sdk.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
            test(RuntimeEnvironment.getApplication(), uid)
        }
    }
}
