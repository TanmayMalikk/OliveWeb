package com.olive.app.ui.users.shared.safetyplan

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.SafeContact
import com.olive.app.data.account.SafetyPlanIntake
import com.olive.app.data.safety.SafetyChecklistItem
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.data.safety.SafetyPlan
import com.olive.app.data.safety.SafetyPlanField
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
class SafetyPlanFormAndConflictsDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun safetyChecklistSectionsContactsAndDocumentsWorkEndToEnd() {
        val c1 = SafeContact("sc-1", "Jordan Helper", "780-555-0101", safeToStay = false, relationship = "Sibling")
        var contacts by mutableStateOf(listOf(c1))
        val doc1 = SafetyDocument("doc-att-1", "u1", "u1/d", "ID_Scan.pdf", "application/pdf", listOf("u1"))
        val docItems = writeSafetyChecklist(
            listOf(SafetyChecklistItem("it-doc", "Copy ID", checked = false, attachmentIds = listOf("doc-att-1"))),
        )
        var fields by mutableStateOf(
            mapOf(
                "primary_contacts" to SafetyPlanField("primary_contacts", "", 0L, "u1", null, 0L),
                "primary_documents" to SafetyPlanField("primary_documents", docItems, 0L, "u1", null, 0L),
            ),
        )
        var uploadedForItem = ""
        var openedDoc: SafetyDocument? = null
        var syncCalls = 0

        compose.setContent {
            OliveUiTestHost {
                val plan = SafetyPlan("u1", "Alex", fields = fields)
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SafetyChecklistSection(
                        plan = plan,
                        fieldKey = "primary_contacts",
                        onEdit = { key, value -> fields = fields + (key to SafetyPlanField(key, value, 1L, "u1", null, 1L)) },
                        onSyncNow = { syncCalls++ },
                        contacts = contacts,
                        contactsEditable = true,
                        onContactsChange = { contacts = it },
                    )
                    SafetyChecklistSection(
                        plan = plan,
                        fieldKey = "primary_documents",
                        onEdit = { key, value -> fields = fields + (key to SafetyPlanField(key, value, 1L, "u1", null, 1L)) },
                        onSyncNow = { syncCalls++ },
                        editable = true,
                        attachmentsEditable = true,
                        onUploadDocument = { uploadedForItem = it },
                        documents = listOf(doc1),
                        onOpenDocument = { openedDoc = it },
                    )
                }
            }
        }
        compose.waitForIdle()

        // Contacts picker: toggle check, safe_to_stay, open add form, fail empty, add contact
        compose.onNodeWithTag("safety_contact_sc-1").performScrollTo().performClick()
        compose.onNodeWithTag("safety_contact_stay_sc-1").performScrollTo().performClick()
        compose.onNodeWithTag("safety_add_contact").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("safety_save_contact").performScrollTo().performClick()
        compose.onNodeWithTag("safety_contact_name").performScrollTo().performTextReplacement("Sam Helper")
        compose.onNodeWithTag("safety_contact_relationship").performScrollTo().performTextReplacement("Friend")
        compose.onNodeWithTag("safety_contact_details").performScrollTo().performTextReplacement("sam@example.com")
        compose.onNodeWithTag("safety_save_contact").performScrollTo().performClick()
        compose.waitForIdle()

        // Documents section: open attachment, remove attachment, add item, remove item
        compose.onNodeWithTag("safety_attachment_it-doc_doc-att-1").performScrollTo().performClick()
        compose.onNodeWithTag("safety_remove_attachment_doc-att-1").performScrollTo().performClick()
        compose.onNodeWithTag("safety_add_primary_documents").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("safety_remove_primary_documents_1").performScrollTo().performClick()
        compose.waitForIdle()

        assertTrue(contacts.size == 2 && openedDoc?.id == "doc-att-1" && syncCalls >= 2)
    }

    @Test
    fun safetyPlanScreenSwitchesToBackupTabAndAppliesIntake() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.SafetyPlan, localMode = true).use { handle ->
            val session = handle.session
            compose.setContent {
                OliveUiTestHost {
                    SafetyPlanScreen(session)
                }
            }
            compose.waitUntil(timeoutMillis = 5_000) {
                session.safetyPlans.plan != null && !session.safetyPlans.loadingPlan
            }
            compose.waitForIdle()

            compose.onNodeWithTag("safety_backup").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("safety_jump_documents").performScrollTo().performClick()
            compose.waitForIdle()

            applySafetyIntakeToPlan(
                context = context,
                controller = session.safetyPlans,
                intake = SafetyPlanIntake(hasPets = true, hasVehicle = true),
                hasAffectedPeople = true,
            )
        }
    }
}
