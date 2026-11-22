// Shared profile address and birthday form fields used across onboarding and account settings.
package com.olive.app.ui.shared

import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveTextButton as TextButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.location.AddressSuggestion
import com.olive.app.data.location.GeoapifyClient
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.concurrent.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Renders the address fields composable.
 *
 * @param value Input value to store, parse, or format.
 * @param cityOnlyRequired Whether to apply city only required.
 * @param cityError Optional city error supplied to [AddressFields].
 * @param onFocus Callback invoked when focus changes or occurs.
 * @param change Action or callback executed for change.
 */
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

/**
 * Renders the address text field composable.
 *
 * @param label Accessible label or display caption.
 * @param value Input value to store, parse, or format.
 * @param tag Tag supplied to [AddressTextField].
 * @param onFocus Callback invoked when focus changes or occurs.
 * @param change Action or callback executed for change.
 */
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

/**
 * Composes the address autocomplete UI element.
 *
 * @param current Current supplied to [AddressAutocomplete].
 * @param label Accessible label or display caption.
 * @param onFocus Callback invoked when focus changes or occurs.
 * @param cityOnly Whether to apply city only.
 * @param fieldError Optional field error supplied to [AddressAutocomplete].
 * @param onChange Callback invoked when the value or observed state changes.
 */
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
        if (selectedText == query) {
            selectedText = null
            suggestions = emptyList()
            return@LaunchedEffect
        }
        if (!focused || query.trim().length < 2 || !GeoapifyClient.configured) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(50.milliseconds)
        runCatching { GeoapifyClient.autocomplete(query, cityOnly, minLength = 2) }
            .onSuccess { suggestions = it }
            .onFailure {
                if (it is CancellationException) throw it
                suggestions = emptyList()
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

private const val FIRST_BIRTH_YEAR = 1927
private const val YEARS_PER_GROUP = 10

/**
 * Computes birthday years.
 *
 * @param today Today supplied to [birthdayYears].
 * @return List of resulting birthday years items.
 */
internal fun birthdayYears(today: LocalDate): List<IntRange> =
    (maxOf(FIRST_BIRTH_YEAR, today.minusYears(130).year)..today.year)
        .chunked(YEARS_PER_GROUP).asReversed().map { it.first()..it.last() }

/**
 * Composes the remember birthday input UI element.
 *
 * @param initialDate Initial date supplied to [rememberBirthdayInput].
 */
@Composable
internal fun rememberBirthdayInput(initialDate: String = "") = rememberSaveable(initialDate) {
    mutableStateOf(initialDate)
}

/**
 * Renders the birthday picker composable.
 *
 * @param value Input value to store, parse, or format.
 * @param tag Tag supplied to [BirthdayPicker].
 * @param enabled Whether this feature or control is currently enabled.
 * @param showErrors Whether to show errors.
 * @param required Whether to apply required.
 * @param onChange Callback invoked when the value or observed state changes.
 */
@Composable
internal fun BirthdayPicker(
    value: String,
    tag: String,
    enabled: Boolean,
    showErrors: Boolean,
    required: Boolean = true,
    onChange: (String) -> Unit,
) {
    val uiText = rememberResourceText()
    val today = LocalDate.now()
    val earliest = today.minusYears(130)
    val locale = LocalConfiguration.current.locales[0]
    val date = remember(value) { runCatching { LocalDate.parse(value) }.getOrNull() }
        ?.takeIf { it in earliest..today }
    val dateText = date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
    val label = uiText(if (required) R.string.date_of_birth_required else R.string.date_of_birth)
    val message = when {
        !showErrors -> null
        value.isBlank() && required -> uiText(R.string.ui_birthday_required)
        value.isNotBlank() && date == null -> uiText(R.string.dob_date_range_error)
        else -> null
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var open by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        OutlinedButton(
            onClick = {
                keyboard?.hide()
                focus.clearFocus()
                open = true
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("${tag}_open")
                .semantics { message?.let { error(it) } },
            enabled = enabled,
        ) { Text(dateText ?: uiText(R.string.dob_picker_title)) }
        if (!required && value.isNotBlank()) {
            TextButton(enabled = enabled, onClick = { onChange("") }) { Text(uiText(R.string.dob_clear)) }
        }
        message?.let { AccessibleMessage(it, color = MaterialTheme.colorScheme.error) }
    }

    if (open) {
        BirthdayPickerDialog(tag, today, onDismiss = { open = false }) {
            onChange(it.toString())
            open = false
        }
    }
}

private enum class BirthdayStep(val title: Int, val columns: Int) {
    Range(R.string.dob_pick_year_range, 2),
    Year(R.string.dob_pick_year, 3),
    Month(R.string.dob_pick_month, 2),
    Day(R.string.dob_pick_day, 4),
}

/**
 * Displays the birthday picker dialog overlay.
 *
 * @param tag Tag supplied to [BirthdayPickerDialog].
 * @param today Today supplied to [BirthdayPickerDialog].
 * @param onDismiss Callback invoked when the dialog or sheet is dismissed.
 * @param onSelect Callback invoked when select changes or occurs.
 */
@Composable
private fun BirthdayPickerDialog(
    tag: String,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    val uiText = rememberResourceText()
    val locale = LocalConfiguration.current.locales[0]
    var step by rememberSaveable { mutableStateOf(BirthdayStep.Range) }
    var rangeEnd by rememberSaveable { mutableIntStateOf(today.year) }
    var year by rememberSaveable { mutableIntStateOf(today.year) }
    var month by rememberSaveable { mutableIntStateOf(1) }
    val selectedMonth = YearMonth.of(year, month)
    val ranges = remember(today) { birthdayYears(today) }
    val range = ranges.firstOrNull { it.last == rangeEnd } ?: ranges.first()
    val earliest = today.minusYears(130)
    val columns = if (LocalDensity.current.fontScale < 1.3f) step.columns else when (step) {
        BirthdayStep.Range, BirthdayStep.Month -> 1
        BirthdayStep.Year, BirthdayStep.Day -> 2
    }
    val choices = when (step) {
        BirthdayStep.Range -> ranges.map {
            it.last to if (it.first == it.last) it.last.toString() else "${it.last}–${it.first}"
        }
        BirthdayStep.Year -> range.reversed()
            .map { it to it.toString() }
        BirthdayStep.Month -> (1..12).filter {
            val candidate = YearMonth.of(year, it)
            candidate.atEndOfMonth() >= earliest && candidate.atDay(1) <= today
        }.map {
            it to java.time.Month.of(it).getDisplayName(TextStyle.FULL_STANDALONE, locale)
        }
        BirthdayStep.Day -> (1..selectedMonth.lengthOfMonth())
            .filter { selectedMonth.atDay(it) in earliest..today }.map { it to it.toString() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiText(R.string.dob_picker_title)) },
        text = {
            key(step) {
                val headingFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    headingFocus.requestFocus()
                }
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        uiText(step.title),
                        Modifier.focusRequester(headingFocus).focusable().semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    when (step) {
                        BirthdayStep.Month -> Text(year.toString())
                        BirthdayStep.Day -> Text(selectedMonth.format(DateTimeFormatter.ofPattern("LLLL uuuu", locale)))
                        else -> Unit
                    }
                    choices.chunked(columns).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { (number, label) ->
                                OutlinedButton(
                                    onClick = {
                                        when (step) {
                                            BirthdayStep.Range -> { rangeEnd = number; step = BirthdayStep.Year }
                                            BirthdayStep.Year -> { year = number; step = BirthdayStep.Month }
                                            BirthdayStep.Month -> { month = number; step = BirthdayStep.Day }
                                            BirthdayStep.Day -> onSelect(selectedMonth.atDay(number))
                                        }
                                    },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                        .testTag("${tag}_${step.name.lowercase()}_$number"),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                                ) {
                                    val spokenLabel = when (step) {
                                        BirthdayStep.Range -> uiText(
                                            R.string.dob_spoken_range,
                                            ranges.first { it.last == number }.first.spokenYear(),
                                            number.spokenYear(),
                                        )
                                        BirthdayStep.Year -> number.spokenYear()
                                        else -> null
                                    }
                                    Text(label, if (spokenLabel == null) Modifier else Modifier.clearAndSetSemantics {
                                        contentDescription = spokenLabel
                                    })
                                }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(uiText(R.string.cancel)) } },
        dismissButton = {
            if (step != BirthdayStep.Range) {
                TextButton(
                    onClick = { step = BirthdayStep.entries[step.ordinal - 1] },
                    modifier = Modifier.testTag("${tag}_picker_back"),
                ) {
                    Text(uiText(R.string.back))
                }
            }
        },
    )
}

/**
 * Computes spoken year for this int.
 *
 * @return The resulting spoken year string.
 */
private fun Int.spokenYear(): String = toString()
