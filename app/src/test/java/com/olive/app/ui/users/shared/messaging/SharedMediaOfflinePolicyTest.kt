package com.olive.app.ui.users.shared.messaging

import com.olive.app.data.account.AccountRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedMediaOfflinePolicyTest {
    @Test
    fun `users and safe contacts retain shared media offline controls`() {
        assertTrue(canManageSharedMediaOfflineCopy(AccountRole.USER))
        assertTrue(canManageSharedMediaOfflineCopy(AccountRole.SAFE_CONTACT))
    }

    @Test
    fun `staff and admins cannot manage shared media offline copies`() {
        assertFalse(canManageSharedMediaOfflineCopy(AccountRole.STAFF))
        assertFalse(canManageSharedMediaOfflineCopy(AccountRole.ADMIN))
    }
}
