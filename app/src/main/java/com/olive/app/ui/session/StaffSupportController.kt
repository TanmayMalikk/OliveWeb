// Users and staff: manages support tickets separately from safety-plan consultations.
package com.olive.app.ui.session

import androidx.compose.runtime.*
import com.olive.app.R
import com.olive.app.data.AccountRole
import com.olive.app.data.SupportRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

internal class StaffSupportController(private val runtime: SessionRuntime) {
    private val repository by lazy { SupportRepository() }

    var requested by mutableStateOf(false)
        private set
    var staffUid by mutableStateOf("")
        private set
    var conversationId by mutableStateOf("")
        private set
    var saving by mutableIntStateOf(0)
        private set
    var error by mutableStateOf<Int?>(null)
        private set

    fun requestHelp() = updateRequest(cancel = false)

    fun cancelRequest() = updateRequest(cancel = true)

    // Keep the current account's ticket on screen; responses from a previous account are ignored.
    private fun updateRequest(cancel: Boolean) {
        if (runtime.localMode) {
            error = R.string.safety_account_required
            return
        }
        if (runtime.state.accountRole != AccountRole.USER) return
        val actor = runtime.coverAccountId ?: return
        saving++
        error = null
        runtime.scope.launch {
            try {
                val ticket = withTimeout(35.seconds) {
                    withContext(Dispatchers.IO) {
                        if (cancel) repository.cancelRequest() else repository.requestHelp()
                    }
                }
                if (runtime.coverAccountId == actor) {
                    requested = ticket.requested
                    staffUid = ticket.staffUid
                    conversationId = ticket.conversationId
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (runtime.coverAccountId == actor) error = R.string.support_request_error
            } finally {
                if (runtime.coverAccountId == actor) saving = (saving - 1).coerceAtLeast(0)
            }
        }
    }

    fun requestAdmin(conversationId: String) {
        if (runtime.localMode || runtime.state.accountRole != AccountRole.STAFF || conversationId.isBlank()) return
        saving++
        error = null
        runtime.scope.launch {
            try {
                withContext(Dispatchers.IO) { repository.requestAdmin(conversationId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = R.string.support_admin_request_error
            } finally {
                saving = (saving - 1).coerceAtLeast(0)
            }
        }
    }

    fun close(conversationId: String) {
        if (runtime.localMode || runtime.state.accountRole != AccountRole.STAFF || conversationId.isBlank() || saving > 0) return
        val actor = runtime.coverAccountId ?: return
        saving++
        error = null
        runtime.scope.launch {
            try {
                withContext(Dispatchers.IO) { repository.close(conversationId) }
                if (runtime.coverAccountId == actor && runtime.state.activeConversation?.id == conversationId) {
                    runtime.conversationActions.closeConversation()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (runtime.coverAccountId == actor) error = R.string.support_close_error
            } finally {
                if (runtime.coverAccountId == actor) saving = (saving - 1).coerceAtLeast(0)
            }
        }
    }
}
