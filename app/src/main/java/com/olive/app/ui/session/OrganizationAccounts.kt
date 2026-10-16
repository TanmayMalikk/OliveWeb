package com.olive.app.ui.session

import com.olive.app.data.*
import com.olive.app.security.*
import com.olive.app.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

// Owns staff and administrator account workflows.
internal class OrganizationAccounts(private val runtime:SessionRuntime) {
    fun saveOrganizationProfile(fullName:String,dob:String,email:String,phone:String) {
        with(runtime) {
            with(state) {
                val identity=pendingIdentity ?: return
                val repository=adminRepository ?: return
                if(fullName.isBlank() || !InputPolicy.validDob(dob) || !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
                    message="Full name, a valid date of birth, and a real email are required.";return
                }
                operations.runOperation("Unable to finish the organization profile. Check the email and try again.") { token ->
                    val updated=repository.completeOrganizationProfile(fullName.trim(),dob.trim(),email.trim(),phone.trim())
                    val saved=withContext(Dispatchers.IO) { store.enrollWithoutPin(identity,updated) }
                    if(epoch==token) {
                        enrollment=saved;profile=updated;pendingProfile=updated;accountRole=updated.role
                        coverUsername=updated.username;coverFullName=updated.fullName;language=updated.language
                        screenHistory.clear();screen=SessionScreen.Portal
                        if(updated.role==AccountRole.ADMIN) queueAdminRefresh()
                    }
                }
            }
        }
    }

    fun updateOrganizationProfile(fullName:String,dob:String,email:String,phone:String) {
        with(runtime) {
            with(state) {
                val device=enrollment ?: return
                val repository=adminRepository ?: return
                if(screen!=SessionScreen.Portal || accountRole !in setOf(AccountRole.STAFF,AccountRole.ADMIN) || busy) return
                if(fullName.isBlank() || !InputPolicy.validDob(dob) || !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
                    message="Full name, a valid date of birth, and a real email are required.";return
                }
                operations.runOperation("Unable to update your Olive Tree profile.") { token ->
                    val updated=repository.completeOrganizationProfile(fullName.trim(),dob.trim(),email.trim(),phone.trim())
                    withContext(Dispatchers.IO) { store.cacheProfile(device.uid,updated) }
                    if(epoch==token && screen==SessionScreen.Portal) {
                        profile=updated;pendingProfile=updated;coverFullName=updated.fullName;message="Profile updated."
                    }
                }
            }
        }
    }

    fun refreshAdminAccounts() {
        with(runtime) {
            with(state) {
                val repository=adminRepository ?: return
                if(accountRole!=AccountRole.ADMIN || busy) return
                operations.runOperation("Unable to load organization accounts.") { token ->
                    val (counts,rows)=repository.accounts()
                    if(epoch==token && screen==SessionScreen.Portal) { adminCounts=counts;adminAccounts=rows }
                }
            }
        }
    }

    fun queueAdminRefresh() {
        with(runtime) {
            with(state) {
                scope.launch {
                    while(busy) delay(50.milliseconds)
                    if(screen==SessionScreen.Portal && accountRole==AccountRole.ADMIN) refreshAdminAccounts()
                }
            }
        }
    }

    fun createManagedAccount(value:AdminAccountDraft) {
        with(runtime) {
            with(state) {
                val repository=adminRepository ?: return
                if(accountRole!=AccountRole.ADMIN || busy) return
                if(!InputPolicy.validUsername(value.username)) { message="Use 8–24 letters, numbers or underscores for the username.";return }
                if(!InputPolicy.validPassword(value.password)) { message="Use 8–24 characters with a letter, number and special character.";return }
                operations.runOperation("Unable to create the account. The username or email may already be in use.") { token ->
                    repository.create(value)
                    val (counts,rows)=repository.accounts()
                    if(epoch==token && screen==SessionScreen.Portal) { adminCounts=counts;adminAccounts=rows;message="${value.role.title} account created." }
                }
            }
        }
    }

    fun deleteManagedAccount(value:AdminAccountSummary) {
        with(runtime) {
            with(state) {
                val repository=adminRepository ?: return
                if(accountRole!=AccountRole.ADMIN || busy || value.protected) return
                operations.runOperation("Unable to delete this account.") { token ->
                    repository.delete(value.uid)
                    val (counts,rows)=repository.accounts()
                    if(epoch==token && screen==SessionScreen.Portal) { adminCounts=counts;adminAccounts=rows;message="${value.username} was deleted." }
                }
            }
        }
    }
}
