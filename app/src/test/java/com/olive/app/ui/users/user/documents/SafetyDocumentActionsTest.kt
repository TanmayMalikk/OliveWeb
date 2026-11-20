package com.olive.app.ui.users.user.documents

import android.net.Uri
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.data.media.*
import com.olive.app.data.safety.*
import com.olive.app.support.SessionRuntimeFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SafetyDocumentActionsTest {
    private class Fixture:AutoCloseable {
        val f=SessionRuntimeFixture()
        val repo=mock(SafetyDocumentDataSource::class.java)
        val media=mock(MediaTransferOperations::class.java)
        val doc=SafetyDocument("doc","owner","","Synthetic.pdf","application/pdf",listOf("owner"),objectKey="objects/doc")
        lateinit var snapshot:(DocumentListSnapshot)->Unit
        lateinit var error:(String)->Unit
        var closed=false
        val controller=SafetyDocumentsController(f.application, f.scope, repo, media, failureMessageResolver = { "Access denied" })
        init {
            doAnswer { call -> snapshot=call.getArgument(1);error=call.getArgument(2);ChatSubscription { closed=true } }.`when`(repo)
                .observeFor(anyString(),any<(DocumentListSnapshot)->Unit>() ?: {},any<(String)->Unit>() ?: {})
            controller.load("owner","fixture_owner",false)
            snapshot(DocumentListSnapshot(listOf(doc),DocumentListSource.SERVER,100))
            runBlocking { `when`(repo.listFor("owner")).thenReturn(DocumentListSnapshot(listOf(doc),DocumentListSource.SERVER,101)) }
        }
        override fun close() { controller.clear();f.close() }
    }
    @Test
    fun serverSnapshotsAcknowledgePublishedFilesAndOldAccountCallbacksAreIgnored() {
        Fixture().use { t ->
            assertEquals(100L,t.controller.state.lastSyncedAt)
            verify(t.media).acknowledgePublished(setOf("doc"))
            t.error("Temporary failure")
            assertNull(t.controller.state.error)
            t.controller.prepare("another")
            t.snapshot(DocumentListSnapshot(listOf(t.doc),DocumentListSource.SERVER))
            assertTrue(t.controller.state.files.isEmpty())
            assertEquals("another",t.controller.state.ownerUid)
            assertTrue(t.closed)
        }
    }
    @Test
    fun shareDeleteAndOfflineActionsUseTheActiveOwnerAndRefreshAfterWrites()=runBlocking<Unit> {
        Fixture().use { t ->
            var completed=false
            t.controller.share(t.doc,"owner",mapOf("friend" to "fixture_friend")) { completed=true }
            assertTrue(completed)
            verify(t.repo).share(t.doc,"owner",mapOf("friend" to "fixture_friend"))
            t.controller.makeAvailableOffline(t.doc)
            verify(t.media).makeAvailableOffline(t.doc,"owner")
            t.controller.removeOfflineCopy(t.doc)
            verify(t.media).removeOfflineCopy(t.doc,"owner")
            t.controller.delete(t.doc,"owner")
            verify(t.repo).delete(t.doc,"owner")
            assertFalse(t.controller.state.loading)
        }
    }
    @Test
    fun planGrantsContinuePastOneRecipientFailureButReportWhenEveryGrantFails()=runBlocking<Unit> {
        Fixture().use { t ->
            `when`(t.repo.grantAccess(t.doc,"owner","bad","bad_fixture",true)).thenAnswer { error("Bad recipient") }
            t.controller.grantPlanAccess(listOf(t.doc),"owner",linkedMapOf("bad" to "bad_fixture","good" to "good_fixture"))
            verify(t.repo).grantAccess(t.doc,"owner","good","good_fixture",true)
            assertNull(t.controller.state.error)
            t.controller.grantPlanAccess(listOf(t.doc),"owner",mapOf("bad" to "bad_fixture"))
            assertEquals("Bad recipient",t.controller.state.error)
        }
    }
    @Test
    fun pickerAndCaptureCallbacksEndProgressAndOnlySuccessfulCaptureDeletesItsSource() {
        Fixture().use { t ->
            t.controller.upload(Uri.parse("content://fixture/document"),AccountIdentity("owner","fixture_owner"),"Upload failed")
            val picker=mockingDetails(t.media).invocations.last { it.method.name=="queueDocument" }
            @Suppress("UNCHECKED_CAST") val failure=picker.arguments[5] as (String)->Unit
            failure("")
            assertFalse(t.controller.state.loading)
            assertEquals("Upload failed",t.controller.state.error)
            val file=File.createTempFile("synthetic-", ".jpg",t.f.application.cacheDir).apply { writeBytes(byteArrayOf(1)) }
            var done=false
            t.controller.uploadCaptured(file,"capture.jpg","image/jpeg",AccountIdentity("owner","fixture_owner"),"Capture failed",MediaKind.IMAGE) { done=true }
            val capture=mockingDetails(t.media).invocations.last { it.method.name=="queueFile" }
            assertEquals(OcrReview.NOT_APPLICABLE,capture.arguments[5])
            @Suppress("UNCHECKED_CAST") val success=capture.arguments[8] as (String)->Unit
            success("queued")
            assertTrue(done)
            assertFalse(file.exists())
            assertFalse(t.controller.state.loading)
        }
    }

    @Test
    fun evidenceSelectionSurvivesAnOlderSnapshotUntilTheServerConfirmsOrRejectsIt() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val write = CompletableDeferred<Unit>()
            doAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                val continuation = invocation.rawArguments.last() as kotlin.coroutines.Continuation<Unit>
                val operation: suspend () -> Unit = { write.await() }
                operation.startCoroutineUninterceptedOrReturn(continuation)
            }.`when`(fixture.repo).setEvidence(fixture.doc, "owner", true)
            fixture.controller.setEvidence(fixture.doc, "owner", true)
            assertTrue(fixture.controller.state.files.single().evidence)
            assertEquals(setOf("doc"), fixture.controller.state.evidenceUpdates)
            fixture.snapshot(DocumentListSnapshot(listOf(fixture.doc), DocumentListSource.SERVER, 101))
            assertTrue(fixture.controller.state.files.single().evidence)
            write.completeExceptionally(IllegalStateException("Synthetic permission denied"))
            fixture.f.await { fixture.controller.state.evidenceUpdates.isEmpty() }
            assertFalse(fixture.controller.state.files.single().evidence)
            assertEquals("Synthetic permission denied", fixture.controller.state.error)
        }
    }

    @Test
    fun openingTheSameDocumentTwiceDownloadsOnceAndAccountSwitchDiscardsTheStalePlaintext() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val download = CompletableDeferred<File>()
            doAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                val continuation = invocation.rawArguments.last() as kotlin.coroutines.Continuation<File>
                val operation: suspend () -> File = { download.await() }
                operation.startCoroutineUninterceptedOrReturn(continuation)
            }.`when`(fixture.media).downloadFile(fixture.doc, "owner")
            val local = File.createTempFile("synthetic-opened-", ".pdf", fixture.f.application.cacheDir)
            try {
                fixture.controller.download(fixture.doc) { fail("A previous account's document must stay closed") }
                fixture.controller.download(fixture.doc) { fail("A duplicate request must stay closed") }
                assertEquals(setOf("doc"), fixture.controller.state.openingDocumentIds)
                verify(fixture.media).downloadFile(fixture.doc, "owner")
                fixture.controller.prepare("another")
                download.complete(local)
                fixture.f.await { !local.exists() }
                assertEquals("another", fixture.controller.state.ownerUid)
                assertTrue(fixture.controller.state.openingDocumentIds.isEmpty())
            } finally {
                local.delete()
            }
        }
    }

    @Test
    fun allMediaErrorEnumsMapToDistinctResourcesAndUploadReportsInvalidState() {
        Fixture().use { t ->
            MediaAccessError.entries.forEach { reason ->
                assertTrue(mediaFailureResource(MediaAccessException(reason)) != 0)
            }
            assertTrue(mediaFailureResource(IllegalStateException("unknown")) != 0)

            t.controller.reportError("Custom error")
            assertEquals("Custom error", t.controller.state.error)

            val file = File.createTempFile("ocr-", ".jpg", t.f.application.cacheDir).apply { writeBytes(byteArrayOf(1)) }
            t.controller.uploadCaptured(
                file, "doc.jpg", "image/jpeg", AccountIdentity("owner", "fixture_owner"),
                "Capture failed", MediaKind.DOCUMENT, ocr = DocumentOcrMetadata("text", 0.9f, true),
            ) {}
            t.controller.uploadCaptured(
                file, "doc2.jpg", "image/jpeg", AccountIdentity("owner", "fixture_owner"),
                "Capture failed", MediaKind.DOCUMENT, ocr = DocumentOcrMetadata("", 0.2f, false),
            ) {}
            t.controller.uploadCaptured(
                file, "doc3.jpg", "image/jpeg", AccountIdentity("owner", "fixture_owner"),
                "Capture failed", MediaKind.DOCUMENT, ocr = null,
            ) {}
            file.delete()
        }
    }

    @Test
    fun emergencyReportAndSharedOfflinePathsHandleAuthAppCheckAndMediaTransfers() = runBlocking<Unit> {
        Fixture().use { t ->
            // Capture failure branch
            val capFile = File.createTempFile("cap-", ".jpg", t.f.application.cacheDir).apply { writeBytes(byteArrayOf(1)) }
            t.controller.uploadCaptured(
                capFile, "cap.jpg", "image/jpeg", AccountIdentity("owner", "fixture_owner"),
                "Capture failed fallback", MediaKind.IMAGE, onComplete = { fail() },
            )
            val capInv = mockingDetails(t.media).invocations.last { it.method.name == "queueFile" }
            @Suppress("UNCHECKED_CAST")
            (capInv.arguments[9] as (String) -> Unit)("")
            assertEquals("Capture failed fallback", t.controller.state.error)
            capFile.delete()

            // openShared when offline & not cached
            val connectivity = mock(android.net.ConnectivityManager::class.java)
            doReturn(connectivity).`when`(t.f.application).getSystemService(android.net.ConnectivityManager::class.java)
            `when`(connectivity.activeNetwork).thenReturn(null)
            `when`(t.repo.cachedFor("other-viewer", "missing-doc")).thenReturn(null)
            t.controller.openShared("other-viewer", "missing-doc") { _, _ -> fail() }
            assertEquals("Access denied", t.controller.state.attachmentErrors["missing-doc"])

            // Emergency report flows with mocked FirebaseAuth, FirebaseAppCheck, and FirebaseFunctions
            doReturn(t.f.application).`when`(t.f.application).applicationContext
            val wrappedContext = t.f.application
            val functions = mock(com.google.firebase.functions.FirebaseFunctions::class.java)
            val callable = mock(com.google.firebase.functions.HttpsCallableReference::class.java)
            val callableResult = com.google.firebase.functions.HttpsCallableResult::class.java
                .getConstructor(Any::class.java).newInstance(emptyMap<String, Any>())
            `when`(functions.getHttpsCallable("emergencyStart")).thenReturn(callable)
            `when`(callable.call(any())).thenReturn(com.google.android.gms.tasks.Tasks.forResult(callableResult))

            val auth = mock(com.google.firebase.auth.FirebaseAuth::class.java)
            val user = mock(com.google.firebase.auth.FirebaseUser::class.java)
            `when`(user.uid).thenReturn("owner")
            `when`(user.getIdToken(false)).thenReturn(
                com.google.android.gms.tasks.Tasks.forResult(mock(com.google.firebase.auth.GetTokenResult::class.java)),
            )
            `when`(auth.currentUser).thenReturn(user)

            val appCheck = mock(com.google.firebase.appcheck.FirebaseAppCheck::class.java)
            `when`(appCheck.getAppCheckToken(false))
                .thenReturn(com.google.android.gms.tasks.Tasks.forException(IllegalStateException("AppCheck blocked")))
                .thenReturn(com.google.android.gms.tasks.Tasks.forResult(mock(com.google.firebase.appcheck.AppCheckToken::class.java)))

            val emergencyMedia = mock(MediaTransferOperations::class.java)
            val emergencyRepo = mock(SafetyDocumentDataSource::class.java)
            `when`(emergencyRepo.listFor("owner")).thenReturn(DocumentListSnapshot(emptyList(), DocumentListSource.SERVER, 1L))
            val emergencyController = SafetyDocumentsController(
                context = wrappedContext,
                scope = t.f.scope,
                repository = emergencyRepo,
                media = emergencyMedia,
                emergencyFunctions = { functions },
                renderEmergencyReport = { ctx, _, _, _, _ ->
                    File.createTempFile("emergency-", ".pdf", ctx.cacheDir).apply { writeText("pdf") }
                },
            )
            val profile = com.olive.app.data.account.UserProfile("fixture_owner", "Owner", "", "", "2000-01-01", "English", "", "", "")

            // Blank uid fails immediately
            var blankResult: Result<Unit>? = null
            emergencyController.createEmergencyReport(
                AccountIdentity("", ""), profile, emptyList(), { null }, onFinished = { blankResult = it },
            )
            assertTrue(blankResult!!.isFailure)

            mockStatic(com.google.firebase.auth.FirebaseAuth::class.java).use { authSdk ->
                authSdk.`when`<com.google.firebase.auth.FirebaseAuth> { com.google.firebase.auth.FirebaseAuth.getInstance() }.thenReturn(auth)
                mockStatic(com.google.firebase.appcheck.FirebaseAppCheck::class.java).use { checkSdk ->
                    checkSdk.`when`<com.google.firebase.appcheck.FirebaseAppCheck> { com.google.firebase.appcheck.FirebaseAppCheck.getInstance() }.thenReturn(appCheck)
                    // 1. AppCheck failure path
                    var appCheckResult: Result<Unit>? = null
                    emergencyController.createEmergencyReport(
                        AccountIdentity("owner", "fixture_owner"), profile, emptyList(), { null },
                        onFinished = { appCheckResult = it },
                    )
                    t.f.await { appCheckResult != null }
                    assertTrue(appCheckResult?.exceptionOrNull() is MediaDeviceVerificationException)

                    // 2. Success path -> onQueued
                    var alertSent = false
                    var queuedResult: Result<Unit>? = null
                    emergencyController.createEmergencyReport(
                        AccountIdentity("owner", "fixture_owner"), profile, emptyList(), { null },
                        onAlertSent = { alertSent = true },
                        onFinished = { queuedResult = it },
                    )
                    t.f.await { alertSent && mockingDetails(emergencyMedia).invocations.any { it.method.name == "queueFile" } }
                    val qSuccess = mockingDetails(emergencyMedia).invocations.last { it.method.name == "queueFile" }
                    @Suppress("UNCHECKED_CAST")
                    (qSuccess.arguments[8] as (String) -> Unit)("queued-id")
                    t.f.await { queuedResult != null }
                    assertEquals(true, queuedResult?.isSuccess)

                    // 3. Transfer onFailure path
                    var failedResult: Result<Unit>? = null
                    emergencyController.createEmergencyReport(
                        AccountIdentity("owner", "fixture_owner"), profile, emptyList(), { null },
                        onFinished = { failedResult = it },
                    )
                    t.f.await { mockingDetails(emergencyMedia).invocations.count { it.method.name == "queueFile" } >= 2 }
                    val qFailure = mockingDetails(emergencyMedia).invocations.last { it.method.name == "queueFile" }
                    @Suppress("UNCHECKED_CAST")
                    (qFailure.arguments[9] as (String) -> Unit)("Upload queue failure")
                    t.f.await { failedResult != null }
                    assertEquals(true, failedResult?.isFailure)

                    // 4. Null media path
                    var noMediaResult: Result<Unit>? = null
                    val noMediaController = SafetyDocumentsController(
                        context = wrappedContext,
                        scope = t.f.scope,
                        repository = emergencyRepo,
                        media = null,
                        emergencyFunctions = { functions },
                        renderEmergencyReport = { ctx, _, _, _, _ ->
                            File.createTempFile("emergency-", ".pdf", ctx.cacheDir).apply { writeText("pdf") }
                        },
                    )
                    noMediaController.createEmergencyReport(
                        AccountIdentity("owner", "fixture_owner"), profile, emptyList(), { null },
                        onFinished = { noMediaResult = it },
                    )
                    t.f.await { noMediaResult != null }
                    assertEquals(true, noMediaResult?.isFailure)
                }
            }
        }
    }
}
