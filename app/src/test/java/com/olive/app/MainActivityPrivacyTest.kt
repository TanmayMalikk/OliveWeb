package com.olive.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityPrivacyTest {
    @Test fun recentPreviewOnlyBlanksWhenProtectionAndPrivateContentAreBothActive() {
        assertTrue(shouldBlankRecentPreview(protectionEnabled=true,sensitive=true))
        assertFalse(shouldBlankRecentPreview(protectionEnabled=false,sensitive=true))
        assertFalse(shouldBlankRecentPreview(protectionEnabled=true,sensitive=false))
    }
}
