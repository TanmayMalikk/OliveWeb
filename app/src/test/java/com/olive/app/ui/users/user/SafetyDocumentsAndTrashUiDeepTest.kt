package com.olive.app.ui.users.user

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.data.safety.DocumentListSnapshot
import com.olive.app.data.safety.DocumentListSource
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.data.safety.SafetyDocumentDataSource
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.user.documents.SafetyDocumentsScreen
import com.olive.app.ui.users.user.notes.Trash
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class SafetyDocumentsAndTrashUiDeepTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun safetyDocumentsScreenFiltersCacheBannerAndLocalModeRender() {
        compose.mainClock.autoAdvance = false
        val docsOverride = mock(SafetyDocumentDataSource::class.java)
        val docs = listOf(
            SafetyDocument("d-img", "owner", "p1", "photo.jpg", "image/jpeg", listOf("owner"), 1000L, objectKey = "p1"),
            SafetyDocument("d-vid", "owner", "p2", "clip.mp4", "video/mp4", listOf("owner"), 2000L, objectKey = "p2"),
            SafetyDocument("d-pdf", "owner", "p3", "letter.pdf", "application/pdf", listOf("owner"), 3000L, objectKey = "p3"),
        )
        doAnswer { inv ->
            val onUpdate = inv.getArgument<(DocumentListSnapshot) -> Unit>(1)
            onUpdate(DocumentListSnapshot(docs, DocumentListSource.LOCAL_INDEX, 1000L))
            ChatSubscription {}
        }.`when`(docsOverride).observeFor(
            anyString(),
            org.mockito.ArgumentMatchers.any<(DocumentListSnapshot) -> Unit>() ?: {},
            org.mockito.ArgumentMatchers.any<(String) -> Unit>() ?: {},
        )

        createUiTestSession(
            role = AccountRole.USER,
            screen = SessionScreen.Safe,
            page = SafePage.Documents,
            localMode = false,
            documentsOverride = docsOverride,
        ).use { handle ->
            compose.setContent {
                OliveUiTestHost {
                    SafetyDocumentsScreen(handle.session)
                }
            }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("documents_offline_index").assertExists()

            listOf(
                R.string.safety_storage_photos,
                R.string.safety_storage_videos,
                R.string.safety_storage_documents,
                R.string.documents_all,
            ).forEach { chipRes ->
                compose.onNodeWithText(context.getString(chipRes)).performClick()
                compose.mainClock.advanceTimeBy(120)
            }
            compose.onNodeWithTag("documents_upload").performClick()
            compose.mainClock.advanceTimeBy(120)
        }
    }

    @Test
    fun noteTrashScreenRendersDeletedNotesIncidentsAndRestoreAction() {
        compose.mainClock.autoAdvance = false
        val now = System.currentTimeMillis()
        val entries = listOf(
            PrivateEntry(id = "t-1", kind = PrivateEntryKind.Note, title = "Deleted Note", body = "Body", deletedAt = now - 5_000L, updatedAt = now - 5_000L),
            PrivateEntry(id = "t-2", kind = PrivateEntryKind.Incident, title = "Deleted Incident", body = "Log", deletedAt = now - 8_000L, updatedAt = now - 8_000L),
        )
        val restored = mutableListOf<String>()
        var closed = false
        var showEmpty by mutableIntStateOf(0)
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault())

        compose.setContent {
            OliveUiTestHost {
                Trash(
                    entries = if (showEmpty == 0) entries else emptyList(),
                    formatter = formatter,
                    restore = { restored += it },
                    close = { closed = true },
                )
            }
        }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("Deleted Note").assertExists()
        compose.onNodeWithText("Deleted Incident").assertExists()
        compose.onAllNodesWithText(context.getString(R.string.restore)).onFirst().performClick()
        assertTrue(restored.contains("t-1"))

        compose.onNodeWithTag("close_scratchpad").performClick()
        assertTrue(closed)

        showEmpty = 1
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText(context.getString(R.string.ui_trash_is_empty)).assertExists()
    }
}
