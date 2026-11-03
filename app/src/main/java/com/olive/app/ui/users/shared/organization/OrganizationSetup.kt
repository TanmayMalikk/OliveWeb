// All safe-app roles: collects staff and admin setup details.
package com.olive.app.ui.users.shared.organization

import com.olive.app.ui.shared.*


import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.ui.users.shared.onboarding.*

import android.util.Patterns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.olive.app.R
import com.olive.app.security.InputPolicy

// Staff and administrators complete organization contact details on first login.
@Composable
internal fun OrganizationProfileSetup(session: SessionViewModel) {
    val uiText = rememberResourceText()
    val existing = session.account.setupProfile
    var fullName by remember(existing) { mutableStateOf(existing?.fullName.orEmpty()) }
    var dob by remember(existing) { mutableStateOf(existing?.dob.orEmpty()) }
    var email by remember(existing) { mutableStateOf(existing?.email.orEmpty()) }
    var phone by remember(existing) { mutableStateOf(existing?.phone.orEmpty()) }
    var page by rememberSaveable(existing?.username) { mutableIntStateOf(0) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val pages = listOf(uiText(R.string.ui_personal_details), uiText(R.string.ui_contact_details))
    val emailValid = Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val required = listOf(fullName.isNotBlank(), InputPolicy.validDob(dob), emailValid)
    // Enables the next button only when every field on the current setup page is valid.
    fun pageValid() =
        if (page == 0) fullName.isNotBlank() && InputPolicy.validDob(dob) else emailValid
    FormColumn {
        Heading(
            uiText(
                R.string.ui_complete_your_value_profile,
                session.account.role.localizedTitle(uiText)
            ), pages[page], uiText(R.string.olive_tree)
        )
        OnboardingProgress(
            required.count { it }, required.size, page, pages.size,
            listOf(fullName.isNotBlank() && InputPolicy.validDob(dob), emailValid)
        )
        // Only continue when page is 0.
        if (page == 0) {
            Field(
                uiText(R.string.full_name_required),
                fullName,
                "organization_full_name",
                enabled = !session.account.busy,
                error = if (showErrors && fullName.isBlank()) uiText(R.string.ui_name_required) else null
            ) {
                fullName = it.take(120)
                showErrors = false
            }
            BirthdayPicker(
                dob, "organization_dob", !session.account.busy,
                error = if (showErrors && !InputPolicy.validDob(dob)) uiText(R.string.ui_birthday_required) else null
            ) {
                dob = it
                showErrors = false
            }
        } else {
            Field(
                uiText(R.string.ui_email_required),
                email,
                "organization_email",
                enabled = !session.account.busy,
                error = if (showErrors && !emailValid) uiText(R.string.ui_invalid_email) else null
            ) {
                email = it.take(254)
                showErrors = false
            }
            Field(
                uiText(R.string.ui_phone_optional),
                phone,
                "organization_phone",
                enabled = !session.account.busy
            ) { phone = it.take(40) }
        }
        Status(session)
        OnboardingNavigation(
            back = {
                showErrors = false
                if (page > 0) page-- else session.navigation.back()
            },
            next = {
                showErrors = true
                if (pageValid()) {
                    showErrors = false
                    if (page == pages.lastIndex) session.saveOrganizationProfile(
                        fullName,
                        dob,
                        email,
                        phone
                    ) else page++
                }
            },
            last = page == pages.lastIndex,
            nextTag = if (page == pages.lastIndex) "organization_profile_submit" else "onboarding_next",
            enabled = !session.account.busy
        )
    }
}
