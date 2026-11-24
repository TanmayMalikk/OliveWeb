package com.olive.app.data.account

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.*
import com.google.firebase.functions.*
import com.google.firebase.storage.*
import com.olive.app.data.network.requireStringKeyedMap
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
                "listAll" -> {
                    val listing = mock(ListResult::class.java)
                    val prefix = path.trimEnd('/') + "/"
                    val children = rows.keys.filter { it.startsWith(prefix) }.sorted().map { ref(it) }
                    `when`(listing.items).thenReturn(children)
                    Tasks.forResult(listing)
                }
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

        fun callable(name: String, data: Map<String, Any>): HttpsCallableReference {
            val call = mock(HttpsCallableReference::class.java)
            val result = HttpsCallableResult::class.java.getConstructor(Any::class.java).newInstance(data)
            `when`(functions.getHttpsCallable(name)).thenReturn(call)
            `when`(call.call(anyMap<String, Any>())).thenReturn(Tasks.forResult(result))
            return call
        }

        fun signInResult(): AuthResult = mock(AuthResult::class.java).also { result ->
            `when`(result.user).thenReturn(user)
        }

        override fun close() { sync.close() }
    }
    private val identity=AccountIdentity("owner","fixture_owner")
    private val profile=UserProfile("fixture_owner","Synthetic owner","","","2000-01-01","English","","Edmonton","")

    @Test
    fun adminRequestsOnlyTheSelectedAccountViewAndReloadsAfterProfileSetup() = runBlocking<Unit> {
        Fixture().use { f ->
            val list = f.callable("adminListAccounts", mapOf(
                "counts" to mapOf("STAFF" to 1, "USER" to 2),
                "tickets" to mapOf("open" to 3, "closed" to 4),
                "accounts" to listOf(mapOf("uid" to "staff-id", "username" to "staff_member", "role" to "STAFF")),
            ))
            val create = f.callable("adminCreateAccount", emptyMap())
            val delete = f.callable("adminDeleteAccount", emptyMap())
            val fields = profile.toJson()
            val profileMap = fields.keys().asSequence().associateWith(fields::get)
            val complete = f.callable("completeOrganizationProfile", mapOf("profile" to profileMap))
            `when`(f.user.reload()).thenReturn(Tasks.forResult(null))
            mockStatic(FirebaseAuth::class.java).use { authSdk ->
                authSdk.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(f.auth)
                val admin = AdminRepository(f.functions)
                assertEquals(AccountCounts(users = 2, staff = 1, openTickets = 3, closedTickets = 4), admin.summary())
                assertEquals("staff-id", admin.accounts().single().uid)
                assertEquals(AccountRole.STAFF, admin.staffAccounts().single().role)
                verify(list).call(mapOf("view" to "SUMMARY"))
                verify(list).call(mapOf("view" to "ACCOUNTS"))
                verify(list).call(mapOf("view" to "STAFF"))

                admin.create(AdminAccountDraft("staff_member", "Synthetic-123!", AccountRole.STAFF))
                verify(create).call(argThat<Map<String, Any>> { it["username"] == "staff_member" && it["role"] == "STAFF" })
                admin.delete("staff-id")
                verify(delete).call(mapOf("uid" to "staff-id"))
                assertEquals(profile, admin.completeOrganizationProfile(profile.fullName, profile.dob, "", ""))
                verify(complete).call(mapOf("fullName" to profile.fullName, "dob" to profile.dob, "email" to "", "phone" to ""))
                verify(f.user).reload()
            }
        }
    }

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

    @Test
    fun publicRegistrationRejectsAnUnexpectedSignedInAccountAndBlocksStaffSelfRegistration() = runBlocking<Unit> {
        Fixture().use { f ->
            val create = f.callable("createPublicAccount", mapOf("uid" to "owner"))
            val password = "Synthetic!1234"
            val email = AccountPolicy.signInEmail(identity.username, AccountRole.SAFE_CONTACT)
            val signedIn = f.signInResult()
            `when`(f.auth.signInWithEmailAndPassword(email, password)).thenReturn(Tasks.forResult(signedIn))
            assertEquals(identity, f.repository.create("Fixture_Owner", password, AccountRole.SAFE_CONTACT))
            verify(create).call(mapOf("username" to identity.username, "password" to password, "role" to "SAFE_CONTACT"))

            `when`(f.user.uid).thenReturn("another-account")
            val mismatch = runCatching { f.repository.create(identity.username, password, AccountRole.SAFE_CONTACT) }.exceptionOrNull()
            assertTrue(mismatch is IllegalStateException)
            verify(f.auth).signOut()
            val forbidden = runCatching { f.repository.create(identity.username, password, AccountRole.STAFF) }.exceptionOrNull()
            assertTrue(forbidden is IllegalArgumentException)
            verify(create, times(2)).call(anyMap<String, Any>())
        }
    }

    @Test
    fun organizationAndLegacyAliasesAreTriedOnlyForTheirAppropriateAccountRoles() = runBlocking<Unit> {
        Fixture().use { f ->
            val password = "Synthetic!1234"
            val primaryError = IllegalStateException("Synthetic primary login failure")
            val primary = AccountPolicy.signInEmail(identity.username, AccountRole.STAFF)
            val staffAlias = "staff.fixture@example.test"
            `when`(f.auth.signInWithEmailAndPassword(primary, password)).thenReturn(Tasks.forException(primaryError))
            val signedIn = f.signInResult()
            `when`(f.auth.signInWithEmailAndPassword(staffAlias, password)).thenReturn(Tasks.forResult(signedIn))
            val resolver = f.callable("resolveOrganizationLogin", mapOf("authEmail" to staffAlias))
            val aliasRefresh = f.callable("restoreOrganizationLoginAlias", emptyMap())
            `when`(aliasRefresh.call()).thenReturn(Tasks.forException(IllegalStateException("Synthetic refresh failure")))
            val token = mock(GetTokenResult::class.java)
            `when`(token.claims).thenReturn(mapOf("role" to "STAFF"), emptyMap())
            `when`(f.user.getIdToken(false)).thenReturn(Tasks.forResult(token))
            assertEquals(identity, f.repository.authenticate(identity.username, password, AccountRole.STAFF))
            verify(resolver).call(mapOf("identifier" to identity.username, "role" to "STAFF"))
            verify(aliasRefresh).call()

            val safeContact = AccountPolicy.signInEmail(identity.username, AccountRole.SAFE_CONTACT)
            val legacy = AccountPolicy.legacyUserEmail(identity.username)
            `when`(f.auth.signInWithEmailAndPassword(safeContact, password)).thenReturn(Tasks.forException(primaryError))
            `when`(f.auth.signInWithEmailAndPassword(legacy, password)).thenReturn(Tasks.forResult(signedIn))
            assertEquals(identity, f.repository.authenticate(identity.username, password, AccountRole.SAFE_CONTACT))
            verify(f.auth).signInWithEmailAndPassword(legacy, password)
        }
    }

    @Test
    fun historyRecordsReadsAllCategoriesAndSignOutDelegatesAndHandlesMissingDisplayName() = runBlocking<Unit> {
        Fixture().use { f ->
            val settingsBytes = SettingsEvent(id = "set-1234", timestamp = 10L, area = "Privacy", action = "Updated").toJson().toString().toByteArray()
            val triggerBytes = TriggerEvent(id = "trig-1234", timestamp = 20L, source = "Cover").toJson().toString().toByteArray()
            val entryBytes = PrivateEntry(id = "note-1234", timestamp = 30L, title = "T", body = "B", deletedAt = 100L).toJson().toString().toByteArray()
            f.rows["/accounts/owner/history/settings/set-1234.json"] = settingsBytes
            f.rows["/accounts/owner/history/triggers/trig-1234.json"] = triggerBytes
            f.rows["/accounts/owner/history/private_entries/note-1234.json"] = entryBytes

            val records = f.repository.historyRecords(identity)
            assertEquals(3, records.size)
            assertTrue(settingsBytes.all { it == 0.toByte() })
            assertTrue(records.any { it.category == "settings" && it.id == "set-1234" })
            assertTrue(records.any { it.category == "triggers" && it.id == "trig-1234" })
            assertTrue(records.any { it.category == "private_entries" && it.id == "note-1234" })
            assertEquals(1, PrivateEntry.fromJson(org.json.JSONObject(records.first { it.category == "private_entries" }.json)).trashDaysRemaining(100L))

            `when`(f.user.displayName).thenReturn(null)
            assertEquals("fixture_owner", f.repository.authenticatedUsername())

            f.repository.signOut()
            verify(f.auth).signOut()

            assertThrows(IllegalStateException::class.java) {
                "bad".requireStringKeyedMap("test")
            }
            assertEquals(mapOf("a" to 1), mapOf("a" to 1).requireStringKeyedMap("test"))
        }
    }
}
