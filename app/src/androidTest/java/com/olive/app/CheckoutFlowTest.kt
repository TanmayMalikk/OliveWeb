package com.olive.app

import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.security.EncryptedVault

import org.junit.Assert.*
import org.junit.Test

class CheckoutFlowTest : OliveUiTestBase() {
    @Test fun checkoutDeclinesDiscardsPaymentAndBackgroundClearsForm() {
        register(username="checkout_tester")
        compose.onNodeWithText("Garden harvest bowl").performClick()
        compose.onNodeWithText("Add to cart").performScrollTo().performClick()
        compose.onNodeWithText("Cart").performClick()
        compose.onNodeWithText("Continue to checkout").performScrollTo().performClick()
        fill("checkout_recipient","Transient Recipient"); fill("cardholder","Transient Cardholder")
        fill("card_number","4242424242424242"); fill("card_expiry","12/30"); fill("card_cvc","123")
        click("use_shipping_address");fill("billing_address","Transient Billing")
        click("place_order"); waitFor("payment_declined")
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
}
