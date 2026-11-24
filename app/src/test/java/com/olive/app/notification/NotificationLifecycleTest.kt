package com.olive.app.notification

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import androidx.work.workDataOf
import androidx.work.impl.WorkManagerImpl
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.olive.app.support.FirestoreTestDriver
import com.olive.app.support.SessionRuntimeFixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class NotificationLifecycleTest {
    private fun fixture(action:(SessionRuntimeFixture,FirebaseAuth,FirestoreTestDriver,WorkManagerImpl)->Unit) {
        SessionRuntimeFixture().use { f ->
            val auth=mock(FirebaseAuth::class.java)
            val user=mock(FirebaseUser::class.java)
            `when`(user.uid).thenReturn("owner")
            `when`(auth.currentUser).thenReturn(user)
            val db=FirestoreTestDriver()
            val work=mock(WorkManagerImpl::class.java)
            WorkManagerImpl.setDelegate(work)
            mockStatic(FirebaseAuth::class.java).use { authStatic ->
                authStatic.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
                mockStatic(FirebaseFirestore::class.java).use { cloud ->
                    cloud.`when`<FirebaseFirestore> { FirebaseFirestore.getInstance() }.thenReturn(db.db)
                    try { action(f,auth,db,work) } finally { LocalNotificationEventHandler.cancel(f.application) }
                }
            }
            WorkManagerImpl.setDelegate(null)
        }
    }

    @Test
    fun tokenRegistrationAndSignoutKeepOnlyTheCurrentAccountsDeviceToken()=fixture { f,auth,db,_ ->
        runBlocking { LocalNotificationEventHandler.saveFcmTokenToFirestore(f.application,"owner","synthetic-token") }
        val entry=db.records.entries.single { it.key.startsWith("notificationTokens/owner/devices/") }
        assertEquals("synthetic-token",entry.value["token"])
        assertEquals("owner",entry.value["uid"])
        `when`(auth.currentUser).thenReturn(null)
        runBlocking { LocalNotificationEventHandler.saveFcmTokenToFirestore(f.application,"owner","new-token") }
        assertFalse(db.records.containsKey(entry.key))
    }

    @Test
    fun foregroundConversationBaselineDoesNotNotifyForHistoryAndNewMessagesScheduleOnce()=fixture { f,_,db,work ->
        LocalNotificationEventHandler.start(f.application)
        val path="conversations/chat"
        db.records[path]=mapOf("participantIds" to listOf("owner","friend"),"lastMessageId" to "old","lastSenderUid" to "friend")
        db.emit("conversations")
        val preferences=f.application.getSharedPreferences(LocalSafeUpdateCheckWorker.STATE_FILE,Context.MODE_PRIVATE)
        assertEquals("old",preferences.getString(LocalSafeUpdateCheckWorker.messageKey("owner","chat"),null))
        clearInvocations(work)
        db.records[path]=db.records.getValue(path)+("lastMessageId" to "new")
        db.emit("conversations")
        assertEquals("new",preferences.getString(LocalSafeUpdateCheckWorker.messageKey("owner","chat"),null))
        val count=mockingDetails(work).invocations.count { it.method.name=="enqueueUniqueWork" }
        assertEquals(1,count)
        db.emit("conversations")
        assertEquals(1,mockingDetails(work).invocations.count { it.method.name=="enqueueUniqueWork" })
        LocalNotificationEventHandler.pauseForegroundListeners()
        assertTrue(db.listeners.all { it.closed })
    }

    @Test
    fun explicitWorkerChecksPersistMarkersAndNotifyOnlyForNewIncomingMessages() = fixture { f, auth, db, work ->
        val parameters = mock(WorkerParameters::class.java)
        `when`(parameters.backgroundExecutor).thenReturn(Executor(Runnable::run))
        `when`(parameters.taskExecutor).thenReturn(mock(TaskExecutor::class.java))
        `when`(parameters.inputData).thenReturn(workDataOf())
        val worker = LocalSafeUpdateCheckWorker(f.application, parameters)
        val chat = "conversations/synthetic-chat"
        db.records["friendLists/owner/friends/friend"] = mapOf("friendshipId" to "synthetic-chat")
        db.records["friendLists/owner/friends/incomplete"] = emptyMap()
        db.records[chat] = mapOf("lastMessageId" to "previous", "lastSenderUid" to "friend")
        fun check() = runBlocking { assertEquals(Result.success(), worker.doWork()) }
        fun alertCount() = mockingDetails(work).invocations.count { it.method.name == "enqueueUniqueWork" }

        check()
        assertEquals(0, alertCount())
        db.records[chat] = mapOf("lastMessageId" to "outgoing", "lastSenderUid" to "owner")
        check()
        assertEquals(0, alertCount())
        db.records[chat] = mapOf("lastMessageId" to "incoming", "lastSenderUid" to "friend")
        check()
        check()
        assertEquals(1, alertCount())
        val state = f.application.getSharedPreferences(LocalSafeUpdateCheckWorker.STATE_FILE, Context.MODE_PRIVATE)
        assertEquals("incoming", state.getString(LocalSafeUpdateCheckWorker.messageKey("owner", "synthetic-chat"), null))

        `when`(parameters.inputData).thenReturn(workDataOf(
            LocalSafeUpdateCheckWorker.KEY_SYNC_FCM_ONLY to true,
            LocalSafeUpdateCheckWorker.KEY_FCM_TOKEN to "synthetic-worker-token",
        ))
        check()
        assertTrue(db.records.values.any { it["token"] == "synthetic-worker-token" })
        `when`(auth.currentUser).thenReturn(null)
        check()
        assertEquals(1, alertCount())
    }

    @Test
    fun fcmMessageDeduplicationIsScopedToTheConversationAndAccount()=fixture { f,_,_,work ->
        val payload=mapOf("type" to "message","recipientId" to "owner","senderUid" to "friend","conversationId" to "chat","messageId" to "new")
        LocalNotificationEventHandler.onFcmMessageReceived(f.application,payload)
        LocalNotificationEventHandler.onFcmMessageReceived(f.application,payload)
        assertEquals(1,mockingDetails(work).invocations.count { it.method.name=="enqueueUniqueWork" })
        LocalNotificationEventHandler.onFcmMessageReceived(f.application,payload+("recipientId" to "other"))
        assertEquals(1,mockingDetails(work).invocations.count { it.method.name=="enqueueUniqueWork" })
        LocalNotificationEventHandler.onFcmMessageReceived(f.application,payload+("conversationId" to "second"))
        assertEquals(2,mockingDetails(work).invocations.count { it.method.name=="enqueueUniqueWork" })
    }

    @Test
    fun assignedTicketsAndEmergencyPushesShowAlertsOnlyForTheirRecipient()=fixture { f,_,_,_ ->
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val resources=spy(RuntimeEnvironment.getApplication().resources)
        doReturn("Synthetic alert").`when`(resources).getString(anyInt())
        doReturn(resources).`when`(f.application).resources
        val manager=f.application.getSystemService(NotificationManager::class.java)
        val notifications=shadowOf(manager)
        LocalNotificationEventHandler.onFcmMessageReceived(f.application,mapOf("eventId" to "ticket-assigned","recipientId" to "owner","conversationId" to "ticket"))
        assertEquals(1,notifications.allNotifications.size)
        LocalNotificationEventHandler.onFcmMessageReceived(f.application,mapOf("type" to "emergency","recipientId" to "owner","emergencyId" to "emergency"))
        assertEquals(2,notifications.allNotifications.size)
        LocalNotificationEventHandler.onFcmMessageReceived(f.application,mapOf("type" to "emergency","recipientId" to "other","emergencyId" to "foreign"))
        assertEquals(2,notifications.allNotifications.size)
        assertTrue(manager.notificationChannels.any { it.id==OliveNotificationManager.CHANNEL_SAFE_UPDATES })
    }
}
