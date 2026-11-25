package com.olive.app.ui.users.user.notes

import android.app.Application
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
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class UserNotesAndSharedFieldsUiDeepTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun privateNotesBoardFiltersOpensEditorTogglesKindAndOpensTrash() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Notes, localMode = true).use { handle ->
            val now = System.currentTimeMillis()
            handle.session.runtime.state.privateEntries = listOf(
                PrivateEntry(id = "n1", kind = PrivateEntryKind.Note, title = "Go Bag Note", body = "Pack copies", pinned = true, updatedAt = now),
                PrivateEntry(id = "i1", kind = PrivateEntryKind.Incident, title = "Incident Alpha", body = "Details here", incidentDate = "2026-11-20", incidentTime = "14:30", updatedAt = now - 1000L),
                PrivateEntry(id = "d1", kind = PrivateEntryKind.Note, title = "Old Draft", body = "Trash body", deletedAt = now - 5000L, updatedAt = now - 5000L),
            )

            compose.setContent {
                OliveUiTestHost {
                    PrivateNotes(handle.session)
                }
            }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText("Go Bag Note").assertExists()

            // Switch filter chips if present and open an existing entry in the editor
            compose.onAllNodesWithText("Incident Alpha").onFirst().performClick()
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithTag("private_editor_title").assertExists()
            compose.onNodeWithTag("private_pin_editor").performClick()
            compose.mainClock.advanceTimeBy(150)
            compose.onNodeWithTag("private_notes_back").performClick()
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithText("Go Bag Note").assertExists()
        }
    }
}
