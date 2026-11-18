package com.olive.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.olive.app.ui.users.shared.safetyplan.SafetyChecklistSection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.BuildConfig
import com.olive.app.R
import com.olive.app.data.account.*
import com.olive.app.data.safety.*
import com.olive.app.security.DeviceEnrollment
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.shared.safetyplan.SafetyPlanScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SafetyPlanAdditionalUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun session(role:AccountRole,plan:SafetyPlan):SessionViewModel {
        check(!BuildConfig.FIREBASE_CONFIGURED)
        lateinit var s:SessionViewModel
        instrumentation.runOnMainSync { s=ViewModelProvider(compose.activity)[SessionViewModel::class.java] }
        compose.waitUntil(15000) { var ready=false;instrumentation.runOnMainSync { ready=!s.busy };ready }
        SafetyPlanStore(context,true).write("viewer",plan.ownerUid,plan)
        instrumentation.runOnMainSync {
            s.runtime.enrollment=DeviceEnrollment("viewer","",role)
            s.runtime.state.accountRole=role
            s.runtime.state.coverUsername="viewer_fixture"
            s.runtime.state.profile=UserProfile("viewer_fixture","Synthetic viewer","","","2000-01-01","English","","Edmonton","",role=role)
            s.runtime.pinAuthorizedUid="viewer";s.runtime.pinAuthorizedEpoch=s.runtime.epoch
            s.runtime.state.screen=if(role in setOf(AccountRole.STAFF,AccountRole.ADMIN)) SessionScreen.Portal else SessionScreen.Safe
            s.runtime.state.page=SafePage.SafetyPlan
        }
        return s
    }
    private fun plan(owner:String="owner")=SafetyPlan(owner,"Synthetic owner",mapOf(
        "primary_documents" to SafetyField(writeSafetyChecklist(listOf(SafetyChecklistItem("passport","Synthetic passport")))),
        "backup_documents" to SafetyField(writeSafetyChecklist(listOf(SafetyChecklistItem("backup","Synthetic backup")))),
        "primary_essentials" to SafetyField(writeSafetyChecklist(listOf(SafetyChecklistItem("old","Synthetic previous note"))))))
    @Test
    fun staffCanEditChecklistSwitchPlansReviewPreviousNotesAndCompleteConsultation() {
        val s=session(AccountRole.STAFF,plan())
        var complete=false
        compose.setContent { OliveTheme { SafetyPlanScreen(s,ownerUid="owner",ownerName="Synthetic owner",onCompleteConsultation={complete=true}) } }
        compose.waitUntil(15000) { s.safetyPlans.plan!=null && !s.safetyPlans.loadingPlan }
        compose.onNodeWithTag("safety_check_primary_documents_0").performClick()
        compose.waitUntil(10000) { readSafetyChecklist(s.safetyPlans.plan!!.fields.getValue("primary_documents").value).first().checked }
        compose.onNodeWithTag("safety_backup").performClick()
        compose.onNodeWithTag("safety_backup_documents").assertTextContains("Synthetic backup")
        compose.onNodeWithTag("safety_primary").performClick()
        compose.onNodeWithTag("safety_plan_page").performScrollToNode(hasTestTag("safety_previous_notes"))
        compose.onNodeWithTag("safety_previous_notes").performClick()
        compose.onNodeWithTag("safety_plan_page").performScrollToNode(hasText("Synthetic previous note"))
        compose.onNodeWithText("Synthetic previous note").assertExists()
        compose.onNodeWithTag("safety_plan_page").performScrollToNode(hasTestTag("complete_safety_consultation"))
        compose.onNodeWithTag("complete_safety_consultation").performClick()
        compose.runOnIdle { assertTrue(complete) }
        compose.onNodeWithTag("safety_checklist_progress").assertIsDisplayed()
    }
    @Test
    fun safeContactPlanIsReadOnlyAndRetainsDocumentsNavigation() {
        val s=session(AccountRole.SAFE_CONTACT,plan())
        compose.setContent { OliveTheme { SafetyPlanScreen(s,ownerUid="owner",ownerName="Synthetic owner") } }
        compose.waitUntil(15000) { s.safetyPlans.plan!=null && !s.safetyPlans.loadingPlan }
        compose.onNodeWithTag("safety_check_primary_documents_0").assertHasNoClickAction()
        compose.onNodeWithTag("safety_add_primary_documents").assertDoesNotExist()
        compose.onNodeWithTag("safety_backup").performClick()
        compose.onNodeWithTag("safety_backup_documents").assertTextContains("Synthetic backup")
        compose.onNodeWithTag("safety_plan_page").performScrollToNode(hasTestTag("safety_documents"))
        compose.onNodeWithTag("safety_documents").performClick()
        compose.runOnIdle { assertEquals(SafePage.Documents,s.page) }
    }
    @Test
    fun userCanReviewAConflictChooseTheIncomingChangeAndKeepTheOtherRow() {
        val mine=writeSafetyChecklist(listOf(SafetyChecklistItem("same","Unchanged row"),SafetyChecklistItem("changed","My document")))
        val incoming=writeSafetyChecklist(listOf(SafetyChecklistItem("same","Unchanged row"),SafetyChecklistItem("changed","Incoming document")))
        val draft=plan("viewer").copy(fields=mapOf("primary_documents" to SafetyField(mine,1,"local",incoming,2)))
        val s=session(AccountRole.USER,draft)
        compose.setContent { OliveTheme { SafetyPlanScreen(s) } }
        compose.onNodeWithText(context.getString(R.string.safety_conflicts_title)).assertExists()
        compose.onNodeWithText("Incoming document").assertExists()
        compose.onNodeWithText(context.getString(R.string.safety_use_synced)).performClick()
        compose.waitUntil(10000) { s.safetyPlans.plan?.hasConflicts==false }
        val values=readSafetyChecklist(s.safetyPlans.plan!!.fields.getValue("primary_documents").value)
        assertEquals(listOf("Unchanged row","Incoming document"),values.map { it.text })
    }

    @Test
    fun removingAStoredDocumentFromAPlanOnlyRemovesItsAttachmentAndDoesNotReattachIt() {
        val slot = "primary_documents"
        val item = SafetyChecklistItem("row", "Synthetic document slot", attachmentIds = listOf("attachment"))
        val plan = mutableStateOf(SafetyPlan("owner", "Synthetic owner", mapOf(
            slot to SafetyField(writeSafetyChecklist(listOf(item))),
        )))
        val document = SafetyDocument("attachment", "owner", "", "Synthetic file", "text/plain", listOf("owner"),
            tags = listOf(safetyChecklistAttachmentTag(slot, item.id)))
        var opened: String? = null
        var picker: String? = null
        compose.setContent {
            OliveTheme {
                SafetyChecklistSection(
                    plan.value, slot,
                    onEdit = { key, value -> plan.value = plan.value.copy(fields = mapOf(key to SafetyField(value))) },

                    documents = listOf(document),
                    onUploadDocument = { picker = it },
                    onOpenDocument = { opened = it.id },
                )
            }
        }
        compose.onNodeWithTag("safety_attachment_row_attachment").performClick()
        compose.runOnIdle { assertEquals(document.id, opened) }
        compose.onNodeWithTag("safety_remove_attachment_attachment").performClick()
        compose.onNodeWithTag("safety_attachment_row_attachment").assertDoesNotExist()
        compose.onNodeWithTag("safety_upload_primary_documents_0").performClick()
        compose.runOnIdle {
            assertEquals(item.id, picker)
            val updated = readSafetyChecklist(plan.value.fields.getValue(slot).value).single()
            assertTrue(updated.attachmentIds.isEmpty())
            assertEquals(listOf(document.id), updated.excludedAttachmentIds)
        }
    }

    @Test
    fun contactControlsAddAnAccountContactWithoutSelectingUnrelatedContacts() {
        val slot = "primary_contacts"
        val trusted = SafeContact("trusted", "Fixture contact", "Synthetic number")
        val contacts = mutableStateOf(listOf(trusted))
        val updated = mutableStateOf(SafetyField(writeSafetyChecklist(emptyList())))
        compose.setContent {
            OliveTheme {
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("contact_fixture")) {
                    SafetyChecklistSection(
                        SafetyPlan("owner", "Synthetic owner", mapOf(slot to updated.value)), slot,
                        onEdit = { _, value -> updated.value = SafetyField(value) },

                        contacts = contacts.value, contactsEditable = true, onContactsChange = { contacts.value = it },
                    )
                }
            }
        }
        compose.onNodeWithTag("safety_contact_trusted").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("safety_contact_stay_trusted").performClick()
        compose.runOnIdle { assertTrue(contacts.value.single().safeToStay) }
        compose.onNodeWithTag("safety_add_contact").performClick()
        compose.onNodeWithTag("safety_contact_name").performTextInput("Another fixture contact")
        compose.onNodeWithTag("safety_contact_details").performTextInput("New synthetic number")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("safety_save_contact").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("Fixture contact", "Another fixture contact"), contacts.value.map { it.name })
            val selected = readSafetyChecklist(updated.value.value).mapNotNull(::readSafetyPlanContact)
            assertEquals(contacts.value.map { it.id }, selected.map { it.id })
        }
    }


    @Test
    fun safetyPlanConsultationControlsShowAssignedStatusAndOfflineStopFeedback() {
        val assigned = plan("viewer").copy(
            consultationRequested = true, consultationConversationId = "ticket", staffUid = "staff",
            safeContactUids = listOf("trusted"), safeContactNames = mapOf("trusted" to "Fixture helper"),
        )
        val session = session(AccountRole.USER, assigned)
        compose.setContent { OliveTheme { SafetyPlanScreen(session) } }
        compose.waitUntil(15_000) { session.safetyPlans.plan != null && !session.safetyPlans.loadingPlan }
        compose.onNodeWithTag("safety_jump_consultation").performClick()
        val content = compose.onNodeWithTag("safety_plan_page")
        content.performScrollToNode(hasText(context.getString(R.string.safety_staff_assigned)))
        compose.onNodeWithText(context.getString(R.string.safety_open_consultation)).assertExists()
        content.performScrollToNode(hasTestTag("safety_stop_sharing"))
        compose.onNodeWithTag("safety_stop_sharing").performClick()
        content.performScrollToNode(hasTestTag("safety_error"))
        compose.onNodeWithTag("safety_error").assertTextContains(context.getString(R.string.safety_account_required))
        compose.runOnIdle {
            assertTrue(session.safetyPlans.plan!!.consultationRequested)
            assertEquals("ticket", session.safetyPlans.plan!!.consultationConversationId)
        }
        compose.onNodeWithTag("safety_checklist_progress").assertIsDisplayed()
    }

}
