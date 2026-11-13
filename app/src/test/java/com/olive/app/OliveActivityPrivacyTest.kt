// Tests private-screen previews and task identity for safe-app users.
package com.olive.app

import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.CoverType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OliveActivityPrivacyTest {
    // Inputs: the values shown below. Expected: recent preview only blanks when protection and private content are both active.
    @Test
    fun recentPreviewOnlyBlanksWhenProtectionAndPrivateContentAreBothActive() {
        assertTrue(shouldBlankRecentPreview(protectionEnabled = true, sensitive = true))
        assertFalse(shouldBlankRecentPreview(protectionEnabled = false, sensitive = true))
        assertFalse(shouldBlankRecentPreview(protectionEnabled = true, sensitive = false))
    }

    // Inputs: the values shown below. Expected: task identity only updates when cover or role changes.
    @Test
    fun taskIdentityOnlyUpdatesWhenCoverOrRoleChanges() {
        val applied = CoverType.NEWS to AccountRole.USER
        assertFalse(shouldApplyTaskIdentity(applied, CoverType.NEWS, AccountRole.USER))
        assertTrue(shouldApplyTaskIdentity(applied, CoverType.FITNESS, AccountRole.USER))
        assertTrue(shouldApplyTaskIdentity(applied, CoverType.NEWS, AccountRole.ADMIN))
    }
}
