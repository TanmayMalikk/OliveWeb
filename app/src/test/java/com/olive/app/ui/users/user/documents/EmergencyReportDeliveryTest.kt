package com.olive.app.ui.users.user.documents

import android.content.ContextWrapper
import com.google.android.gms.tasks.Tasks
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.AppCheckToken
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GetTokenResult
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.HttpsCallableReference
import com.google.firebase.functions.HttpsCallableResult
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.safety.DocumentListSnapshot
import com.olive.app.data.safety.DocumentListSource
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.data.safety.SafetyDocumentDataSource
import java.io.File
import kotlinx.coroutines.runBlocking
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.UserProfile
import com.olive.app.data.media.MediaDeviceVerificationException
import com.olive.app.support.SessionRuntimeFixture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EmergencyReportDeliveryTest {
    @Test
    fun unavailableDeviceAttestationPreventsCreatingAnEmergencyRequestOrUpload() {
        SessionRuntimeFixture().use { fixture ->
            val identity = AccountIdentity("owner", "fixture_owner")
            val profile = UserProfile("fixture_owner", "Synthetic owner", "", "", "2000-01-01", "English", "", "", "")
            val auth = mock(FirebaseAuth::class.java)
            val user = mock(FirebaseUser::class.java)
            val token = mock(GetTokenResult::class.java)
            `when`(auth.currentUser).thenReturn(user)
            `when`(user.uid).thenReturn(identity.uid)
            `when`(user.getIdToken(false)).thenReturn(Tasks.forResult(token))
            val appCheck = mock(FirebaseAppCheck::class.java)
            val outage = IOException("Synthetic attestation outage")
            `when`(appCheck.getAppCheckToken(false)).thenReturn(Tasks.forException(outage))
            val functions = mock(FirebaseFunctions::class.java)
            val media = mock(MediaTransferOperations::class.java)
            val context = object : ContextWrapper(fixture.application) {
                override fun getApplicationContext() = this
                override fun getResources() = fixture.application.resources
            }
            val controller = SafetyDocumentsController(context, fixture.scope, null, media, emergencyFunctions = { functions })
            controller.prepare(identity.uid)

            mockStatic(FirebaseAuth::class.java).use { authStatic ->
                authStatic.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
                mockStatic(FirebaseAppCheck::class.java).use { checkStatic ->
                    checkStatic.`when`<FirebaseAppCheck> { FirebaseAppCheck.getInstance() }.thenReturn(appCheck)
                    val done = AtomicReference<Result<Unit>>()
                    val confirmations = java.util.concurrent.atomic.AtomicInteger()
                    controller.createEmergencyReport(
                        identity, profile, emptyList(), { error("Location must not be requested") },
                        onAlertSent = { confirmations.incrementAndGet() }, onFinished = done::set,
                    )
                    val failure = done.get()?.exceptionOrNull()
                    assertTrue(failure.toString(), failure is MediaDeviceVerificationException)
                    assertSame(outage, failure?.cause)
                    assertFalse(controller.state.loading)
                    assertEquals(0, confirmations.get())
                    verifyNoInteractions(functions, media)
                }
            }
        }
    }

    @Test
    fun emergencyUploadCombinesIncidentAttachmentsAndTaggedEvidenceAndCleansUpTheReport() {
        SessionRuntimeFixture().use { fixture ->
            val identity = AccountIdentity("owner", "fixture_owner")
            val profile = UserProfile("fixture_owner", "Synthetic owner", "", "", "2000-01-01", "English", "", "", "")
            val context = fixture.application.applicationContext
            val auth = mock(FirebaseAuth::class.java)
            val user = mock(FirebaseUser::class.java)
            val token = mock(GetTokenResult::class.java)
            `when`(auth.currentUser).thenReturn(user)
            `when`(user.uid).thenReturn(identity.uid)
            `when`(user.getIdToken(false)).thenReturn(Tasks.forResult(token))
            val appCheck = mock(FirebaseAppCheck::class.java)
            val proof = mock(AppCheckToken::class.java)
            `when`(appCheck.getAppCheckToken(false)).thenReturn(Tasks.forResult(proof))
            val functions = mock(FirebaseFunctions::class.java)
            val start = mock(HttpsCallableReference::class.java)
            val startResult = mock(HttpsCallableResult::class.java)
            `when`(functions.getHttpsCallable("emergencyStart")).thenReturn(start)
            `when`(start.call(anyMap<String, Any>())).thenReturn(Tasks.forResult(startResult))
            val documents = mock(SafetyDocumentDataSource::class.java)
            val media = mock(MediaTransferOperations::class.java)
            val attachment = SafetyDocument("incident-attachment", "owner", "", "Synthetic attachment.txt", "text/plain", listOf("owner"))
            val evidence = attachment.copy(id = "separate-evidence", evidence = true)
            val unrelated = attachment.copy(id = "unrelated")
            val incident = PrivateEntry(title = "Synthetic incident", body = "Details", kind = PrivateEntryKind.Incident,
                attachmentIds = listOf(attachment.id))
            val note = incident.copy(kind = PrivateEntryKind.Note, attachmentIds = listOf(unrelated.id))
            val selected = AtomicReference<Map<String, EmergencyReportMedia>>()
            val rendered = File.createTempFile("fixture-report-", ".pdf", context.cacheDir)
            try {
                val controller = SafetyDocumentsController(context, fixture.scope, documents, media,
                    emergencyFunctions = { functions },
                    renderEmergencyReport = { _, _, entries, _, files ->
                        assertEquals(listOf(incident), entries)
                        selected.set(files)
                        rendered
                    },
                )
                controller.prepare(identity.uid)
                mockStatic(FirebaseAuth::class.java).use { authStatic ->
                    authStatic.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
                    mockStatic(FirebaseAppCheck::class.java).use { checkStatic ->
                        checkStatic.`when`<FirebaseAppCheck> { FirebaseAppCheck.getInstance() }.thenReturn(appCheck)
                        runBlocking {
                            `when`(documents.listFor(identity.uid)).thenReturn(
                                DocumentListSnapshot(listOf(attachment, evidence, unrelated), DocumentListSource.SERVER),
                            )
                            val done = AtomicReference<Result<Unit>>()
                            val confirmations = java.util.concurrent.atomic.AtomicInteger()
                            controller.createEmergencyReport(identity, profile, listOf(incident, note), { null },
                                onAlertSent = { confirmations.incrementAndGet() }, onFinished = done::set)
                            fixture.await { mockingDetails(media).invocations.any { it.method.name == "queueFile" } || done.get() != null }
                            val queue = mockingDetails(media).invocations.single { it.method.name == "queueFile" }
                            assertEquals(1, confirmations.get())
                            assertEquals(setOf(attachment.id, evidence.id), selected.get().keys)
                            verify(documents, never()).download(context, unrelated)
                            val request = mockingDetails(start).invocations.single { it.method.name == "call" }.arguments[0] as Map<*, *>
                            assertEquals("emergency-report-${request["id"]}.pdf", queue.arguments[1])
                            assertEquals("application/pdf", queue.arguments[2])
                            assertEquals(identity, queue.arguments[3])
                            @Suppress("UNCHECKED_CAST")
                            (queue.arguments[9] as (String) -> Unit)("Synthetic upload rejected")
                            assertEquals("Synthetic upload rejected", done.get().exceptionOrNull()?.message)
                            assertFalse(rendered.exists())
                            assertFalse(controller.state.loading)
                        }
                    }
                }
            } finally {
                rendered.delete()
            }
        }
    }

}
