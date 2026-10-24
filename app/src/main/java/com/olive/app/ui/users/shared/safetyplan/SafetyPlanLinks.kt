// Contains the safety plan links code for this part of Olive.
package com.olive.app.ui.users.shared.safetyplan

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*


import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.olive.app.data.*
import com.olive.app.data.Friend
import com.olive.app.data.SafetyChecklistItem
import com.olive.app.data.writeSafetyChecklist
import com.olive.app.R
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


// Adaptable prompts informed by PHAC safety-planning guidance; all starting text remains editable.
// https://www.canada.ca/en/public-health/services/health-promotion/stop-family-violence/plan-your-safety.html
internal fun safetyDefaults(context: Context): Map<String, String> = buildMap {
    listOf("primary", "backup").forEach { plan ->
        com.olive.app.data.SAFETY_SECTIONS.forEach { section ->
            val prompts = when (section) {
                "documents" -> safetyDocumentSuggestions(context)
                "grab" -> context.resources.getStringArray(R.array.safety_grab_checklist).toList()
                "destination" -> context.resources.getStringArray(R.array.safety_destination_checklist)
                    .toList()

                "travel" -> context.resources.getStringArray(R.array.safety_travel_checklist)
                    .toList()

                "contacts" -> context.resources.getStringArray(R.array.safety_contacts_checklist)
                    .toList()

                "aftercare" -> context.resources.getStringArray(R.array.safety_aftercare_checklist)
                    .toList()

                else -> emptyList()
            }
            put(
                "${plan}_$section",
                writeSafetyChecklist(prompts.map {
                    SafetyChecklistItem(
                        UUID.randomUUID().toString(),
                        it
                    )
                })
            )
        }
    }
}

private fun safetyDocumentSuggestions(context: Context) =
    context.resources.getStringArray(R.array.safety_document_checklist).toList()

internal fun safetySectionLabel(section: String) = when (section) {
    "destination" -> R.string.safety_destination
    "travel" -> R.string.safety_travel
    "contacts" -> R.string.safety_contacts
    "documents" -> R.string.safety_documents_heading
    "grab" -> R.string.safety_grab_heading
    "essentials" -> R.string.safety_essentials
    "dependants" -> R.string.safety_dependants
    else -> R.string.safety_aftercare
}

internal fun safetySectionHint(section: String) = when (section) {
    "destination" -> R.string.safety_destination_hint
    "travel" -> R.string.safety_travel_hint
    "contacts" -> R.string.safety_contacts_hint
    "aftercare" -> R.string.safety_aftercare_hint
    else -> null
}

// Linking is always explicit; names, phone numbers and email addresses are never used to infer an account.
@Composable
internal fun TrustedContactLinkPicker(
    friends: List<Friend>, selectedUid: String, unavailableUids: Set<String>,
    enabled: Boolean, tag: String, onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = friends.firstOrNull { it.uid == selectedUid }
    val choices = friends.filter { it.uid == selectedUid || it.uid !in unavailableUids }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.trusted_link_title),
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            stringResource(R.string.trusted_link_help),
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
        if (friends.isEmpty()) Text(
            stringResource(R.string.trusted_link_no_friends),
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
        else Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true }, enabled = enabled && choices.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag(tag)
            ) {
                Icon(Icons.Outlined.Link, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        selected != null -> stringResource(
                            R.string.trusted_link_to,
                            selected.username
                        )

                        selectedUid.isNotBlank() -> stringResource(R.string.trusted_link_unavailable)
                        else -> stringResource(R.string.trusted_link_none)
                    }, Modifier.weight(1f)
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { friend ->
                    DropdownMenuItem(
                        text = { Text("@${friend.username}") },
                        onClick = {
                            expanded = false
                            onSelected(friend.uid)
                        },
                        leadingIcon = { Icon(Icons.Outlined.Link, null) }
                    )
                }
            }
        }
        if (selectedUid.isNotBlank()) TextButton(
            onClick = { onSelected("") }, enabled = enabled,
            modifier = Modifier.align(Alignment.End).testTag("${tag}_unlink")
        ) {
            Icon(
                Icons.Outlined.LinkOff,
                null
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.trusted_unlink))
        }
    }
}
