// Tests plan edits, quick exit, and trusted-contact sharing for users.
package com.olive.app

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.data.*
import com.olive.app.security.InputPolicy
import com.olive.app.ui.*
import org.junit.Assert.*
import org.junit.Test

class SafetyPlanUiTest : OliveUiTestBase() {
    private fun setup(): SessionViewModel {
        openOnboarding()
        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        compose.runOnIdle {
            session.account.chooseNew()
            session.account.authenticate(
                "safety_tester",
                "test-password-123",
                true
            )
        }
        waitFor("full_name")
        compose.runOnIdle {
            session.account.saveSetupProfile(
                UserProfile(
                    "safety_tester", "Safety Tester", "", "", "2000-01-02",
                    "English", "", "Edmonton", InputPolicy.triggerHash("moon garden"),
                    contacts = listOf(
                        SafeContact(
                            "existing_partner",
                            "Trusted Friend",
                            "555-0100"
                        )
                    ), address = DeliveryAddress(city = "Edmonton")
                )
            )
        }
        chooseAppPin()
        fill("new_pin", "123456")
        fill(
            "confirm_pin",
            "123456"
        )
        click("pin_setup_submit")
        waitCover()
        unlock()
        click("safe_safety_plan")
        waitFor("safety_primary_documents")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "safety-plan.png")
            .outputStream().use {
                instrumentation.uiAutomation.takeScreenshot()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        return session
    }

    @Test
    fun primaryAndBackupSaveSeparatelyAndRemainAvailableAfterRestart() {
        val session = setup()
        fill("safety_primary_documents", "Primary safe place")
        click("safety_backup")
        waitFor("safety_backup_documents")
        fill("safety_backup_documents", "Backup safe place")
        compose.waitUntil(10_000) { session.safetyPlans.saving == 0 }
        compose.runOnIdle { session.navigation.quickExit() }
        waitCover()
        unlock()
        click("safe_safety_plan")
        waitFor("safety_primary_documents")
        compose.onNodeWithTag("safety_primary_documents").assertTextContains("Primary safe place")
        click("safety_backup")
        waitFor("safety_backup_documents")
        compose.onNodeWithTag("safety_backup_documents").assertTextContains("Backup safe place")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val encrypted =
            java.io.File(context.noBackupFilesDir, "olive-safety-demo.vault").readBytes()
                .toString(Charsets.UTF_8)
        assertFalse(encrypted.contains("Primary safe place"))
        assertFalse(encrypted.contains("Backup safe place"))
        compose.activityRule.scenario.recreate()
        waitCover()
        unlock()
        click("safe_safety_plan")
        waitFor("safety_primary")
        click("safety_primary")
        waitFor("safety_primary_documents")
        compose.onNodeWithTag("safety_primary_documents").assertTextContains("Primary safe place")
    }

    @Test
    fun immediateQuickExitKeepsTheEditButClearsPrivatePresentation() {
        val session = setup()
        compose.runOnIdle {
            session.safetyPlans.edit(
                "primary_aftercare",
                "After leaving note"
            )
            session.navigation.quickExit()
        }
        waitCover()
        assertNull(session.safetyPlans.plan)
        unlock()
        click("safe_safety_plan")
        waitFor("safety_primary_documents")
        click("safety_jump_grab")
        waitFor("safety_primary_grab")
        compose.onNodeWithTag("safety_heading_primary_grab").assertIsDisplayed()
        compose.onNodeWithTag("safety_plan_page")
            .performScrollToNode(hasTestTag("safety_primary_aftercare"))
        compose.onNodeWithTag("safety_primary_aftercare").assertTextContains("After leaving note")
        assertEquals(SessionScreen.Safe, session.screen)
    }

    @Test
    fun trustedContactsCanBeSelectedOrAddedWithoutTypingThemIntoThePlan() {
        val session = setup()
        click("safety_jump_contacts")
        compose.onNodeWithTag("safety_contact_existing_partner").performScrollTo().assertIsOff()
            .performClick()
        compose.onNodeWithTag("safety_contact_stay_existing_partner").performScrollTo()
            .assertIsOff().performClick()
        compose.waitUntil(10_000) {
            session.safetyPlans.saving == 0 && !session.busy &&
                    session.profile?.contacts?.firstOrNull()?.safeToStay == true
        }
        compose.onNodeWithTag("safety_contact_existing_partner").assertIsOn()

        compose.onNodeWithTag("safety_add_contact").performScrollTo().performClick()
        fill("safety_contact_name", "Backup Friend")
        fill("safety_contact_details", "555-0199")
        click("safety_save_contact")
        compose.waitUntil(10_000) {
            session.safetyPlans.saving == 0 && !session.busy &&
                    session.profile?.contacts?.any { it.name == "Backup Friend" } == true
        }
        val added = session.profile!!.contacts.first { it.name == "Backup Friend" }
        compose.onNodeWithTag("safety_contact_${added.id}").performScrollTo().assertIsOn()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        java.io.File(
            instrumentation.targetContext.getExternalFilesDir(null),
            "safety-plan-contacts.png"
        ).outputStream().use {
            instrumentation.uiAutomation.takeScreenshot()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }

        compose.runOnIdle { session.navigation.quickExit() }
        waitCover()
        unlock()
        click("safe_safety_plan")
        click("safety_jump_contacts")
        compose.onNodeWithTag("safety_contact_existing_partner").performScrollTo().assertIsOn()
        compose.onNodeWithTag("safety_contact_${added.id}").performScrollTo().assertIsOn()
        click("safety_backup")
        click("safety_jump_contacts")
        compose.onNodeWithTag("safety_contact_existing_partner").performScrollTo().assertIsOff()
    }

    @Test
    fun leavingSectionsCanBeReachedEditedAndSavedInBothPlans() {
        val session = setup()
        listOf("destination", "travel", "aftercare").forEach { section ->
            click("safety_jump_$section")
            compose.onNodeWithTag("safety_heading_primary_$section").assertIsDisplayed()
            compose.onNodeWithTag("safety_check_primary_${section}_0").performScrollTo()
                .performClick().assertIsOn()
        }
        click("safety_jump_contacts")
        compose.onNodeWithTag("safety_contact_existing_partner").performScrollTo().assertIsOff()
        click("safety_jump_destination")
        fill("safety_primary_destination", "Stay with my sister")
        click("safety_backup")
        click("safety_jump_destination")
        fill("safety_backup_destination", "Stay with a friend")
        compose.waitUntil(10_000) { session.safetyPlans.saving == 0 }
        compose.runOnIdle { session.navigation.quickExit() }
        waitCover()
        unlock()
        click("safe_safety_plan")
        click("safety_jump_destination")
        compose.onNodeWithTag("safety_primary_destination")
            .assertTextContains("Stay with my sister")
        compose.onNodeWithTag("safety_check_primary_destination_0").assertIsOn()
        click("safety_backup")
        click("safety_jump_destination")
        compose.onNodeWithTag("safety_backup_destination").assertTextContains("Stay with a friend")
    }

    @Test
    fun talkBackCanSwitchPlansAndQuickExitClearsThePrivateEditor() {
        val session = setup()
        TalkBackTestSession(compose.activity).use { talkBack ->
            compose.waitUntil(10_000) { compose.activity.getSystemService(android.view.accessibility.AccessibilityManager::class.java).isTouchExplorationEnabled }
            compose.onNodeWithTag("safety_backup")
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            waitFor("safety_backup_documents")
            compose.onNodeWithTag("quick_exit")
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            waitCover()
            compose.onNodeWithContentDescription("Returned to Olive")
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.LiveRegion,
                        LiveRegionMode.Polite
                    )
                )
            assertTrue(
                "Quick-exit status must not move TalkBack focus",
                talkBack.focused.none { it.contains("Returned to Olive") })
            assertNull(session.safetyPlans.plan)
            compose.onNodeWithTag("safety_plan_page").assertDoesNotExist()
        }
    }

    @Test
    fun checklistTicksPersistAndDocumentItemsAreVisible() {
        val session = setup()
        compose.onNodeWithTag("safety_save_status").assertTextEquals("Saved.")
        compose.onNodeWithTag("safety_upload_primary_documents_0").performScrollTo()
            .assertIsNotEnabled()
        val originalCount =
            readSafetyChecklist(session.safetyPlans.plan!!.fields.getValue("primary_documents").value).size
        compose.onNodeWithTag("safety_check_primary_documents_0").performScrollTo().performClick()
            .assertIsOn()
        compose.onNodeWithTag("safety_add_primary_documents").performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction() and hasContentDescription("Important documents"))
            .onLast()
            .performScrollTo().performTextReplacement("Bring spare keys")
        click("safety_jump_documents")
        compose.onNodeWithContentDescription("Driver’s licence / government-issued photo ID")
            .performScrollTo().assertExists()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        java.io.File(
            instrumentation.targetContext.getExternalFilesDir(null),
            "safety-checklist-documents.png"
        ).outputStream().use {
            instrumentation.uiAutomation.takeScreenshot()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.waitUntil(10_000) { session.safetyPlans.saving == 0 }
        compose.runOnIdle { session.navigation.quickExit() }
        waitCover()
        unlock()
        click("safe_safety_plan")
        waitFor("safety_primary_documents")
        compose.onNodeWithTag("safety_check_primary_documents_0").performScrollTo().assertIsOn()
        compose.onNode(hasSetTextAction() and hasText("Bring spare keys")).performScrollTo()
            .assertExists()
        compose.onNodeWithTag("safety_remove_primary_documents_$originalCount").performScrollTo()
            .performClick()
        compose.onNode(hasSetTextAction() and hasText("Bring spare keys")).assertDoesNotExist()
    }

    @Test
    fun consultationExplainsLocalModeAndDocumentUploadDoesNotOpenAPicker() {
        setup()
        compose.onNodeWithTag("safety_plan_page")
            .performScrollToNode(hasTestTag("safety_request_consultation"))
        click("safety_request_consultation")
        compose.onNodeWithTag("safety_plan_page").performScrollToNode(hasTestTag("safety_error"))
        compose.onNodeWithText("Staff consultation requires a signed-in account. Your plans still work offline.")
            .assertIsDisplayed()
        click("safety_back")
        waitFor("safe_documents")
        click("safe_documents")
        // The current documents screen opens camera/media/document choices from one upload action.
        // Local mode keeps that entry action disabled, so no picker can open.
        compose.onNodeWithTag("documents_upload").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("documents_choose_camera").assertDoesNotExist()
        compose.onNodeWithTag("quick_exit").assertExists()
    }
}
