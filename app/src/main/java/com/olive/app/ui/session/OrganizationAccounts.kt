package com.olive.app.ui.session
import com.olive.app.R

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
                    message=runtime.application.getString(R.string.ui_full_name_a_valid_date_of_birth_and_a_real_email_are_required);return
                }
                operations.runOperation(runtime.application.getString(R.string.ui_unable_to_finish_the_organization_profile_check_the_email_and_try)) { token ->
                    val updated=repository.completeOrganizationProfile(fullName.trim(),dob.trim(),email.trim(),phone.trim())
                    val saved=withContext(Dispatchers.IO) { store.enrollWithoutPin(identity,updated) }
                    if(epoch==token) {
                        enrollment=saved;profile=updated;pendingProfile=updated;accountRole=updated.role
                        coverUsername=updated.username;coverDob=updated.dob;coverFullName=updated.fullName;language=updated.language
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
                    message=runtime.application.getString(R.string.ui_full_name_a_valid_date_of_birth_and_a_real_email_are_required);return
                }
                operations.runOperation(runtime.application.getString(R.string.ui_unable_to_update_your_olive_tree_profile)) { token ->
                    val updated=repository.completeOrganizationProfile(fullName.trim(),dob.trim(),email.trim(),phone.trim())
                    withContext(Dispatchers.IO) { store.cacheProfile(device.uid,updated) }
                    if(epoch==token && screen==SessionScreen.Portal) {
                        profile=updated;pendingProfile=updated;coverDob=updated.dob;coverFullName=updated.fullName;message=runtime.application.getString(R.string.ui_profile_updated)
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
                operations.runOperation(runtime.application.getString(R.string.ui_unable_to_load_organization_accounts)) { token ->
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
                if(!InputPolicy.validUsername(value.username)) { message=runtime.application.getString(R.string.ui_use_8_24_letters_numbers_or_underscores_for_the_username);return }
                if(!InputPolicy.passwordExcludesUsername(value.password,value.username)) { message=runtime.application.getString(R.string.password_excludes_username); return }
                if(!InputPolicy.validAccountPassword(value.password,value.username)) { message=runtime.application.getString(R.string.ui_use_8_24_characters_with_a_letter_number_and_special_character);return }
                operations.runOperation(runtime.application.getString(R.string.ui_unable_to_create_the_account_the_username_or_email_may_already_be)) { token ->
                    repository.create(value)
                    val (counts,rows)=repository.accounts()
                    if(epoch==token && screen==SessionScreen.Portal) { adminCounts=counts;adminAccounts=rows;message=runtime.application.getString(R.string.ui_value_account_created , value.role.localizedTitle(ResourceText(runtime.application.resources))) }
                }
            }
        }
    }

    fun deleteManagedAccount(value:AdminAccountSummary) {
        with(runtime) {
            with(state) {
                val repository=adminRepository ?: return
                if(accountRole!=AccountRole.ADMIN || busy || value.protected) return
                operations.runOperation(runtime.application.getString(R.string.ui_unable_to_delete_this_account)) { token ->
                    repository.delete(value.uid)
                    val (counts,rows)=repository.accounts()
                    if(epoch==token && screen==SessionScreen.Portal) { adminCounts=counts;adminAccounts=rows;message=runtime.application.getString(R.string.ui_value_was_deleted , value.username) }
                }
            }
        }
    }
}
