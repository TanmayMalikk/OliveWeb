// Shared profile address and birthday form fields used across onboarding and account settings.
package com.olive.app.ui.shared

import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveTextButton as TextButton
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.ButtonDefaults
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
import java.time.format.TextStyle as DateTextStyle
import java.util.concurrent.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextStyle
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import kotlin.math.max

/**
 * Renders the address fields composable.
 *
 * @param value Input value to store, parse, or format.
 * @param cityOnlyRequired Whether to apply city only required.
 * @param cityError Optional city error supplied to [AddressFields].
 * @param shakeTrigger Monotonic counter to re-trigger shake feedback on repeated failed attempts.
 * @param onFocus Callback invoked when focus changes or occurs.
 * @param change Action or callback executed for change.
 */
@Composable
internal fun AddressFields(
    value: DeliveryAddress,
    cityOnlyRequired: Boolean = false,
    cityError: String? = null,
    shakeTrigger: Int = 0,
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
        shakeTrigger = shakeTrigger,
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
 * @param shakeTrigger Monotonic counter to re-trigger shake feedback on repeated failed attempts.
 * @param onChange Callback invoked when the value or observed state changes.
 */
@Composable
private fun AddressAutocomplete(
    current: DeliveryAddress,
    label: String,
    onFocus: (() -> Unit)? = null,
    cityOnly: Boolean = false,
    fieldError: String? = null,
    shakeTrigger: Int = 0,
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
    val effectiveShake = rememberFieldErrorShakeTrigger(fieldError ?: error, shakeTrigger)
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
            .oliveErrorShake(effectiveShake)
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
 * @return The saveable state containing the selected birth date.
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
 * @param shakeTrigger Monotonic counter to re-trigger shake feedback on repeated failed attempts.
 * @param onChange Callback invoked when the value or observed state changes.
 */
@Composable
internal fun BirthdayPicker(
    value: String,
    tag: String,
    enabled: Boolean,
    showErrors: Boolean,
    required: Boolean = true,
    shakeTrigger: Int = 0,
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
    val effectiveShake = rememberFieldErrorShakeTrigger(message, shakeTrigger)
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
                .oliveErrorShake(effectiveShake)
                .semantics { message?.let { error(it) } },
            enabled = enabled,
            border = if (message != null) BorderStroke(1.5.dp, MaterialTheme.colorScheme.error)
                else ButtonDefaults.outlinedButtonBorder(enabled),
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
            it to java.time.Month.of(it).getDisplayName(DateTextStyle.FULL_STANDALONE, locale)
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
 * @receiver The numeric year to format for spoken output.
 * @return The resulting spoken year string.
 */
private fun Int.spokenYear(): String = toString()

internal const val ERROR_SHAKE_DURATION_MS = 420

/**
 * Builds the damped horizontal shake keyframe sequence `(offsetDp, timeMillis)` for [ERROR_SHAKE_DURATION_MS].
 *
 * @param amplitudeDp Peak horizontal displacement in dp.
 * @return Ordered keyframe pairs starting and ending at `0f` dp.
 */
internal fun errorShakeKeyframes(amplitudeDp: Float = 14f): List<Pair<Float, Int>> {
    val peak = amplitudeDp.coerceAtLeast(0f)
    return listOf(
        0f to 0,
        -peak to 50,
        peak to 100,
        -peak * 0.78f to 155,
        peak * 0.78f to 210,
        -peak * 0.5f to 265,
        peak * 0.5f to 320,
        -peak * 0.22f to 370,
        0f to ERROR_SHAKE_DURATION_MS,
    )
}

/**
 * Returns `true` when an invalid field should fire a fresh shake: either an error newly appeared or
 * changed, or an explicit submit retry incremented [currentTrigger] while [currentError] remained set.
 *
 * @param previousError Previously observed field error text, or `null`.
 * @param currentError Current field error text, or `null`.
 * @param previousTrigger Previously observed explicit submit trigger counter.
 * @param currentTrigger Current explicit submit trigger counter.
 * @return `true` if a shake should be dispatched.
 */
internal fun shouldTriggerFieldErrorShake(
    previousError: String?,
    currentError: String?,
    previousTrigger: Int,
    currentTrigger: Int,
): Boolean = currentError != null && (currentError != previousError || currentTrigger > previousTrigger)

/**
 * Checks whether Android system animator motion is enabled on this device.
 *
 * @param context Android context used to query global animator duration scale.
 * @return `true` when animator scale is positive, or `true` by default if unavailable.
 */
private fun isMotionEnabled(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
}.getOrDefault(true)

/**
 * Tracks a field's [error] text and explicit [shakeTrigger] counter and returns a monotonic shake count
 * that never fires on initial composition and re-fires on every invalid re-submission.
 *
 * @param error Current validation error text for the field, or `null`.
 * @param shakeTrigger Caller-incremented counter for repeated invalid submit attempts.
 * @return Monotonic trigger count to pass to [oliveErrorShake].
 */
@Composable
internal fun rememberFieldErrorShakeTrigger(error: String?, shakeTrigger: Int): Int {
    val count = remember { mutableIntStateOf(0) }
    val lastError = remember { mutableStateOf(error) }
    val lastTrigger = remember { mutableIntStateOf(shakeTrigger) }
    LaunchedEffect(error, shakeTrigger) {
        if (shouldTriggerFieldErrorShake(lastError.value, error, lastTrigger.intValue, shakeTrigger)) {
            count.intValue += 1
        }
        lastError.value = error
        lastTrigger.intValue = shakeTrigger
    }
    return count.intValue
}

/**
 * Applies an exaggerated finite horizontal shake and rejection haptic when [trigger] advances above zero,
 * respecting OS animator-duration reduced-motion settings.
 *
 * @receiver The modifier chain to extend.
 * @param trigger Monotonic counter incremented on each invalid attempt (`0` skips initial mount motion).
 * @param amplitudeDp Peak horizontal excursion in dp.
 * @return Modified [Modifier] with horizontal translation applied during an active shake.
 */
internal fun Modifier.oliveErrorShake(trigger: Int, amplitudeDp: Float = 14f): Modifier = composed {
    val offsetDp = remember { Animatable(0f) }
    val view = LocalView.current
    val context = LocalContext.current
    LaunchedEffect(trigger) {
        if (trigger <= 0) return@LaunchedEffect
        val haptic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(haptic)
        if (isMotionEnabled(context)) {
            offsetDp.snapTo(0f)
            offsetDp.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = ERROR_SHAKE_DURATION_MS
                    for ((value, at) in errorShakeKeyframes(amplitudeDp)) {
                        value at at
                    }
                },
            )
        } else {
            offsetDp.snapTo(0f)
        }
    }
    this.graphicsLayer { translationX = offsetDp.value.dp.toPx() }
}

internal val LocalAccessibilityMessagesVisible = staticCompositionLocalOf { true }

// TalkBack reads normal screen content first, temporary overlay content next, and persistent
// floating actions last. New overlapping layouts should use these values instead of raw numbers.
internal enum class AccessibilityTraversalOrder(val index: Float) {
    ScreenContent(0f),
    OverlayContent(1f),
    PersistentActions(2f),
}

/**
 * Marks a layout as the boundary where TalkBack applies the shared traversal order.
 *
 * @receiver The modifier chain to extend.
 * @return The resulting [Modifier] for accessibility traversal group.
 */
internal fun Modifier.accessibilityTraversalGroup(): Modifier = semantics {
    isTraversalGroup = true
}

/**
 * Assigns one part of an overlapping layout to its place in the shared TalkBack order.
 *
 * @receiver The modifier chain to extend.
 * @param order Order supplied to [accessibilityTraversal].
 * @return The resulting [Modifier] for accessibility traversal.
 */
internal fun Modifier.accessibilityTraversal(order: AccessibilityTraversalOrder): Modifier =
    semantics { traversalIndex = order.index }

/**
 * Exposes status changes through Compose semantics. Live regions let TalkBack
 * announce new content while preserving the user's current reading position.
 *
 * @param text Text string to display, parse, or send.
 * @param modifier Compose modifier applied to the root layout.
 * @param color Color supplied to [AccessibleMessage].
 * @param style Style supplied to [AccessibleMessage].
 * @param showText Whether to show text.
 */
@Composable
internal fun AccessibleMessage(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    style: TextStyle = LocalTextStyle.current,
    showText: Boolean = true,
) {
    val visible = LocalAccessibilityMessagesVisible.current
    if (!showText) {
        val view = androidx.compose.ui.platform.LocalView.current
        LaunchedEffect(text, visible, view) {
            if (visible) {
                @Suppress("DEPRECATION") // Announce once without leaving an invisible focusable node.
                view.announceForAccessibility(text)
            }
        }
        return
    }
    val semantics = modifier.semantics {
        if (visible) {
            liveRegion = LiveRegionMode.Polite
        } else hideFromAccessibility()
    }
    Text(text, color = color, style = style, modifier = semantics)
}

/**
 * Callers resolve action labels from Android resources; decorative icons keep a null description.
 *
 * @param imageVector Image vector supplied to [LocalizedIcon].
 * @param contentDescription Optional content description supplied to [LocalizedIcon].
 * @param modifier Compose modifier applied to the root layout.
 * @param tint Tint supplied to [LocalizedIcon].
 */
@Composable
internal fun LocalizedIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(imageVector, contentDescription, modifier, tint)
}

/**
 * Identifies accessibility actions that should also reset the privacy timer.
 *
 * @param eventType Event type supplied to [isAccessibilityUserInteraction].
 * @return `true` if is accessibility user interaction, or `false` otherwise.
 */
internal fun isAccessibilityUserInteraction(eventType: Int): Boolean = eventType in setOf(
    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
    AccessibilityEvent.TYPE_VIEW_CLICKED,
    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
    AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY,
)

/**
 * Observe the existing outer content view, preserving its delegate and the Compose/WebView delegates below it.
 *
 * @param view View supplied to [observeAccessibilityInteractions].
 * @param onInteraction Callback invoked when interaction changes or occurs.
 */
internal fun observeAccessibilityInteractions(view: ViewGroup, onInteraction: () -> Unit) {
    val previous = ViewCompat.getAccessibilityDelegate(view)
    ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
        /**
         * Resets the privacy timer before Android sends a TalkBack event.
         *
         * @param host Host supplied to [onRequestSendAccessibilityEvent].
         * @param child Child supplied to [onRequestSendAccessibilityEvent].
         * @param event Sensor, UI, or lifecycle event being processed.
         * @return `true` if on request send accessibility event, or `false` otherwise.
         */
        override fun onRequestSendAccessibilityEvent(
            host: ViewGroup,
            child: View,
            event: AccessibilityEvent,
        ): Boolean {
            val accepted = previous?.onRequestSendAccessibilityEvent(host, child, event)
                ?: super.onRequestSendAccessibilityEvent(host, child, event)
            if (accepted && isAccessibilityUserInteraction(event.eventType)) onInteraction()
            return accepted
        }

        /**
         * Adds the whole field rule to one TalkBack event instead of reading separate pieces.
         *
         * @param host Host supplied to [dispatchPopulateAccessibilityEvent].
         * @param event Sensor, UI, or lifecycle event being processed.
         * @return `true` if dispatch populate accessibility event, or `false` otherwise.
         */
        override fun dispatchPopulateAccessibilityEvent(
            host: View,
            event: AccessibilityEvent
        ): Boolean =
            previous?.dispatchPopulateAccessibilityEvent(host, event)
                ?: super.dispatchPopulateAccessibilityEvent(host, event)

        /**
         * Finds the Android accessibility node provider used by TalkBack.
         *
         * @param host Host supplied to [getAccessibilityNodeProvider].
         * @return The resulting [AccessibilityNodeProviderCompat], or `null` if unavailable.
         */
        override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProviderCompat? =
            previous?.getAccessibilityNodeProvider(host) ?: super.getAccessibilityNodeProvider(host)

        /**
         * Adds the control description to the TalkBack event.
         *
         * @param host Host supplied to [onInitializeAccessibilityEvent].
         * @param event Sensor, UI, or lifecycle event being processed.
         */
        override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
            if (previous != null) previous.onInitializeAccessibilityEvent(host, event)
            else super.onInitializeAccessibilityEvent(host, event)
        }

        /**
         * Adds the control role and description to the TalkBack node.
         *
         * @param host Host supplied to [onInitializeAccessibilityNodeInfo].
         * @param info Info supplied to [onInitializeAccessibilityNodeInfo].
         */
        override fun onInitializeAccessibilityNodeInfo(
            host: View,
            info: AccessibilityNodeInfoCompat
        ) {
            if (previous != null) previous.onInitializeAccessibilityNodeInfo(host, info)
            else super.onInitializeAccessibilityNodeInfo(host, info)
        }

        /**
         * Adds readable text when TalkBack announces this custom control.
         *
         * @param host Host supplied to [onPopulateAccessibilityEvent].
         * @param event Sensor, UI, or lifecycle event being processed.
         */
        override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) {
            if (previous != null) previous.onPopulateAccessibilityEvent(host, event)
            else super.onPopulateAccessibilityEvent(host, event)
        }

        /**
         * Accessibility ui helpers: Performs accessibility action.
         *
         * @param host Host supplied to [performAccessibilityAction].
         * @param action Action supplied to [performAccessibilityAction].
         * @param args Optional args supplied to [performAccessibilityAction].
         * @return `true` if perform accessibility action, or `false` otherwise.
         */
        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean =
            previous?.performAccessibilityAction(host, action, args)
                ?: super.performAccessibilityAction(host, action, args)

        /**
         * Sends accessibility event.
         *
         * @param host Host supplied to [sendAccessibilityEvent].
         * @param eventType Event type supplied to [sendAccessibilityEvent].
         */
        override fun sendAccessibilityEvent(host: View, eventType: Int) {
            if (previous != null) previous.sendAccessibilityEvent(host, eventType)
            else super.sendAccessibilityEvent(host, eventType)
        }

        /**
         * Sends accessibility event unchecked.
         *
         * @param host Host supplied to [sendAccessibilityEventUnchecked].
         * @param event Sensor, UI, or lifecycle event being processed.
         */
        override fun sendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) {
            if (previous != null) previous.sendAccessibilityEventUnchecked(host, event)
            else super.sendAccessibilityEventUnchecked(host, event)
        }
    })
}

/**
 * Recheck after focus changes and throughout keyboard resizing. Scrolling reveals the field without requesting focus, so it cannot steal input or TalkBack focus from another control.
 *
 * @receiver The modifier chain to extend.
 * @return The resulting [Modifier] for keep visible while typing.
 */
internal fun Modifier.keepVisibleWhileTyping(): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val keyboardBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    // Keeps the private screen awake while an editable field has focus.
    LaunchedEffect(focused, keyboardBottom) {
        if (focused) {
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    this.bringIntoViewRequester(requester).onFocusChanged { focused = it.isFocused }
}

/**
 * UI sizing: every scrollbar is 4dp wide, sits 3dp from the edge, and keeps at least a 28dp thumb. Shared Olive scrollbar for forms and other regular vertically scrolling content.
 *
 * @receiver The modifier chain to extend.
 * @param state Current state instance to read or render.
 * @return The resulting [Modifier] for olive scrollbar.
 */
internal fun Modifier.oliveScrollbar(state: ScrollState): Modifier = drawWithContent {
    drawContent()
    if (state.maxValue <= 0) return@drawWithContent

    val width = 4.dp.toPx()
    val edge = 3.dp.toPx()
    val trackHeight = size.height - edge * 2
    val viewportShare = size.height / (size.height + state.maxValue)
    val thumbHeight = max(28.dp.toPx(), trackHeight * viewportShare).coerceAtMost(trackHeight)
    val progress = state.value.toFloat() / state.maxValue.toFloat()
    val thumbTop = edge + (trackHeight - thumbHeight) * progress
    val x = size.width - width - edge

    drawRoundRect(ScrollTrack, Offset(x, edge), Size(width, trackHeight), CornerRadius(width))
    drawRoundRect(ScrollThumb, Offset(x, thumbTop), Size(width, thumbHeight), CornerRadius(width))
}

/**
 * Include partial rows so the thumb follows each scroll pixel instead of jumping between item indices.
 *
 * @receiver The modifier chain to extend.
 * @param state Current state instance to read or render.
 * @return The resulting [Modifier] for olive scrollbar.
 */
internal fun Modifier.oliveScrollbar(state: LazyListState): Modifier = drawWithContent {
    drawContent()
    val info = state.layoutInfo
    val first = info.visibleItemsInfo.firstOrNull() ?: return@drawWithContent
    val last = info.visibleItemsInfo.lastOrNull() ?: return@drawWithContent
    val start = first.index + (info.viewportStartOffset - first.offset).toFloat()
        .div((first.size + info.mainAxisItemSpacing).coerceAtLeast(1)).coerceIn(0f, 1f)
    val end = last.index + (info.viewportEndOffset - last.offset).toFloat()
        .div((last.size + info.mainAxisItemSpacing).coerceAtLeast(1)).coerceIn(0f, 1f)
    drawLazyScrollbar(
        start,
        end,
        info.totalItemsCount,
        state.canScrollBackward,
        state.canScrollForward
    )
}

/**
 * Measure partial grid rows together; full-width headings and incomplete rows retain their actual item counts.
 *
 * @receiver The modifier chain to extend.
 * @param state Current state instance to read or render.
 * @return The resulting [Modifier] for olive scrollbar.
 */
internal fun Modifier.oliveScrollbar(state: LazyGridState): Modifier = drawWithContent {
    drawContent()
    val info = state.layoutInfo
    val rows = info.visibleItemsInfo.filter { it.row >= 0 }.groupBy { it.row }.toSortedMap()
    val first = rows.entries.firstOrNull() ?: return@drawWithContent
    val last = rows.entries.lastOrNull() ?: return@drawWithContent
    val start =
        first.value.minOf { it.index } + first.value.size * (info.viewportStartOffset - first.value.minOf { it.offset.y }).toFloat()
            .div((first.value.maxOf { it.size.height } + info.mainAxisItemSpacing).coerceAtLeast(1))
            .coerceIn(0f, 1f)
    val end =
        last.value.minOf { it.index } + last.value.size * (info.viewportEndOffset - last.value.minOf { it.offset.y }).toFloat()
            .div((last.value.maxOf { it.size.height } + info.mainAxisItemSpacing).coerceAtLeast(1))
            .coerceIn(0f, 1f)
    drawLazyScrollbar(
        start,
        end,
        info.totalItemsCount,
        state.canScrollBackward,
        state.canScrollForward
    )
}

/**
 * Draws lazy scrollbar.
 *
 * @receiver The Compose drawing scope and its coordinate system.
 * @param start Start supplied to [drawLazyScrollbar].
 * @param end End supplied to [drawLazyScrollbar].
 * @param total Total supplied to [drawLazyScrollbar].
 * @param canBack Whether can back.
 * @param canForward Whether can forward.
 */
private fun DrawScope.drawLazyScrollbar(
    start: Float,
    end: Float,
    total: Int,
    canBack: Boolean,
    canForward: Boolean
) {
    if (total <= 0 || (!canBack && !canForward)) return
    val width = 4.dp.toPx()
    val edge = 3.dp.toPx()
    val trackHeight = size.height - edge * 2
    if (trackHeight <= 0f) return
    val visible = (end - start).coerceIn(0f, total.toFloat())
    val thumbHeight = max(28.dp.toPx(), trackHeight * visible / total).coerceAtMost(trackHeight)
    val range = (total - visible).coerceAtLeast(.001f)
    val progress = when {
        !canBack -> 0f
        !canForward -> 1f
        else -> (start / range).coerceIn(0f, 1f)
    }
    val x = size.width - width - edge
    drawRoundRect(ScrollTrack, Offset(x, edge), Size(width, trackHeight), CornerRadius(width))
    drawRoundRect(
        ScrollThumb,
        Offset(x, edge + (trackHeight - thumbHeight) * progress),
        Size(width, thumbHeight),
        CornerRadius(width)
    )
}

/**
 * Focus a visible control once its window and content are ready, without refocusing on later updates.
 *
 * @param key Lookup key or cryptographic key, or null when not provided.
 * @param ready Whether to apply ready.
 * @return The resulting [FocusRequester] for remember entry focus.
 */
@Composable
internal fun rememberEntryFocus(key: Any?, ready: Boolean = true): androidx.compose.ui.focus.FocusRequester {
    val requester = remember(key) { androidx.compose.ui.focus.FocusRequester() }
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    var requested by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, ready, windowFocused) {
        if (ready && windowFocused && !requested) {
            withFrameNanos { }
            requested = requester.requestFocus()
        }
    }
    return requester
}
