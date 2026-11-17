package com.olive.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.R
import com.olive.app.data.account.*
import com.olive.app.data.messaging.*
import com.olive.app.security.DeviceEnrollment
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.shared.messaging.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MessagingPrivacyUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun text(id:Int)=instrumentation.targetContext.getString(id)
    private fun session():SessionViewModel {
        lateinit var session:SessionViewModel
        instrumentation.runOnMainSync {session=ViewModelProvider(compose.activity)[SessionViewModel::class.java]}
        compose.waitUntil(15000) {var ready=false;instrumentation.runOnMainSync {ready=!session.busy};ready}
        instrumentation.runOnMainSync {
            session.runtime.enrollment=DeviceEnrollment("privacy-fixture","")
            session.runtime.state.accountRole=AccountRole.USER
            session.runtime.state.profile=UserProfile("privacy_fixture","Synthetic owner","","","2000-01-01","English","","Edmonton","")
            session.runtime.state.friends=listOf(Friend("first","first-friend","First fixture"),Friend("second","second-friend","Second fixture"))
            session.runtime.pinAuthorizedUid="privacy-fixture";session.runtime.pinAuthorizedEpoch=session.runtime.epoch
            session.runtime.state.screen=SessionScreen.Safe;session.runtime.state.page=SafePage.Messages
        }
        return session
    }
    @Test
    fun privateNoteChooserKeepsDifferentPeoplesNotesSeparateAndAppendsWithoutReplacingHistory() {
        val session=session()
        val targets=listOf(DirectoryUser("first-friend","First fixture"),DirectoryUser("second-friend","Second fixture"))
        var dismissed=0
        compose.setContent {OliveTheme {PrivateContactNoteDialog(session,targets){dismissed++}}}
        compose.onNodeWithTag("private_note_person_first-friend").performClick()
        compose.onNodeWithTag("save_private_contact_note").assertIsNotEnabled()
        compose.onNodeWithTag("private_contact_note_text").performTextInput("Synthetic first observation")
        compose.onNodeWithTag("save_private_contact_note").performClick()
        compose.waitUntil(10000) {dismissed==1}
        compose.onNodeWithText(text(R.string.back)).performClick()
        compose.onNodeWithTag("private_note_person_second-friend").performClick()
        compose.onNodeWithTag("private_contact_note_text").performTextInput("Synthetic second observation")
        compose.onNodeWithTag("save_private_contact_note").performClick()
        compose.waitUntil(10000) {dismissed==2}
        compose.runOnIdle {
            assertTrue(session.messages.privateContactNotes.getValue("first-friend").body.contains("first observation"))
            assertFalse(session.messages.privateContactNotes.getValue("second-friend").body.contains("first observation"))
        }
        compose.onNodeWithText(text(R.string.back)).performClick()
        compose.onNodeWithTag("private_note_person_first-friend").performClick()
        compose.onNodeWithText("Synthetic first observation",substring=true).assertExists()
        compose.onNodeWithTag("private_contact_note_text").performTextReplacement("Synthetic additional observation")
        compose.onNodeWithTag("save_private_contact_note").performClick()
        compose.waitUntil(10000) {dismissed==3}
        compose.runOnIdle {assertTrue(session.messages.privateContactNotes.getValue("first-friend").body.contains("additional observation"))}
    }
    @Test
    fun archivedConversationActionsRestoreTheChatAndOnlyOfferAcceptedFriendsAsPrivateNoteTargets() {
        val conversation=ChatConversation("group","GROUP",listOf("owner","friend","stranger"),mapOf("owner" to "Owner fixture","friend" to "Friend fixture","stranger" to "Stranger fixture"),type="GROUP")
        var notes=emptyList<DirectoryUser>();var archived=false
        compose.setContent {OliveTheme {ConversationActionsDialog(conversation,true,true,"owner",AccountRole.USER,setOf("friend"),{archived=true},{notes=it},{})}}
        compose.onNodeWithText(text(R.string.support_chat_closed)).assertExists()
        compose.onNodeWithText(text(R.string.unarchive_chat)).performClick()
        compose.onNodeWithText(text(R.string.private_note)).performClick()
        compose.runOnIdle {assertTrue(archived);assertEquals(listOf("friend"),notes.map {it.uid})}
    }
    @Test
    fun encryptedTimelineCardsDistinguishSharedItemsAndLocationStatusAndHideUnsentContent() {
        val session=session()
        val bodies=listOf("SHARED_LOCATION:${System.currentTimeMillis()+60000}","SHARED_SAFETY_PLAN:owner:Plan","SHARED_NOTE:entry:Synthetic private note","SHARED_INCIDENT:incident:Synthetic incident","Ordinary synthetic text","")
        compose.setContent {OliveTheme {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            bodies.forEachIndexed {index,body -> ChatMessageBody(ChatMessage("m$index","friend","Friend fixture",body,1),session,false,"Friend fixture"){_,_->} }
            ChatMessageBody(ChatMessage("unsent","friend","Friend fixture","Do not display this removed content",1,unsent=true),session,false,"Friend fixture"){_,_->}
        }}}
        compose.onNodeWithTag("shared_live_location").performClick()
        compose.runOnIdle {assertEquals(SafePage.Location,session.page)}
        compose.onNodeWithText("Synthetic private note").assertExists()
        compose.onNodeWithText("Synthetic incident").assertExists()
        compose.onNodeWithText("Ordinary synthetic text").assertExists()
        compose.onNodeWithText(text(R.string.message_unsent)).assertExists()
        compose.onNodeWithText("Do not display this removed content").assertDoesNotExist()
    }
}
