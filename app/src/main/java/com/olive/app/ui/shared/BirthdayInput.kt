package com.olive.app.ui.shared

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

private const val FIRST_BIRTH_YEAR = 1927
private const val YEARS_PER_GROUP = 10

internal fun birthdayYears(today: LocalDate): List<IntRange> =
    (maxOf(FIRST_BIRTH_YEAR, today.minusYears(130).year)..today.year)
        .chunked(YEARS_PER_GROUP).asReversed().map { it.first()..it.last() }

@Composable
internal fun rememberBirthdayInput(initialDate: String = "") = rememberSaveable(initialDate) {
    mutableStateOf(initialDate)
}

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

private fun Int.spokenYear(): String = toString().toList().joinToString(" ")
