package com.olive.app.ui.users

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.SafetyPlanIntake
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.admin.AdminPortal
import com.olive.app.ui.users.safecontact.SafeContactArea
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.shared.onboarding.SafetyPlanQuestionnaireDialog
import com.olive.app.ui.users.shared.safetyplan.SafeContactSafetyPlansScreen
import com.olive.app.ui.users.shared.safetyplan.SafetyPlanScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class RolePortalsAndSafetyPlanDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun safeContactAreaTraversesAllPagesCleanly() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.SAFE_CONTACT, screen = SessionScreen.Safe, page = SafePage.Home, localMode = true).use { handle ->
            compose.setContent {
                OliveUiTestHost {
                    SafeContactArea(handle.session, exit = {})
                }
            }
            compose.mainClock.advanceTimeBy(200)
            listOf(
                SafePage.Home,
                SafePage.Profile,
                SafePage.Settings,
                SafePage.Notes,
                SafePage.Messages,
                SafePage.Location,
                SafePage.SafetyPlan,
                SafePage.Documents,
                SafePage.Walkthrough,
            ).forEach { target ->
                compose.runOnUiThread { handle.session.runtime.state.page = target }
                compose.mainClock.advanceTimeBy(220)
            }
        }
    }

    @Test
    fun adminPortalNavigatesAcrossAllAdminSubscreens() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.ADMIN, screen = SessionScreen.Portal, page = SafePage.Home, localMode = true).use { handle ->
            compose.setContent {
                OliveUiTestHost {
                    AdminPortal(handle.session)
                }
            }
            compose.mainClock.advanceTimeBy(200)

            fun clickSubscreenBack() {
                val cdMatches = listOf(R.string.back, R.string.ui_back, R.string.ui_previous_safe_page).firstNotNullOfOrNull { resId ->
                    val label = context.getString(resId)
                    runCatching {
                        val nodes = compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(label))
                        if (nodes.fetchSemanticsNodes().isNotEmpty()) nodes[0] else null
                    }.getOrNull()
                }
                if (cdMatches != null) {
                    cdMatches.performClick()
                    return
                }
                val tagMatches = listOf("safe_home", "organization_map_back").firstNotNullOfOrNull { tag ->
                    runCatching {
                        val nodes = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag))
                        if (nodes.fetchSemanticsNodes().isNotEmpty()) nodes[0] else null
                    }.getOrNull()
                }
                if (tagMatches != null) {
                    tagMatches.performClick()
                    return
                }
                val textMatches = listOf(R.string.safety_back, R.string.back, R.string.ui_back).firstNotNullOfOrNull { resId ->
                    val label = context.getString(resId)
                    runCatching {
                        val nodes = compose.onAllNodes(androidx.compose.ui.test.hasText(label))
                        if (nodes.fetchSemanticsNodes().isNotEmpty()) nodes[0] else null
                    }.getOrNull()
                }
                requireNotNull(textMatches) { "No back button found on admin subscreen" }.performClick()
            }

            compose.mainClock.autoAdvance = true
            compose.onNodeWithContentDescription(
                context.getString(R.string.admin_emergency_response),
                substring = true,
            ).performScrollTo()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription(
                context.getString(R.string.admin_emergency_response),
                substring = true,
            ).performClick()
            compose.mainClock.advanceTimeBy(220)
            clickSubscreenBack()
            compose.mainClock.advanceTimeBy(220)

            listOf(
                R.string.safety_inbox,
                R.string.accounts,
                R.string.admin_staff_management,
                R.string.admin_escalation_chats,
                R.string.organization_live_map,
            ).forEach { labelRes ->
                compose.mainClock.autoAdvance = true
                compose.onNodeWithText(context.getString(labelRes)).performScrollTo()
                compose.mainClock.autoAdvance = false
                compose.onNodeWithText(context.getString(labelRes)).performClick()
                compose.mainClock.advanceTimeBy(220)
                clickSubscreenBack()
                compose.mainClock.advanceTimeBy(220)
            }
            compose.onNodeWithContentDescription(context.getString(R.string.account_settings)).performClick()
            compose.mainClock.advanceTimeBy(220)
        }
    }

    @Test
    fun staffAndSafeContactSafetyPlanScreensAndQuestionnaireDialogWork() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.STAFF, screen = SessionScreen.Safe, page = SafePage.SafetyPlan, localMode = true).use { handle ->
            var stage by mutableIntStateOf(0)
            var savedQuestionnaire = false

            compose.setContent {
                OliveUiTestHost {
                    when (stage) {
                        0 -> SafetyPlanScreen(
                            session = handle.session,
                            ownerUid = "owner",
                            ownerName = "Alex",
                            onCompleteConsultation = {},
                        )
                        1 -> SafeContactSafetyPlansScreen(handle.session)
                        else -> SafetyPlanQuestionnaireDialog(
                            initialIntake = SafetyPlanIntake(),
                            enabled = true,
                            onDismiss = {},
                            onSave = { savedQuestionnaire = true },
                            inWindow = stage == 2,
                        )
                    }
                }
            }
            compose.mainClock.advanceTimeBy(50)
            Thread.sleep(150)
            compose.mainClock.advanceTimeBy(300)

            stage = 1
            compose.mainClock.advanceTimeBy(200)

            stage = 2
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.onNodeWithTag("safety_intake_dialog_save").performClick()
            compose.waitForIdle()
            assertTrue(savedQuestionnaire)
        }
    }
}
