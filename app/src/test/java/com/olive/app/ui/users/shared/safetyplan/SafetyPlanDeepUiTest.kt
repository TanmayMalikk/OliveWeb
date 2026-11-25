package com.olive.app.ui.users.shared.safetyplan

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.AdminAccountSummary
import com.olive.app.data.account.SafeContact
import com.olive.app.data.messaging.Friend
import com.olive.app.data.safety.SafetyChecklistItem
import com.olive.app.data.safety.SafetyField
import com.olive.app.data.safety.SafetyPlan
import com.olive.app.data.safety.writeSafetyChecklist
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.admin.AdminAccounts
import com.olive.app.ui.users.admin.CreateAccountDialog
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.shared.organization.OrganizationProfileSetup
import com.olive.app.ui.users.shared.organization.OrganizationSettings
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class SafetyPlanDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun safetyPlanChecklistAndContactsSectionsEditAndSaveCleanly() {
        val docsValue = writeSafetyChecklist(
            listOf(
                SafetyChecklistItem("item-1", "Passport", checked = false),
                SafetyChecklistItem("item-2", "Lease", checked = true),
            ),
        )
        val conflictValue = writeSafetyChecklist(
            listOf(SafetyChecklistItem("item-1", "Old Passport", checked = false)),
        )
        val contactsValue = writeSafetyChecklist(
            listOf(SafetyChecklistItem("c-1", "Sam · 555-0199", checked = true)),
        )
        var plan by mutableStateOf(
            SafetyPlan(
                ownerUid = "owner",
                ownerName = "Alex",
                fields = mapOf(
                    "primary_documents" to SafetyField(docsValue, revision = 2L, conflictValue = conflictValue, conflictRevision = 3L),
                    "primary_contacts" to SafetyField(contactsValue, revision = 1L),
                ),
            ),
        )
        var contacts by mutableStateOf(listOf(SafeContact("c-1", "Sam", "555-0199", safeToStay = true, relationship = "Sister")))
        var editCount = 0

        compose.setContent {
            OliveUiTestHost {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SafetyChecklistSection(
                        plan = plan,
                        fieldKey = "primary_documents",
                        onEdit = { _, updated ->
                            editCount++
                            plan = plan.copy(fields = plan.fields + ("primary_documents" to SafetyField(updated, revision = 4L)))
                        },
                        editable = true,
                    )
                    SafetyChecklistSection(
                        plan = plan,
                        fieldKey = "primary_contacts",
                        onEdit = { _, updated ->
                            editCount++
                            plan = plan.copy(fields = plan.fields + ("primary_contacts" to SafetyField(updated, revision = 2L)))
                        },
                        contacts = contacts,
                        contactsEditable = true,
                        onContactsChange = { contacts = it },
                        editable = true,
                    )
                    TrustedContactLinkPicker(
                        friends = listOf(Friend("f-1", "friend-1", "sam_contact")),
                        selectedUid = "friend-1",
                        unavailableUids = emptySet(),
                        enabled = true,
                        tag = "link_picker",
                        onSelected = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("safety_check_primary_documents_0").performScrollTo().performClick()
        compose.onNodeWithTag("safety_add_primary_documents").performScrollTo().performClick()
        compose.onNodeWithTag("safety_remove_primary_documents_1").performScrollTo().performClick()
        compose.onNodeWithTag("safety_add_contact").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("safety_contact_name").performScrollTo().performTextInput("Jordan Lee")
        compose.onNodeWithTag("safety_contact_relationship").performScrollTo().performTextInput("Aunt")
        compose.onNodeWithTag("safety_contact_details").performScrollTo().performTextInput("555-0144")
        compose.onNodeWithTag("safety_save_contact").performScrollTo().performClick()
        assertTrue(editCount >= 2 && contacts.size == 2)
    }

    @Test
    fun adminAccountManagementAndOrganizationSettingsInteractCleanly() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.ADMIN, screen = SessionScreen.Safe, page = SafePage.Home, localMode = true).use { handle ->
            handle.session.runtime.state.adminAccounts = listOf(
                AdminAccountSummary("u-1", "alex_user", AccountRole.USER, "Alex Rivera", "alex@example.com", "555-0100", profileComplete = true, protected = false),
                AdminAccountSummary("s-1", "staff_sam", AccountRole.STAFF, "Sam Staff", "sam@example.com", "", profileComplete = false, protected = false, assignedUsers = 2),
                AdminAccountSummary("a-1", "admin_pat", AccountRole.ADMIN, "Pat Admin", "pat@example.com", "", profileComplete = true, protected = true),
            )
            var stage by mutableIntStateOf(0)

            compose.setContent {
                OliveUiTestHost {
                    when (stage) {
                        0 -> AdminAccounts(handle.session, onBack = {}, onCreate = { stage = 1 }, onDelete = {})
                        1 -> CreateAccountDialog(handle.session, onDismiss = {})
                        2 -> OrganizationSettings(handle.session, onBack = {})
                        else -> OrganizationProfileSetup(handle.session)
                    }
                }
            }
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithText(context.getString(R.string.ui_setup_complete)).performClick()
            compose.onNodeWithText(context.getString(R.string.ui_setup_required)).performClick()
            compose.onNodeWithText(context.getString(R.string.admin_all_account_statuses)).performClick()

            stage = 1
            compose.mainClock.advanceTimeBy(200)
            stage = 2
            compose.mainClock.advanceTimeBy(200)
            stage = 3
            compose.mainClock.advanceTimeBy(200)
        }
    }
}
