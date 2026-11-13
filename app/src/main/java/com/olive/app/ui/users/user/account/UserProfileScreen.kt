// Safe-app users: shows profile details and cover selection.
package com.olive.app.ui.users.user.account


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.SafeContact
import com.olive.app.data.account.UserProfile
import com.olive.app.ui.shared.LightSage
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.localizedTitle
import com.olive.app.ui.shared.rememberResourceText
import com.olive.app.ui.users.shared.navigation.Field
import com.olive.app.ui.users.shared.navigation.FormColumn
import com.olive.app.ui.users.shared.navigation.Heading
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import com.olive.app.ui.users.shared.navigation.Status
import com.olive.app.ui.users.shared.safetyplan.TrustedContactLinkPicker
import java.util.UUID
import com.olive.app.ui.shared.LocalizedIcon as Icon

// UI sizing/text: this back action keeps Material button sizing and localized text rather than a custom fixed size.
@Composable
internal fun SafeBack(session: SessionViewModel) {
    TextButton(
        onClick = session.navigation::back,
        modifier = Modifier.testTag("safe_home")
    ) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
        Text(stringResource(R.string.back))
    }
}

// Trusted people can be added or cleaned up here, but the app never auto-calls or auto-messages them.
// UI sizing/text: contact cards use 16dp padding and Material title text; edits stay in shared full-width fields.
@Composable
internal fun ContactEditor(session: SessionViewModel, profile: UserProfile) {
    val uiText = rememberResourceText()
    var editing by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var linkedUid by remember { mutableStateOf("") }
    FormColumn {
        IconButton(onClick = session.navigation::back, modifier = Modifier.testTag("trusted_contacts_back")) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                stringResource(R.string.ui_previous_safe_page)
            )
        }
        Heading(
            uiText(R.string.safe_contacts),
            uiText(R.string.save_trusted_people_here_olive_does_not_send_messages_or_make_calls)
        )
        val safeToStay = profile.contacts.filter { it.safeToStay }
        if (safeToStay.isNotEmpty()) Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = LightSage)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.safe_to_stay),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
                safeToStay.forEach { Text("• ${it.name}") }
            }
        }
        profile.contacts.forEach { contact ->
            val safeStayDescription = stringResource(R.string.safe_to_stay_for, contact.name)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(contact.name, style = MaterialTheme.typography.titleMedium)
                            Text(contact.details)
                            if (contact.safeToStay) Text(
                                stringResource(R.string.safe_to_stay), color = OliveGreen,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        Switch(
                            checked = contact.safeToStay,
                            onCheckedChange = { enabled ->
                                session.saveContacts(profile.contacts.map {
                                    if (it.id == contact.id) it.copy(
                                        safeToStay = enabled
                                    ) else it
                                })
                            },
                            enabled = !session.busy,
                            modifier = Modifier.testTag("safe_to_stay_${contact.id}")
                                .semantics { contentDescription = safeStayDescription })
                    }
                    Text(
                        stringResource(R.string.safe_to_stay_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted
                    )
                    TrustedContactLinkPicker(
                        session.messages.friends, contact.linkedUid,
                        profile.contacts.filterNot { it.id == contact.id }
                            .mapNotNull { it.linkedUid.takeIf(String::isNotBlank) }.toSet(),
                        !session.busy, "trusted_link_${contact.id}"
                    ) { selectedUid ->
                        session.saveContacts(profile.contacts.map {
                            if (it.id == contact.id) it.copy(
                                linkedUid = selectedUid
                            ) else it
                        })
                        if (editing == contact.id) linkedUid = selectedUid
                    }
                    Row {
                        TextButton(onClick = {
                            session.recordSettingsInteraction(
                                "Olive Branch",
                                "Opened contact editor"
                            )
                            editing = contact.id
                            name = contact.name
                            details =
                                contact.details
                            linkedUid = contact.linkedUid
                        }, enabled = !session.busy) { Text(stringResource(R.string.edit)) }
                        TextButton(onClick = {
                            session.recordSettingsInteraction(
                                "Olive Branch",
                                "Selected remove contact"
                            )
                            session.saveContacts(profile.contacts.filterNot { it.id == contact.id })
                            // Clear the form when the removed contact was being edited.
                            if (editing == contact.id) {
                                editing = null
                                name = ""
                                details = ""
                                linkedUid = ""
                            }
                        }, enabled = !session.busy) { Text(stringResource(R.string.remove)) }
                    }
                }
            }
        }
        if (profile.contacts.isEmpty()) Text(
            stringResource(R.string.no_contacts_yet_adding_one_is_optional),
            color = Muted
        )
        Text(
            if (editing == null) uiText(R.string.add_a_contact) else uiText(R.string.edit_contact),
            style = MaterialTheme.typography.titleMedium
        )
        Field(
            uiText(R.string.contact_name),
            name,
            "contact_name",
            enabled = !session.busy,
            onFocus = {
                session.recordSettingsInteraction(
                    "Olive Branch",
                    "Focused contact name"
                )
            }) { name = it.take(120) }
        Field(
            uiText(R.string.phone_or_email),
            details,
            "contact_details",
            enabled = !session.busy,
            onFocus = {
                session.recordSettingsInteraction(
                    "Olive Branch",
                    "Focused contact details"
                )
            }) { details = it.take(200) }
        TrustedContactLinkPicker(
            session.messages.friends, linkedUid,
            profile.contacts.filterNot { it.id == editing }
                .mapNotNull { it.linkedUid.takeIf(String::isNotBlank) }.toSet(),
            !session.busy, "contact_link_account"
        ) { linkedUid = it }
        Status(session)
        Button(
            onClick = {
                // A trusted contact needs both a name and a phone number or email.
                if (name.isBlank() || details.isBlank()) {
                    session.account.showError(uiText(R.string.enter_a_name_and_a_phone_number_or_email))
                } else if (editing == null && profile.contacts.size >= 25) {
                    session.account.showError(uiText(R.string.you_can_save_up_to_25_contacts))
                } else {
                    val contact = SafeContact(
                        editing ?: UUID.randomUUID().toString(),
                        name.trim(),
                        details.trim(),
                        profile.contacts.firstOrNull { it.id == editing }?.safeToStay ?: false,
                        linkedUid
                    )
                    session.saveContacts(if (editing == null) profile.contacts + contact else profile.contacts.map { if (it.id == editing) contact else it })
                    editing = null
                    name = ""
                    details = ""
                    linkedUid = ""
                }
            },
            enabled = !session.busy,
            modifier = Modifier.fillMaxWidth().testTag("save_contact")
        ) { Text(stringResource(R.string.save_contact)) }
        if (editing != null) TextButton(onClick = {
            editing = null
            name = ""
            details = ""
            linkedUid = ""
        }) { Text(stringResource(R.string.cancel_edit)) }
    }
}

// Edit the private profile, safe-entry methods and authenticated trigger settings.
// UI sizing/text: settings reuse shared form spacing and Material titleLarge section headings so translated labels can wrap.
@Composable
internal fun SafeProfileScreen(session: SessionViewModel, profile: UserProfile) {
    val uiText = rememberResourceText()
    FormColumn {
        SafeBack(session)
        Heading(uiText(R.string.my_profile), uiText(R.string.your_saved_account_information))
        val rows = buildList {
            add(uiText(R.string.account_type) to profile.role.localizedTitle(uiText))
            add(uiText(R.string.username) to profile.username)
            add(
                uiText(R.string.full_name) to profile.fullName
            )
            if (profile.role == AccountRole.USER) {
                add(uiText(R.string.people_experiencing_abuse) to profile.affectedNames)
                add(
                    uiText(
                        R.string.abuser_s_name
                    ) to profile.abuserName
                )
                add(uiText(R.string.situation) to profile.situation)
            }
            add(uiText(R.string.date_of_birth) to profile.dob)
            add(uiText(R.string.preferred_language) to profile.language)
            add(
                uiText(R.string.region_city_state) to profile.region
            )
            // Only users choose a discreet cover; other roles open their workspace directly.
            if (profile.role == AccountRole.USER) {
                add(uiText(R.string.cover_app) to profile.coverType.localizedTitle(uiText))
                add(
                    uiText(R.string.delivery_address) to listOf(
                        profile.address.line1,
                        profile.address.line2,
                        profile.address.city,
                        profile.address.province,
                        profile.address.postalCode,
                        profile.address.country
                    ).filter { it.isNotBlank() }.joinToString(", ")
                )
            }
        }
        rows
            .forEach { (label, value) ->
                Text(label, style = MaterialTheme.typography.labelLarge, color = OliveGreen)
                if (value.isBlank()) Text(stringResource(R.string.not_provided))
                else if (label == uiText(R.string.preferred_language)) Text(value)
                else Text(value)
            }
    }
}
