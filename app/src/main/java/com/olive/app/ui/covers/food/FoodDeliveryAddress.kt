// Food cover users: lets food-cover users pick a delivery address.
package com.olive.app.ui.covers.food

import com.olive.app.ui.shared.*

import com.olive.app.ui.shared.rememberResourceText
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import com.olive.app.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.olive.app.data.DeliveryAddress
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.foundation.clickable
import com.olive.app.data.*
import kotlinx.coroutines.CancellationException


// returns the display text for food cover.
internal fun DeliveryAddress.displayText() =
    listOf(line1, line2, city, province, postalCode, country)
        .filter(String::isNotBlank).joinToString(", ")

// UI sizing/text: checkout fields fill the dialog width and rely on Material field/error text sizes.

@Composable
internal fun AddressFields(
    value: DeliveryAddress,
    cityOnlyRequired: Boolean = false,
    cityError: String? = null,
    onFocus: ((String) -> Unit)? = null,
    change: (DeliveryAddress) -> Unit
) {
    val uiText = rememberResourceText()
    AddressAutocomplete(
        value,
        if (cityOnlyRequired) uiText(R.string.street_address_optional) else uiText(R.string.street_address_required),
        onFocus = { onFocus?.invoke("Google address selection") },
        onChange = change
    )
    CheckoutField(
        uiText(R.string.apartment_unit_optional),
        value.line2,
        "address_line2",
        onFocus = { onFocus?.invoke("Apartment / unit") }) { change(value.copy(line2 = it.take(160))) }
    AddressAutocomplete(
        value,
        uiText(R.string.city_required),
        cityOnly = true,
        fieldError = cityError,
        onFocus = { onFocus?.invoke("City") },
        onChange = change
    )
    CheckoutField(
        if (cityOnlyRequired) uiText(R.string.province_state_optional) else uiText(R.string.province_state_required),
        value.province,
        "address_province",
        onFocus = { onFocus?.invoke("Province / state") }) {
        change(
            value.copy(
                province = it.take(
                    160
                )
            )
        )
    }
    CheckoutField(
        if (cityOnlyRequired) uiText(R.string.postal_zip_code_optional) else uiText(R.string.postal_zip_code_required),
        value.postalCode,
        "address_postal",
        onFocus = { onFocus?.invoke("Postal / ZIP code") }) {
        change(
            value.copy(
                postalCode = it.take(
                    160
                )
            )
        )
    }
    CheckoutField(
        if (cityOnlyRequired) uiText(R.string.country_optional) else uiText(R.string.country_required),
        value.country,
        "address_country",
        onFocus = { onFocus?.invoke("Country") }) { change(value.copy(country = it.take(160))) }
}

// UI sizing/color: cover dialogs fill the screen with Cream, respect system/keyboard insets, and use 24dp padding with 14dp gaps.

@Composable
internal fun CoverDialog(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            securePolicy = SecureFlagPolicy.SecureOn
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = Cream) {
            val scrollState = rememberScrollState()
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
                    .oliveScrollbar(scrollState).verticalScroll(scrollState).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.back_to_menu)) }
                content()
            }
        }
    }
}

// Address fields live here; SessionCoverState saves through SessionViewModel and updates addressSaveVersion to close the dialog.
// UI sizing/text: address headings use headlineMedium and controls fill the dialog width.
@Composable
internal fun AddressDialog(
    address: DeliveryAddress,
    busy: Boolean,
    message: String?,
    onSave: (DeliveryAddress) -> Unit,
    onClose: () -> Unit
) {
    var draft by remember(address) { mutableStateOf(address) }
    CoverDialog(onClose) {
        Text(
            stringResource(R.string.delivery_address),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.where_should_we_deliver_your_food))
        AddressFields(draft) { draft = it }
        message?.let { AccessibleMessage(it) }
        Button(
            onClick = {
                onSave(
                    draft.copy(
                        line1 = draft.line1.trim(),
                        line2 = draft.line2.trim(),
                        city = draft.city.trim(),
                        province = draft.province.trim(),
                        postalCode = draft.postalCode.trim(),
                        country = draft.country.trim()
                    )
                )
            },
            enabled = draft.valid && !busy,
            modifier = Modifier.fillMaxWidth().testTag("save_address")
        ) { Text(stringResource(R.string.save_delivery_address)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick = onClose) { Text(stringResource(R.string.done)) }
    }
}

// Change checkout presentation here; keep payment fields temporary and the result declined.
// UI sizing/color: checkout sections use titleLarge, inline error colors, full-width buttons, and the shared dialog spacing.

@Composable
private fun AddressAutocomplete(
    current: DeliveryAddress,
    label: String,
    onFocus: (() -> Unit)? = null,
    cityOnly: Boolean = false,
    fieldError: String? = null,
    onChange: (DeliveryAddress) -> Unit
) {
    val uiText = rememberResourceText()
    var suggestions by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedText by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    val query = if (cityOnly) current.city else current.line1
    // Update address autocomplete when information used by this screen changes.
    LaunchedEffect(query, focused) {
        suggestions = emptyList()
        if (selectedText == query) {
            selectedText = null
            return@LaunchedEffect
        }
        if (!focused || query.trim().length < 3 || !GeoapifyClient.configured) return@LaunchedEffect
        delay(200.milliseconds)
        runCatching { GeoapifyClient.autocomplete(query, cityOnly) }
            .onSuccess { suggestions = it }
            .onFailure {
                if (it is CancellationException) throw it else error =
                    uiText(R.string.address_suggestions_are_unavailable_continue_entering_your_address)
            }
    }

    OutlinedTextField(
        value = query,
        onValueChange = { value ->
            val cleared = if (cityOnly) current.copy(
                city = value.take(160),
                latitude = null,
                longitude = null
            )
            else current.copy(line1 = value.take(160), latitude = null, longitude = null)
            onChange(cleared)
            error = null
        },
        singleLine = true,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth()
            .testTag(if (cityOnly) "address_city" else "address_line1")
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus?.invoke()
            }
            .keepVisibleWhileTyping(),
        isError = error != null || fieldError != null,
        supportingText = (fieldError ?: error)?.let { value -> { AccessibleMessage(value) } })
    suggestions.forEach { suggestion ->
        Surface(Modifier.fillMaxWidth().clickable {
            val resolved = suggestion.address
            val selected = if (cityOnly) current.copy(
                city = resolved.city, province = resolved.province, country = resolved.country,
                latitude = resolved.latitude, longitude = resolved.longitude
            ) else resolved.copy(line2 = current.line2)
            selectedText = if (cityOnly) selected.city else selected.line1
            suggestions =
                emptyList()
            onChange(selected)
            onFocus?.invoke()
            error = null
        }, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(12.dp)) {
                Text(suggestion.primary, style = MaterialTheme.typography.titleSmall)
                if (suggestion.secondary.isNotBlank()) Text(
                    suggestion.secondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
    if (suggestions.isNotEmpty()) Text(
        stringResource(R.string.powered_by_geoapify),
        style = MaterialTheme.typography.labelSmall,
        color = Muted
    )
}
