package com.olive.app.ui.session

import com.olive.app.data.account.PrivateEntry
import com.olive.app.support.SessionRuntimeFixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionHistoryRefreshTest {
    @Test
    fun refreshingLoadedNotesKeepsTheirContentInsteadOfReturningToSkeletons() = runBlocking<Unit> {
        SessionRuntimeFixture().use { fixture ->
            val history = fixture.runtime.history
            val note = PrivateEntry(title = "Synthetic note", body = "Saved content")
            fixture.runtime.store.savePrivateEntry("owner", note)
            history.refreshHistory("owner")
            fixture.await { history.loadingUid == null && fixture.runtime.state.privateEntries.any { it.id == note.id } }
            history.refreshHistory("owner")
            assertNull(history.loadingUid)
            assertEquals(note.id, fixture.runtime.state.privateEntries.single().id)
        }
    }
}
