package com.olive.app.ui.users.user.notes

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.olive.app.R
import com.olive.app.data.account.parseIncidentDate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private enum class IncidentPicker { Date, Time }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IncidentDateTimeFields(
    date: String,
    time: String,
    enabled: Boolean,
    onDateChange: (String) -> Unit,
    onTimeChange: (String) -> Unit
) {
    var openPicker by remember { mutableStateOf<IncidentPicker?>(null) }
    val today = LocalDate.now()
    val initialDate = remember(date, today) { parseIncidentDate(date, today) }
    val initialTime = remember(time) { runCatching { LocalTime.parse(time) }.getOrNull() }
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val locale = LocalConfiguration.current.locales[0]
    val timePattern = DateFormat.getBestDateTimePattern(locale, if (use24Hour) "Hm" else "hm")
    val dateLabel = stringResource(R.string.incident_date)
    val timeLabel = stringResource(R.string.incident_time)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IncidentPickerField(
            label = dateLabel,
            value = initialDate?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) ?: date,
            tag = "incident_date",
            enabled = enabled,
            icon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null) },
            clearLabel = stringResource(R.string.incident_clear_date),
            onClear = { onDateChange("") },
        ) { openPicker = IncidentPicker.Date }
        if (date.isNotBlank() && initialDate == null) {
            Text(stringResource(R.string.incident_date_invalid), color = MaterialTheme.colorScheme.error)
        }
        IncidentPickerField(
            label = timeLabel,
            value = initialTime?.format(DateTimeFormatter.ofPattern(timePattern, locale)) ?: time,
            tag = "incident_time",
            enabled = enabled,
            icon = { Icon(Icons.Outlined.AccessTime, contentDescription = null) },
            clearLabel = stringResource(R.string.incident_clear_time),
            onClear = { onTimeChange("") },
        ) { openPicker = IncidentPicker.Time }
    }

    when (openPicker) {
        IncidentPicker.Date -> {
            val calendarDate = initialDate ?: today
            val picker = rememberDatePickerState(
                initialSelectedDateMillis = calendarDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                yearRange = minOf(1900, calendarDate.year)..today.year,
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        !Instant.ofEpochMilli(utcTimeMillis).atOffset(ZoneOffset.UTC).toLocalDate().isAfter(today)
                    override fun isSelectableYear(year: Int): Boolean = year <= today.year
                },
            )
            DatePickerDialog(
                onDismissRequest = { openPicker = null },
                confirmButton = {
                    TextButton(
                        enabled = picker.selectedDateMillis != null,
                        modifier = Modifier.testTag("incident_date_apply"),
                        onClick = {
                            picker.selectedDateMillis?.let {
                                onDateChange(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().toString())
                            }
                            openPicker = null
                        }
                    ) { Text(stringResource(R.string.apply)) }
                },
                dismissButton = {
                    TextButton(onClick = { openPicker = null }) { Text(stringResource(R.string.cancel)) }
                }
            ) {
                DatePicker(
                    state = picker,
                    modifier = Modifier.testTag("incident_calendar"),
                    showModeToggle = false
                )
            }
        }
        IncidentPicker.Time -> {
            val current = initialTime ?: LocalTime.now()
            val picker = rememberTimePickerState(current.hour, current.minute, is24Hour = use24Hour)
            Dialog(
                onDismissRequest = { openPicker = null },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier.padding(16.dp).widthIn(max = 400.dp).fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            stringResource(R.string.select_time),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.align(Alignment.Start).padding(bottom = 16.dp)
                        )
                        TimePicker(
                            state = picker,
                            modifier = Modifier.testTag("incident_clock"),
                            layoutType = TimePickerLayoutType.Vertical
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { openPicker = null }) { Text(stringResource(R.string.cancel)) }
                            TextButton(
                                modifier = Modifier.testTag("incident_time_apply"),
                                onClick = {
                                    onTimeChange(LocalTime.of(picker.hour, picker.minute).toString())
                                    openPicker = null
                                }
                            ) { Text(stringResource(R.string.apply)) }
                        }
                    }
                }
            }
        }
        null -> Unit
    }
}

@Composable
private fun IncidentPickerField(
    label: String,
    value: String,
    tag: String,
    enabled: Boolean,
    icon: @Composable () -> Unit,
    clearLabel: String,
    onClear: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = ButtonDefaults.outlinedButtonColors()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (enabled) colors.containerColor else colors.disabledContainerColor,
        contentColor = if (enabled) colors.contentColor else colors.disabledContentColor,
        border = ButtonDefaults.outlinedButtonBorder(enabled),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f)
                    .testTag(tag)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                icon()
                Column {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Text(
                        value.ifBlank { stringResource(R.string.olive_not_selected) },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            if (value.isNotBlank()) {
                IconButton(
                    onClick = onClear,
                    enabled = enabled,
                    modifier = Modifier.padding(end = 4.dp).testTag("${tag}_clear"),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = clearLabel)
                }
            }
        }
    }
}
