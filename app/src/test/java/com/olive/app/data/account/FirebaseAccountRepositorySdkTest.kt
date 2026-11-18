package com.olive.app.data.account

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.*
import com.google.firebase.functions.*
import com.google.firebase.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FirebaseAccountRepositorySdkTest {
    private class Fixture:AutoCloseable {
        val auth=mock(FirebaseAuth::class.java)
        val user=mock(FirebaseUser::class.java)
        val storage=mock(FirebaseStorage::class.java)
        val functions=mock(FirebaseFunctions::class.java)
        val rows=mutableMapOf<String,ByteArray>()
        val uploads=mutableListOf<Pair<String,ByteArray>>()
        val refs=mutableMapOf<String,StorageReference>()
        val sync=mockConstruction(AccountSyncRepository::class.java)
        val repository:FirebaseAccountRepository
        init {
            `when`(auth.currentUser).thenReturn(user)
            `when`(user.uid).thenReturn("owner")
            `when`(user.email).thenReturn("fixture_owner@user.olive.invalid")
            `when`(user.displayName).thenReturn("fixture_owner")
            val token=mock(GetTokenResult::class.java)
            `when`(token.claims).thenReturn(mapOf("role" to "USER"))
            `when`(user.getIdToken(false)).thenReturn(Tasks.forResult(token))
            `when`(storage.reference).thenReturn(ref(""))
            repository=FirebaseAccountRepository(auth,storage,functions)
        }
        fun ref(path:String):StorageReference=refs.getOrPut(path) {
            mock(StorageReference::class.java,Answer { call -> when(call.method.name) {
                "child" -> ref(path.trimEnd('/')+"/"+call.getArgument<String>(0))
                "getName" -> path.substringAfterLast('/')
                "getBytes" -> Tasks.forResult(rows[path] ?: error("Missing synthetic object $path"))
                "putBytes" -> {
                    val bytes=call.getArgument<ByteArray>(0)
                    uploads+=path to bytes.copyOf()
                    val snapshot=mock(UploadTask.TaskSnapshot::class.java)
                    val task=mock(UploadTask::class.java)
                    `when`(task.isComplete).thenReturn(true)
                    `when`(task.result).thenReturn(snapshot)
                    task
                }
                else -> RETURNS_DEFAULTS.answer(call)
            } })
        }
        override fun close() { sync.close() }
    }
    private val identity=AccountIdentity("owner","fixture_owner")
    private val profile=UserProfile("fixture_owner","Synthetic owner","","","2000-01-01","English","","Edmonton","")
    @Test
    fun profileReadAndWriteUseTheAuthenticatedAccountAndEraseTemporaryReadBytes()=runBlocking<Unit> {
        Fixture().use { f ->
            val bytes=profile.toJson().toString().toByteArray()
            f.rows["/accounts/owner/profile.json"]=bytes
            assertEquals(profile,f.repository.profile(identity))
            assertTrue(bytes.all { it==0.toByte() })
            f.repository.saveProfile(identity,profile)
            assertEquals("/accounts/owner/profile.json",f.uploads.last().first)
            assertEquals(profile,UserProfile.fromJson(org.json.JSONObject(String(f.uploads.last().second))))
            assertThrows(IllegalStateException::class.java){runBlocking { f.repository.saveProfile(identity,profile.copy(username="different")) }}
            `when`(f.user.uid).thenReturn("different")
            assertThrows(IllegalStateException::class.java){runBlocking { f.repository.profile(identity) }}
        }
    }
    @Test
    fun historyWritesValidateTheirCategoryAndIdentifierBeforeUploading()=runBlocking<Unit> {
        Fixture().use { f ->
            val event=HistoryRecord("settings","event-123456",SettingsEvent(area="Cover",action="Synthetic update").toJson().toString())
            f.repository.saveHistoryRecord(identity,event)
            assertEquals("/accounts/owner/history/settings/event-123456.json",f.uploads.single().first)
            assertThrows(IllegalArgumentException::class.java){runBlocking { f.repository.saveHistoryRecord(identity,event.copy(category="unknown")) }}
            assertThrows(IllegalArgumentException::class.java){runBlocking { f.repository.saveHistoryRecord(identity,event.copy(id="../foreign")) }}
            assertEquals(1,f.uploads.size)
        }
    }
    @Test
    fun authenticationChecksRoleClaimsAndPasswordChangesReauthenticateBeforeUpdating()=runBlocking<Unit> {
        Fixture().use { f ->
            val result=mock(AuthResult::class.java)
            `when`(result.user).thenReturn(f.user)
            `when`(f.auth.signInWithEmailAndPassword(anyString(),anyString())).thenReturn(Tasks.forResult(result))
            assertEquals(identity,f.repository.authenticate("fixture_owner","Secret!1234",AccountRole.USER))
            assertEquals("owner",f.repository.authenticatedUid())
            assertEquals("fixture_owner",f.repository.authenticatedUsername())
            assertEquals(AccountRole.USER,f.repository.authenticatedRole())
            `when`(f.user.reauthenticate(any<AuthCredential>())).thenReturn(Tasks.forResult<Void>(null))
            `when`(f.user.updatePassword("NewSecret!1234")).thenReturn(Tasks.forResult<Void>(null))
            f.repository.changePassword(identity,"OldSecret!1234","NewSecret!1234")
            val order=inOrder(f.user)
            order.verify(f.user).reauthenticate(any<AuthCredential>())
            order.verify(f.user).updatePassword("NewSecret!1234")
            val token=mock(GetTokenResult::class.java)
            `when`(token.claims).thenReturn(mapOf("role" to "ADMIN"))
            `when`(f.user.getIdToken(false)).thenReturn(Tasks.forResult(token))
            assertThrows(IllegalStateException::class.java){runBlocking { f.repository.authenticate("fixture_owner","Secret!1234",AccountRole.USER) }}
            verify(f.auth).signOut()
        }
    }
}