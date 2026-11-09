// Shared profile address editor used by onboarding and account settings.
package com.olive.app.ui.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.DeliveryAddress
import com.olive.app.data.AddressSuggestion
import com.olive.app.data.GeoapifyClient
import java.util.concurrent.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun AddressFields(
    value: DeliveryAddress,
    cityOnlyRequired: Boolean = false,
    cityError: String? = null,
    onFocus: ((String) -> Unit)? = null,
    change: (DeliveryAddress) -> Unit,
) {
    val text = rememberResourceText()
    AddressAutocomplete(
        value,
        if (cityOnlyRequired) text(R.string.street_address_optional) else text(R.string.street_address_required),
        onFocus = { onFocus?.invoke("Google address selection") },
        onChange = change,
    )
    AddressTextField(
        text(R.string.apartment_unit_optional),
        value.line2,
        "address_line2",
        onFocus = { onFocus?.invoke("Apartment / unit") },
    ) { change(value.copy(line2 = it.take(160))) }
    AddressAutocomplete(
        value,
        text(R.string.city_required),
        onFocus = { onFocus?.invoke("City") },
        cityOnly = true,
        fieldError = cityError,
        onChange = change,
    )
    AddressTextField(
        if (cityOnlyRequired) text(R.string.province_state_optional) else text(R.string.province_state_required),
        value.province,
        "address_province",
        onFocus = { onFocus?.invoke("Province / state") },
    ) { change(value.copy(province = it.take(160))) }
    AddressTextField(
        if (cityOnlyRequired) text(R.string.postal_zip_code_optional) else text(R.string.postal_zip_code_required),
        value.postalCode,
        "address_postal",
        onFocus = { onFocus?.invoke("Postal / ZIP code") },
    ) { change(value.copy(postalCode = it.take(160))) }
    AddressTextField(
        if (cityOnlyRequired) text(R.string.country_optional) else text(R.string.country_required),
        value.country,
        "address_country",
        onFocus = { onFocus?.invoke("Country") },
    ) { change(value.copy(country = it.take(160))) }
}

@Composable
private fun AddressTextField(
    label: String,
    value: String,
    tag: String,
    onFocus: (() -> Unit)? = null,
    change: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = change,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag)
            .onFocusChanged { if (it.isFocused) onFocus?.invoke() }.keepVisibleWhileTyping(),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Text),
    )
}

@Composable
private fun AddressAutocomplete(
    current: DeliveryAddress,
    label: String,
    onFocus: (() -> Unit)? = null,
    cityOnly: Boolean = false,
    fieldError: String? = null,
    onChange: (DeliveryAddress) -> Unit,
) {
    val text = rememberResourceText()
    var suggestions by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedText by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    val query = if (cityOnly) current.city else current.line1
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
                if (it is CancellationException) throw it
                error = text(R.string.address_suggestions_are_unavailable_continue_entering_your_address)
            }
    }
    OutlinedTextField(
        value = query,
        onValueChange = { typed ->
            onChange(
                if (cityOnly) current.copy(city = typed.take(160), latitude = null, longitude = null)
                else current.copy(line1 = typed.take(160), latitude = null, longitude = null)
            )
            error = null
        },
        singleLine = true,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().testTag(if (cityOnly) "address_city" else "address_line1")
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus?.invoke() }
            .keepVisibleWhileTyping(),
        isError = error != null || fieldError != null,
        supportingText = (fieldError ?: error)?.let { message -> { AccessibleMessage(message) } },
    )
    suggestions.forEach { suggestion ->
        Surface(
            modifier = Modifier.fillMaxWidth().clickable {
                val resolved = suggestion.address
                val selected = if (cityOnly) current.copy(
                    city = resolved.city,
                    province = resolved.province,
                    country = resolved.country,
                    latitude = resolved.latitude,
                    longitude = resolved.longitude,
                ) else resolved.copy(line2 = current.line2)
                selectedText = if (cityOnly) selected.city else selected.line1
                suggestions = emptyList()
                onChange(selected)
                onFocus?.invoke()
                error = null
            },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(suggestion.primary, style = MaterialTheme.typography.titleSmall)
                if (suggestion.secondary.isNotBlank()) {
                    Text(suggestion.secondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (suggestions.isNotEmpty()) {
        Text(text(R.string.powered_by_geoapify), style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}
