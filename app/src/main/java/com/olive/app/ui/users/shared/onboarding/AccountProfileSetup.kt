// All safe-app roles: collects profile details during account setup.
package com.olive.app.ui.users.shared.onboarding

import com.olive.app.ui.shared.*


import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.ui.users.user.account.CoverPicker

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.data.*
import com.olive.app.data.DeliveryAddress
import com.olive.app.R
import com.olive.app.security.InputPolicy
import com.olive.app.ui.covers.food.AddressFields
import com.olive.app.ui.shared.LocalizedIcon as Icon
import java.util.UUID

// USER and SAFE CONTACT: shared profile questions live here.
// USER ONLY: abuse details, situation, trigger, optional safe contact, and cover-app choice are inside !safeContact blocks.
// UI sizing/text: section titles use Material title sizes, helper text uses bodySmall, and all fields use the shared responsive form width.
@Composable
internal fun ProfileSetup(session: SessionViewModel) {
    val uiText = rememberResourceText()
    val safeContact = session.account.role == AccountRole.SAFE_CONTACT
    val existing = session.account.setupProfile
    var fullName by remember(existing) { mutableStateOf(existing?.fullName.orEmpty()) }
    var affected by remember(existing) { mutableStateOf(existing?.affectedNames.orEmpty()) }
    var abuserName by remember(existing) { mutableStateOf(existing?.abuserName.orEmpty()) }
    var dob by remember(existing) { mutableStateOf(existing?.dob.orEmpty()) }
    val language = existing?.language?.ifBlank { "English" } ?: "English"
    var situation by remember(existing) { mutableStateOf(existing?.situation.orEmpty()) }
    var address by remember(existing) { mutableStateOf(existing?.address ?: DeliveryAddress()) }
    var trigger by remember { mutableStateOf("") }
    var contactName by remember { mutableStateOf("") }
    var contactDetails by remember { mutableStateOf("") }
    val contacts =
        remember(existing) { mutableStateListOf<SafeContact>().also { it.addAll(existing?.contacts.orEmpty()) } }
    var coverType by remember(existing) { mutableStateOf(existing?.coverType ?: CoverType.FOOD) }
    val pages = if (safeContact) listOf(
        uiText(R.string.ui_personal_details),
        uiText(R.string.location)
    ) else
        listOf(
            uiText(R.string.ui_personal_details),
            uiText(R.string.location),
            uiText(R.string.ui_safe_entry_cover)
        )
    var page by rememberSaveable(existing?.username, safeContact) { mutableIntStateOf(0) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val validation = ProfileSetupValidation(
        safeContact,
        fullName,
        dob,
        address,
        trigger,
        contactName,
        contactDetails
    )
    val required = validation.requiredFields
    val completed = required.count { it }
    val supportValid = validation.supportValid
    val localCompletion = validation.pageCompletion
    val stages = if (safeContact) 3 else 4
    val progressPage = page + 1
    val credentialCount = if (session.account.creatingAccount) 3 else 2
    val totalRequired = credentialCount + required.size + if (safeContact) 0 else 2
    val progressCompleted = credentialCount + completed
    val pageCompletion = listOf(true) + localCompletion
    // Shared safe app account profile setup: returns the collected contacts.
    fun collectedContacts() =
        (contacts.toList() + if (contactName.isNotBlank() && contactDetails.isNotBlank())
            listOf(
                SafeContact(
                    UUID.randomUUID().toString(),
                    contactName.trim(),
                    contactDetails.trim()
                )
            ) else emptyList()).take(25)

    // Shared safe app account profile setup: Saves profile.
    fun saveProfile() = session.account.saveSetupProfile(
        UserProfile(
            username = session.account.setupUsername,
            fullName = fullName.trim(),
            affectedNames = affected.trim(),
            abuserName = abuserName.trim(),
            dob = dob.trim(),
            language = language.trim(),
            situation = situation.trim(),
            region = address.generalLocation,
            triggerHash = if (safeContact) "" else InputPolicy.triggerHash(trigger),
            contacts = if (safeContact) emptyList() else collectedContacts(),
            address = address,
            coverType = coverType,
            role = session.account.role
        )
    )
    FormColumn(scrollResetKey = page) {
        Heading(uiText(R.string.your_profile), pages[page], focusKey = page)
        OnboardingProgress(progressCompleted, totalRequired, progressPage, stages, pageCompletion)
        AnimatedContent(targetState = pages[page], label = "onboarding page") { current ->
            // Choose what save profile should do for the current value.
            when (current) {
                uiText(R.string.ui_personal_details) -> Column(
                    verticalArrangement = Arrangement.spacedBy(
                        16.dp
                    )
                ) {
                    Field(
                        uiText(R.string.full_name_required),
                        fullName,
                        "full_name",
                        enabled = !session.account.busy,
                        error = if (showErrors && fullName.isBlank()) uiText(R.string.ui_name_required) else null
                    ) {
                        fullName = it.take(120)
                        showErrors = false
                    }
                    BirthdayPicker(
                        dob,
                        "dob",
                        !session.account.busy,
                        error = if (showErrors && !InputPolicy.validDob(dob)) uiText(R.string.ui_birthday_required) else null
                    ) {
                        dob = it
                        showErrors = false
                    }
                    if (!safeContact) {
                        HorizontalDivider()
                        Field(
                            uiText(R.string.names_of_people_experiencing_abuse_optional),
                            affected,
                            "affected_names",
                            enabled = !session.account.busy
                        ) { affected = it.take(500) }
                        Field(
                            uiText(R.string.abuser_s_name_optional),
                            abuserName,
                            "abuser_name",
                            enabled = !session.account.busy
                        ) { abuserName = it.take(120) }
                        Field(
                            uiText(R.string.ui_brief_description_optional),
                            situation,
                            "situation",
                            multiline = true,
                            enabled = !session.account.busy
                        ) { situation = it.take(2000) }
                    }
                }

                uiText(R.string.location) -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        uiText(R.string.ui_only_your_city_is_required),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    AddressFields(
                        address,
                        cityOnlyRequired = true,
                        cityError = if (showErrors && !address.locationValid) uiText(R.string.ui_city_required) else null
                    ) {
                        address = it
                        showErrors = false
                    }
                }

                uiText(R.string.ui_safe_entry_cover) -> Column(
                    verticalArrangement = Arrangement.spacedBy(
                        14.dp
                    )
                ) {
                    Field(
                        uiText(R.string.trigger_word_or_phrase),
                        trigger,
                        "trigger",
                        secret = true,
                        showSecretToggle = true,
                        enabled = !session.account.busy,
                        error = if (showErrors && !InputPolicy.validTrigger(trigger)) uiText(R.string.ui_invalid_trigger) else null
                    ) {
                        trigger = it.take(64)
                        showErrors = false
                    }
                    RequirementBullet(
                        uiText(R.string.ui_3_64_characters),
                        InputPolicy.validTrigger(trigger),
                        trigger.isNotEmpty()
                    )
                    HorizontalDivider()
                    CoverPicker(coverType, !session.account.busy) { coverType = it }
                    Text(
                        uiText(R.string.ui_name_and_icon_update_after_leaving_the_app),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    HorizontalDivider()
                    Text(
                        uiText(R.string.ui_safe_contacts_optional),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() })
                    Text(
                        uiText(R.string.ui_add_one_or_more_trusted_people_olive_never_contacts_them_automati),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    contacts.forEach { contact ->
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        contact.name,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                    )
                                    Text(
                                        contact.details,
                                        color = Muted,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                IconButton(
                                    onClick = { contacts.remove(contact) },
                                    enabled = !session.account.busy
                                ) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        uiText(R.string.ui_remove_safe_contact)
                                    )
                                }
                            }
                        }
                    }
                    Field(
                        uiText(R.string.ui_contact_name_optional),
                        contactName,
                        "setup_contact_name",
                        enabled = !session.account.busy && contacts.size < 25,
                        error = if (showErrors && !supportValid) uiText(R.string.ui_complete_both_fields) else null
                    ) {
                        contactName = it.take(120)
                        showErrors = false
                    }
                    Field(
                        uiText(R.string.ui_phone_or_email_optional),
                        contactDetails,
                        "setup_contact_details",
                        enabled = !session.account.busy && contacts.size < 25,
                        error = if (showErrors && !supportValid) uiText(R.string.ui_complete_both_fields) else null
                    ) {
                        contactDetails = it.take(200)
                        showErrors = false
                    }
                    OutlinedButton(
                        onClick = {
                            contacts += SafeContact(
                                UUID.randomUUID().toString(),
                                contactName.trim(),
                                contactDetails.trim()
                            )
                            contactName = ""
                            contactDetails = ""
                            showErrors = false
                        },
                        enabled = !session.account.busy && contactName.isNotBlank() && contactDetails.isNotBlank() && contacts.size < 25,
                        modifier = Modifier.fillMaxWidth().testTag("add_onboarding_contact")
                    ) {
                        Icon(Icons.Outlined.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.add_another_contact)
                        )
                    }
                }
            }
        }
        Status(session)
        val finalProfilePage = page == pages.lastIndex
        val last = safeContact && finalProfilePage
        OnboardingNavigation(
            back = {
                showErrors = false
                if (page > 0) page-- else session.navigation.back()
            },
            next = {
                showErrors = true
                if (validation.isPageValid(page)) {
                    showErrors = false
                    if (finalProfilePage) saveProfile() else page++
                }
            },
            last = last,
            nextTag = if (page == pages.lastIndex) "profile_submit" else "onboarding_next",
            enabled = !session.account.busy,
            nextLabel = if (!safeContact && page == pages.lastIndex) uiText(R.string.next) else null
        )
    }
}

// Step validation is shared by progress calculation and the Next/Save actions.
internal class ProfileSetupValidation(
    private val safeContact: Boolean,
    private val fullName: String,
    private val dob: String,
    private val address: DeliveryAddress,
    private val trigger: String,
    private val contactName: String,
    private val contactDetails: String,
) {
    val personalValid get() = fullName.isNotBlank() && InputPolicy.validDob(dob)
    val supportValid get() = contactName.isBlank() == contactDetails.isBlank()
    val requiredFields
        get() = listOf(fullName.isNotBlank(), InputPolicy.validDob(dob), address.locationValid) +
                if (safeContact) emptyList() else listOf(InputPolicy.validTrigger(trigger))
    val pageCompletion
        get() = if (safeContact) listOf(personalValid, address.locationValid) else
            listOf(
                personalValid,
                address.locationValid,
                InputPolicy.validTrigger(trigger) && supportValid
            )

    // Validate the step index, independently of the translated heading displayed on that page.
    fun isPageValid(page: Int) = when (page) {
        0 -> personalValid
        1 -> address.locationValid
        2 -> InputPolicy.validTrigger(trigger) && supportValid
        else -> false
    }
}
