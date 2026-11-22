// All safe-app roles: collects profile details during account setup.
package com.olive.app.ui.users.shared.onboarding

import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveTextButton as TextButton
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
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.SafeContact
import com.olive.app.data.account.SafetyPlanIntake
import com.olive.app.data.account.UserProfile
import com.olive.app.security.InputPolicy
import com.olive.app.ui.shared.AddressFields
import com.olive.app.ui.shared.LocalizedIcon as Icon
import java.util.UUID

// USER and SAFE CONTACT: shared profile questions live here.
private data class AffectedPersonDraft(val name: String = "", val age: String = "")

/**
 * Renders the affected people fields composable.
 *
 * @param value Input value to store, parse, or format.
 * @param enabled Whether this feature or control is currently enabled.
 * @param onChange Callback invoked when the value or observed state changes.
 */
@Composable
private fun AffectedPeopleFields(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val uiText = rememberResourceText()
    val ageSuffix = remember { Regex("""^(.*) \(([0-9]{1,3})\)$""") }
    var people by remember {
        mutableStateOf(value.lines().filter(String::isNotBlank).map { line ->
            ageSuffix.matchEntire(line)?.let { AffectedPersonDraft(it.groupValues[1], it.groupValues[2]) }
                ?: AffectedPersonDraft(line)
        }.ifEmpty { listOf(AffectedPersonDraft()) })
    }
    /**
     * Updates the affected person draft.
     *
     * @param rows Collection of rows values to process.
     */
    fun update(rows: List<AffectedPersonDraft>) {
        val saved = rows.filter { it.name.isNotBlank() }.joinToString("\n") {
            it.name.trim() + if (it.age.isNotEmpty()) " (${it.age})" else ""
        }
        if (saved.length <= 500) {
            people = rows
            onChange(saved)
        }
    }
    Text(uiText(R.string.names_of_people_experiencing_abuse_optional), style = MaterialTheme.typography.titleMedium)
    people.forEachIndexed { index, person ->
        Field(uiText(R.string.feedback_person_name, index + 1), person.name, "affected_name_$index", enabled = enabled) { name ->
            update(people.toMutableList().also { it[index] = person.copy(name = name) })
        }
        Field(uiText(R.string.feedback_person_age, index + 1), person.age, "affected_age_$index", enabled = enabled) { age ->
            if (age.isEmpty() || age.matches(Regex("[0-9]{1,3}")) && age.toInt() <= 130) {
                update(people.toMutableList().also { it[index] = person.copy(age = age) })
            }
        }
        if (people.size > 1) TextButton(onClick = { update(people.filterIndexed { row, _ -> row != index }) }, enabled = enabled) {
            Text(uiText(R.string.feedback_remove_person, index + 1))
        }
    }
    OutlinedButton(
        onClick = { update(people + AffectedPersonDraft()) },
        enabled = enabled && people.last().name.isNotBlank() && value.length < 500,
    ) { Text(uiText(R.string.feedback_add_person)) }
}

/**
 * USER ONLY: abuse details, situation, trigger, optional safe contact, and cover-app choice are inside !safeContact blocks. UI sizing/text: section titles use Material title sizes, helper text uses bodySmall, and all fields use the shared responsive form width.
 *
 * @param session Shared session view model owning application state and actions.
 */
@Composable
internal fun ProfileSetup(session: SessionViewModel) {
    val uiText = rememberResourceText()
    val safeContact = session.account.role == AccountRole.SAFE_CONTACT
    val existing = session.account.setupProfile
    var fullName by remember(existing) { mutableStateOf(existing?.fullName.orEmpty()) }
    var affected by remember(existing) { mutableStateOf(existing?.affectedNames.orEmpty()) }
    var abuserName by remember(existing) { mutableStateOf(existing?.abuserName.orEmpty()) }
    var birthday by rememberBirthdayInput(existing?.dob.orEmpty())
    val dob = birthday
    val language = existing?.language?.ifBlank { "English" } ?: "English"
    var situation by remember(existing) { mutableStateOf(existing?.situation.orEmpty()) }
    var address by remember(existing) { mutableStateOf(existing?.address ?: DeliveryAddress()) }
    var trigger by remember { mutableStateOf("") }
    var contactName by remember { mutableStateOf("") }
    var contactDetails by remember { mutableStateOf("") }
    val contacts =
        remember(existing) { mutableStateListOf<SafeContact>().also { it.addAll(existing?.contacts.orEmpty()) } }
    var coverType by remember(existing) { mutableStateOf(existing?.coverType ?: CoverType.FITNESS) }
    var safetyIntake by remember(existing) { mutableStateOf(existing?.safetyIntake ?: SafetyPlanIntake()) }
    val pages = if (safeContact) listOf(
        uiText(R.string.ui_personal_details),
        uiText(R.string.location),
        uiText(R.string.friend_invite_code)
    ) else
        listOf(
            uiText(R.string.ui_personal_details),
            uiText(R.string.location),
            uiText(R.string.ui_safe_entry_cover),
            uiText(R.string.friend_invite_code),
            uiText(R.string.onboarding_safety_questionnaire),
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
    val stages = if (safeContact) 4 else 6
    val progressPage = page + 1
    val credentialCount = if (session.account.creatingAccount) 3 else 2
    val totalRequired = credentialCount + required.size + if (safeContact) 0 else 2
    val progressCompleted = credentialCount + completed
    val pageCompletion = if (safeContact) listOf(true) + localCompletion + true
    else listOf(true) + localCompletion + listOf(true, true)
    /**
     * Gives account storage the collected contacts it needs.
     *
     * @return The resulting collected contacts.
     */
    fun collectedContacts() =
        (contacts.toList() + if (contactName.isNotBlank() && contactDetails.isNotBlank())
            listOf(
                SafeContact(
                    UUID.randomUUID().toString(),
                    contactName.trim(),
                    contactDetails.trim()
                )
            ) else emptyList()).take(25)

    // Checks the onboarding fields and saves the completed account profile.
    val saveScope = rememberCoroutineScope()
    var preparingProfile by remember { mutableStateOf(false) }
    /**
     * Saves profile.
     */
    fun saveProfile() {
        if (preparingProfile) return
        val draftTrigger = trigger
        val draft = UserProfile(
            username = session.account.setupUsername,
            fullName = fullName.trim(),
            affectedNames = affected.trim(),
            abuserName = abuserName.trim(),
            dob = dob.trim(),
            language = language.trim(),
            situation = situation.trim(),
            region = address.generalLocation,
            triggerHash = "",
            contacts = if (safeContact) emptyList() else collectedContacts(),
            address = address,
            coverType = coverType,
            role = session.account.role,
            safetyIntake = if (safeContact) SafetyPlanIntake() else safetyIntake.copy(completed = true),
        )
        preparingProfile = true
        saveScope.launch {
            try {
                val verifier = if (safeContact) "" else withContext(Dispatchers.Default) {
                    InputPolicy.triggerHash(draftTrigger)
                }
                session.account.saveSetupProfile(draft.copy(triggerHash = verifier))
            } finally {
                preparingProfile = false
            }
        }
    }
    FormColumn(scrollResetKey = page) {
        Heading(uiText(R.string.your_profile), pages[page], focusKey = page)
        OnboardingProgress(progressCompleted, totalRequired, progressPage, stages, pageCompletion)
        AnimatedContent(targetState = pages[page], label = "onboarding page") { current ->
            // Continues setup after the profile saves, or shows the save error.
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
                        birthday, "dob", !session.account.busy, showErrors,
                    ) { birthday = it }
                    if (!safeContact) {
                        HorizontalDivider()
                        AffectedPeopleFields(affected, !session.account.busy) { affected = it }
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
                        Text(uiText(R.string.feedback_situation_hint), style = MaterialTheme.typography.bodyMedium)
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

                uiText(R.string.onboarding_safety_questionnaire) ->
                    SafetyPlanQuestionnaireForm(safetyIntake, !session.account.busy) { safetyIntake = it }
            }
        }
        if (pages[page] == uiText(R.string.friend_invite_code)) {
            com.olive.app.ui.users.shared.messaging.InviteCodeEntry(messages = session.messages, enabled = !session.localMode,
                accountUid = session.runtime.pendingIdentity?.uid)
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
                if (finalProfilePage || validation.isPageValid(page)) {
                    showErrors = false
                    if (finalProfilePage) saveProfile() else page++
                }
            },
            last = last,
            nextTag = if (page == pages.lastIndex) "profile_submit" else "onboarding_next",
            enabled = !session.account.busy && !preparingProfile,
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

    /**
     * Validate the step index, independently of the translated heading displayed on that page.
     *
     * @param page Page supplied to [isPageValid].
     * @return `true` if is page valid, or `false` otherwise.
     */
    fun isPageValid(page: Int) = when (page) {
        0 -> personalValid
        1 -> address.locationValid
        2 -> InputPolicy.validTrigger(trigger) && supportValid
        3 -> !safeContact
        else -> false
    }
}
