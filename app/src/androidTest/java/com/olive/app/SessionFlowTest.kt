package com.olive.app

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.security.EncryptedVault
import com.olive.app.security.child
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.io.File

class SessionFlowTest {
    private val compose=createAndroidComposeRule<MainActivity>()
    private val cleanStore=object : ExternalResource() {
        override fun before() {
            check(!BuildConfig.FIREBASE_CONFIGURED) { "These tests only run against local test accounts." }
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            val context=instrumentation.targetContext
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.ACCESS_COARSE_LOCATION)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.ACCESS_FINE_LOCATION)
            val directory=context.noBackupFilesDir
            listOf("olive-demo.vault","olive-demo.vault.bak","olive-demo.vault.new",
                "olive-demo-activity.vault","olive-demo-activity.vault.bak","olive-demo-activity.vault.new",
                "olive-cover.vault","olive-cover.vault.bak","olive-cover.vault.new").forEach { File(directory,it).delete() }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(cleanStore).around(compose)

    private fun waitFor(tag: String) {
        try { compose.waitUntil(45_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
        catch(e: Throwable) {
            val session=androidx.lifecycle.ViewModelProvider(compose.activity)[com.olive.app.ui.SessionViewModel::class.java]
            throw AssertionError("Waiting for $tag; screen=${session.screen}, busy=${session.busy}, message=${session.message}",e)
        }
    }
    private fun fill(tag: String, text: String) { compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(text) }
    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun chooseBirthday() {
        click("dob_year");click("dob_year_2000")
        click("dob_month");click("dob_month_1")
        click("dob_day");click("dob_day_2")
    }
    private fun register(pin: String="123456") {
        waitFor("new_user"); click("new_user")
        fill("username","olive_tester"); fill("password","test-password-123"); fill("confirm_password","test-password-123")
        click("credentials_submit"); waitFor("profile_submit")
        fill("full_name","Test Person"); fill("abuser_name","Test Person Causing Harm"); chooseBirthday(); fill("trigger","moon garden")
        fill("address_line1","123 Test Street"); fill("address_city","Edmonton")
        fill("address_province","Alberta"); fill("address_postal","T5J 0N3")
        click("profile_submit"); waitFor("pin_setup_submit")
        fill("new_pin",pin); fill("confirm_pin",pin); click("pin_setup_submit")
        waitCover()
    }
    private fun waitCover() {
        try { compose.waitUntil(30_000) { compose.onAllNodesWithTag("cover_root").fetchSemanticsNodes().isNotEmpty() } }
        catch(e: Exception) {
            val session=androidx.lifecycle.ViewModelProvider(compose.activity)[com.olive.app.ui.SessionViewModel::class.java]
            throw AssertionError("Expected cover; screen=${session.screen}, busy=${session.busy}, message=${session.message}\n${compose.onRoot().printToString()}",e)
        }
    }
    private fun trigger() { compose.onNode(hasSetTextAction()).performTextInput("moon garden"); waitFor("unlock_pin") }
    private fun enterPin(pin: String) {
        pin.forEach { compose.onNodeWithTag("pin_digit_$it").performClick() }
        compose.onNodeWithTag("unlock_submit").performClick()
    }
    private fun unlock(pin: String = "123456") { trigger(); enterPin(pin); waitFor("safe_settings") }

    @Test fun onboardingNeedsCityButNotAFullDeliveryAddress() {
        waitFor("new_user");click("new_user")
        fill("username","city_only");fill("password","test-password-123");fill("confirm_password","test-password-123")
        click("credentials_submit");waitFor("profile_submit")
        fill("full_name","City Test");chooseBirthday();fill("address_city","Edmonton");fill("trigger","moon garden")
        click("profile_submit");waitFor("pin_setup_submit")
        fill("new_pin","1234");fill("confirm_pin","1234");click("pin_setup_submit");waitCover()
        val session=androidx.lifecycle.ViewModelProvider(compose.activity)[com.olive.app.ui.SessionViewModel::class.java]
        assertEquals("Edmonton",session.deliveryAddress.city)
        assertFalse(session.deliveryAddress.valid)
    }

    @Test fun coverChoiceSwitchesNewsAndCalculatorAndBothReachSafeArea() {
        register()
        unlock()
        click("safe_settings")
        click("cover_choice_news")
        compose.onNodeWithTag("settings_full_name").assertExists()
        compose.onNodeWithTag("quick_exit").performClick()
        waitFor("news_cover")
        fill("news_search","moon garden")
        waitFor("unlock_pin")
        enterPin("123456"); waitFor("safe_settings")
        click("safe_settings")
        click("cover_choice_calculator")
        compose.onNodeWithTag("settings_full_name").assertExists()
        compose.onNodeWithTag("quick_exit").performClick()
        waitFor("calculator_cover")
        "123456".forEach { compose.onNodeWithTag("calc_key_$it").performClick() }
        compose.onNodeWithTag("calc_key_equals").performClick()
        compose.onNodeWithTag("calc_key_equals").performClick()
        waitFor("safe_settings")
        click("sign_out")
        waitFor("returning_user")
    }

    @Test fun onboardingContactsQuickExitAndReturningUser() {
        register()
        compose.runOnIdle { assertFalse(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
        // Cover cart stays alive through the secure overlay.
        compose.onNodeWithText("Garden harvest bowl").performClick()
        compose.onNodeWithText("Add to cart").performScrollTo().performClick()
        compose.onNodeWithText("Discover").performClick()
        unlock()
        compose.onNodeWithTag("safe_notes").assertExists()
        repeat(3) { compose.onNodeWithTag("future_feature_${it+2}").assertExists() }
        click("safe_notes");click("new_private_entry");click("private_kind_incident")
        fill("private_entry_title","Private incident")
        fill("private_entry_body","Details that must stay inside Olive Branch")
        click("save_private_entry")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Details that must stay inside Olive Branch").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("quick_exit").performClick();waitCover()
        compose.onNodeWithText("Details that must stay inside Olive Branch").assertDoesNotExist()
        unlock();click("safe_notes")
        compose.onNodeWithText("Details that must stay inside Olive Branch").assertExists()
        fill("private_notes_search","Private incident")
        compose.onNodeWithTag("private_entry_row").assertExists()
        fill("private_notes_search","")
        compose.onNodeWithTag("private_pin").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Pinned").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("private_entry_row").performClick()
        fill("private_entry_body","Updated private incident details")
        click("save_private_entry")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Updated private incident details").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Edited",substring=true).assertExists()
        compose.onNodeWithTag("private_edit_log").performClick()
        compose.onNodeWithText("Edit log").assertExists()
        compose.onNodeWithText("Details that must stay inside Olive Branch",substring=true).assertExists()
        compose.onAllNodesWithText("Updated private incident details",substring=true).assertAny(hasText("Updated private incident details",substring=true))
        compose.onNodeWithText("Close").performClick()
        val privateBytes=File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,"olive-demo-activity.vault").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(privateBytes.contains("Details that must stay inside Olive Branch"))
        assertFalse(privateBytes.contains("Updated private incident details"))
        click("safe_home")
        compose.runOnIdle { assertFalse(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
        click("safe_settings");click("safe_contacts")
        fill("contact_name","Trusted Friend"); fill("contact_details","friend@example.invalid"); click("save_contact")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Trusted Friend").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Edit").performScrollTo().performClick()
        fill("contact_name","Updated Friend"); click("save_contact")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Updated Friend").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("quick_exit").performClick(); waitCover()
        compose.onNodeWithText("Updated Friend").assertDoesNotExist()
        compose.activityRule.scenario.recreate(); waitCover()
        unlock();click("safe_settings");click("safe_contacts");compose.onNodeWithText("Updated Friend").assertExists()
        compose.onNodeWithTag("quick_exit").performClick(); waitCover()
        compose.onNodeWithText("Cart").performClick()
        compose.onNodeWithText("Garden harvest bowl").assertExists()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitCover()
        unlock(); click("sign_out"); waitFor("returning_user")
        click("returning_user")
        fill("username","olive_tester"); fill("password","wrong-password"); click("credentials_submit")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Unable to sign in. Check your credentials and connection, then try again.").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) { !androidx.lifecycle.ViewModelProvider(compose.activity)[com.olive.app.ui.SessionViewModel::class.java].busy }
        fill("password","test-password-123"); click("credentials_submit"); waitFor("pin_setup_submit")
        fill("new_pin","654321"); fill("confirm_pin","654321"); click("pin_setup_submit")
        waitCover()
        compose.onNodeWithText("Discover").performClick(); waitCover()
        unlock("654321");click("safe_settings");click("safe_contacts");compose.onNodeWithText("Updated Friend").assertExists()
    }

    @Test fun fifthFailureAllowsImmediateRetryAndLegacyCooldownIsIgnored() {
        register()
        repeat(5) { index ->
            if(index==0) trigger()
            enterPin("999999")
            if(index<4) compose.waitUntil(30_000) { compose.onAllNodesWithText("Incorrect PIN. ${4-index} attempts remaining.").fetchSemanticsNodes().isNotEmpty() }
        }
        waitCover()
        compose.onNodeWithTag("safe_attempts").assertDoesNotExist()
        trigger(); enterPin("999999")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Incorrect PIN. 4 attempts remaining.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("quick_exit").performClick(); waitCover()
        compose.activityRule.scenario.recreate(); waitCover()
        // Simulate an upgrade from a version with an active cooldown.
        val vault=EncryptedVault(InstrumentationRegistry.getInstrumentation().targetContext,"demo")
        vault.update { root -> root.child("enrollments").getJSONObject(root.getString("activeUid"))
            .put("failures",5).put("lockedUntil",System.currentTimeMillis()+60_000L) }
        unlock();click("safe_activity")
        compose.onAllNodesWithTag("attempt_row").onFirst().assertExists()
        val root=vault.read()
        assertFalse(root.getJSONObject("enrollments").getJSONObject(root.getString("activeUid")).has("lockedUntil"))
        assertEquals(6,root.getJSONObject("attempts").getJSONArray(root.getString("activeUid")).length())
        val bytes=File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,"olive-demo.vault").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("Test Person")); assertFalse(bytes.contains("moon garden")); assertFalse(bytes.contains("test-password-123"))

        click("activity_from")
        compose.onNodeWithTag("activity_from_calendar").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithTag("activity_from_clock").assertExists()
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun backgroundAndInFlightUnlockNeverReopenSafeArea() {
        register(); unlock(); click("safe_profile")
        compose.onNodeWithText("Test Person Causing Harm").assertExists()
        compose.activityRule.scenario.onActivity { it.onUserLeaveHint() }
        waitCover(); compose.onNodeWithText("Test Person Causing Harm").assertDoesNotExist()
        unlock(); click("safe_profile")
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        waitCover(); compose.onNodeWithText("Test Person").assertDoesNotExist()
        trigger(); enterPin("123456")
        compose.onNodeWithTag("quick_exit").performClick()
        waitCover()
        compose.waitUntil(30_000) { !androidx.lifecycle.ViewModelProvider(compose.activity)[com.olive.app.ui.SessionViewModel::class.java].busy }
        compose.waitForIdle()
        compose.onNodeWithTag("safe_contacts").assertDoesNotExist()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Your safe space").assertDoesNotExist()
    }
    @Test fun settingsLanguageTriggerAndAddressPersist() {
        register("1234"); unlock("1234"); click("safe_settings")
        fill("settings_situation","Updated test situation")
        click("language"); compose.onNodeWithText("Français").performClick(); click("save_settings")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Paramètres").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Enregistrer les paramètres").assertExists()
        fill("settings_trigger","new secret"); fill("settings_pin","9999"); click("change_trigger")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("NIP incorrect.",substring=true).fetchSemanticsNodes().isNotEmpty() }
        fill("settings_trigger","new secret"); fill("settings_pin","1234"); click("change_trigger")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Mot déclencheur modifié.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("quick_exit").performClick(); waitCover()
        compose.onNodeWithText("Découvrir").assertExists()
        compose.activityRule.scenario.recreate(); waitCover()
        compose.onNodeWithText("Découvrir").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("new secret"); waitFor("unlock_pin")
        enterPin("1234"); waitFor("safe_settings")
        val vault=EncryptedVault(InstrumentationRegistry.getInstrumentation().targetContext,"demo")
        val root=vault.read();val uid=root.getString("activeUid")
        val profile=root.getJSONObject("profiles").getJSONObject(uid)
        assertEquals("French",profile.getString("language"))
        assertEquals("Updated test situation",profile.getString("situation"))
        assertEquals("123 Test Street",profile.getJSONObject("address").getString("line1"))
        assertEquals(1,root.getJSONObject("attempts").getJSONArray(uid).length())
        click("safe_activity")
        compose.onAllNodesWithTag("settings_history_row").onFirst().assertExists()
        compose.onAllNodesWithTag("trigger_history_row").onFirst().assertExists()
    }
    @Test fun checkoutDeclinesDiscardsPaymentAndBackgroundClearsForm() {
        register()
        compose.onNodeWithText("Garden harvest bowl").performClick()
        compose.onNodeWithText("Add to cart").performScrollTo().performClick()
        compose.onNodeWithText("Cart").performClick()
        compose.onNodeWithText("Continue to checkout").performScrollTo().performClick()
        fill("checkout_recipient","Transient Recipient"); fill("cardholder","Transient Cardholder")
        fill("card_number","4242424242424242"); fill("card_expiry","12/30"); fill("card_cvc","123"); fill("billing_address","Transient Billing")
        click("place_order"); compose.onNodeWithTag("payment_declined").assertExists()
        compose.onNodeWithTag("card_number").assertDoesNotExist()
        compose.onNodeWithText("Return to menu").performClick()
        val vault=EncryptedVault(InstrumentationRegistry.getInstrumentation().targetContext,"demo")
        val root=vault.read()
        val saved=root.toString()
        assertFalse(saved.contains("4242424242424242")); assertFalse(saved.contains("Transient"))
        val activity=EncryptedVault(InstrumentationRegistry.getInstrumentation().targetContext,"demo-activity").read()
        assertFalse(activity.toString().contains("4242424242424242"))
        assertFalse(activity.toString().contains("Transient"))
        val history=activity.getJSONObject("checkoutHistory").getJSONArray(root.getString("activeUid"))
        assertEquals(1,history.length())
        assertTrue(history.getJSONObject(0).getJSONArray("foods").getJSONObject(0).getString("food").startsWith("Garden harvest bowl"))
        compose.waitUntil(10_000) {
            EncryptedVault(InstrumentationRegistry.getInstrumentation().targetContext,"cover").read()
                .optJSONObject("accounts")?.optJSONObject(root.getString("activeUid"))
                ?.optJSONObject("cart")?.optInt("bowl")==1
        }
        compose.onNodeWithText("Continue to checkout").performScrollTo().performClick()
        fill("cardholder","Discard on background")
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        waitCover();compose.onNodeWithTag("cardholder").assertDoesNotExist()
        compose.onNodeWithText("Cart").performClick()
        compose.onNodeWithText("Continue to checkout").performScrollTo().performClick()
        compose.onNodeWithTag("cardholder").performScrollTo().assertTextEquals("Name on card","")
    }

    @Test fun coverAccountShowsOnlyDeliveryProfileAndChangesLanguage() {
        register()
        compose.onNodeWithText("Account").performClick()
        compose.onNodeWithTag("cover_profile_name").assertTextEquals("Test Person")
        compose.onNodeWithTag("cover_profile_username").assertTextEquals("@olive_tester")
        compose.onNodeWithTag("cover_sign_out").assertDoesNotExist()
        compose.onNodeWithTag("cover_profile_address").assert(hasText("Edmonton",substring=true))
        compose.onNodeWithText("2000-01-02").assertDoesNotExist()
        compose.onNodeWithText("Test Person Causing Harm").assertDoesNotExist()
        compose.onNodeWithText("moon garden").assertDoesNotExist()
        fill("cover_full_name","Delivery Customer")
        click("cover_language"); compose.onNodeWithText("Français").performClick()
        click("cover_save_settings")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Paramètres du compte enregistrés.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Compte").assertExists()
        compose.onNodeWithText("Découvrir").performClick(); waitCover()
        compose.onNode(hasSetTextAction()).performTextInput("moon garden"); waitFor("unlock_pin")
        enterPin("123456"); waitFor("safe_profile")
        click("safe_profile")
        compose.onNodeWithText("Delivery Customer").assertExists()
        compose.onNodeWithText("Français").assertExists()
    }

    @Test fun passwordChangeRequiresTheCurrentPassword() {
        register();unlock();click("safe_settings")
        fill("current_password","wrong-password");fill("new_password","new-password-456");fill("confirm_new_password","new-password-456");click("change_password")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Unable to change the password.",substring=true).fetchSemanticsNodes().isNotEmpty() }
        fill("current_password","test-password-123");fill("new_password","new-password-456");fill("confirm_new_password","new-password-456");click("change_password")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Password changed.").fetchSemanticsNodes().isNotEmpty() }
        fill("current_device_pin","123456");fill("new_device_pin","654321");fill("confirm_new_device_pin","654321");click("change_device_pin")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Device PIN changed.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("quick_exit").performClick();waitCover();unlock("654321")
    }

}
