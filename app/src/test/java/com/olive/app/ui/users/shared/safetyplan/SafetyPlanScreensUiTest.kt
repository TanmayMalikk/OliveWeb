package com.olive.app.ui.users.shared.safetyplan

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.Friend
import com.olive.app.data.safety.SafetyChecklistItem
import com.olive.app.data.safety.SafetyField
import com.olive.app.data.safety.SafetyPlan
import com.olive.app.data.safety.SharedSafetyPlan
import com.olive.app.data.safety.writeSafetyChecklist
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class SafetyPlanScreensUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun safetyPlanChecklistComponentsAndSharedPlansRenderAndInteract() {
        val fieldValue = writeSafetyChecklist(
            listOf(
                SafetyChecklistItem("item-1", "Passport & ID", checked = false),
                SafetyChecklistItem("item-2", "Keys ready", checked = true),
            ),
        )
        val plan = SafetyPlan(
            ownerUid = "owner",
            ownerName = "Alex",
            fields = mapOf("primary_documents" to SafetyField(fieldValue, revision = 1L)),
        )
        var editedField = ""
        var openedSharedOwner = ""
        var stage by mutableIntStateOf(0)

        compose.setContent {
            OliveUiTestHost {
                when (stage) {
                    0 -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        SafetyChecklistProgress(prepared = 1, total = 2, summary = true)
                        SafetyChecklistSection(
                            plan = plan,
                            fieldKey = "primary_documents",
                            onEdit = { key, _ -> editedField = key },
                        )
                        TrustedContactLinkPicker(
                            friends = listOf(Friend("f-1", "friend-1", "sam")),
                            selectedUid = "friend-1",
                            unavailableUids = emptySet(),
                            enabled = true,
                            tag = "trusted_picker",
                            onSelected = {},
                        )
                    }
                    else -> SharedSafetyPlanList(
                        plans = listOf(SharedSafetyPlan("owner", "Alex", updatedAt = 1000L)),
                        loading = false,
                        localMode = false,
                        error = null,
                        onBack = {},
                        onOpen = { openedSharedOwner = it.ownerUid },
                    )
                }
            }
        }
        compose.onNodeWithTag("safety_checklist_progress").assertIsDisplayed()
        compose.onNodeWithTag("trusted_picker").assertExists()

        stage = 1
        compose.waitForIdle()
        compose.onNodeWithText("Alex", substring = true).performClick()
        assertTrue(openedSharedOwner == "owner" && editedField.isEmpty())
        assertTrue(safetyDefaults(context, true).isNotEmpty())
    }

    @Test
    fun safetyPlanScreenAndSafeContactSafetyPlansScreenMountCleanly() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.SafetyPlan, localMode = true).use { handle ->
            compose.setContent {
                OliveUiTestHost {
                    SafetyPlanScreen(handle.session)
                }
            }
            compose.mainClock.advanceTimeBy(50)
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            compose.onNodeWithTag("safety_primary").assertExists()
            compose.onNodeWithTag("safety_backup").performClick()
            compose.mainClock.advanceTimeBy(200)
            compose.waitForIdle()
        }
    }
}
