// Tests private-screen previews and task identity for safe-app users.
package com.olive.app

import com.olive.app.ui.shared.*

import com.olive.app.data.AccountRole
import com.olive.app.data.CoverType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OliveActivityPrivacyTest {
    @Test
    fun recentPreviewOnlyBlanksWhenProtectionAndPrivateContentAreBothActive() {
        assertTrue(shouldBlankRecentPreview(protectionEnabled = true, sensitive = true))
        assertFalse(shouldBlankRecentPreview(protectionEnabled = false, sensitive = true))
        assertFalse(shouldBlankRecentPreview(protectionEnabled = true, sensitive = false))
    }

    @Test
    fun taskIdentityOnlyUpdatesWhenCoverOrRoleChanges() {
        val applied = CoverType.FOOD to AccountRole.USER
        assertFalse(shouldApplyTaskIdentity(applied, CoverType.FOOD, AccountRole.USER))
        assertTrue(shouldApplyTaskIdentity(applied, CoverType.NEWS, AccountRole.USER))
        assertTrue(shouldApplyTaskIdentity(applied, CoverType.FOOD, AccountRole.ADMIN))
    }
}
