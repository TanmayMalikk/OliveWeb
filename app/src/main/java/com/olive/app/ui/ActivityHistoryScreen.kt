package com.olive.app.ui

import androidx.compose.ui.res.stringResource
import com.olive.app.R

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.text.font.FontWeight

// Tap a range edge to choose its local calendar date first and clock time second.
// UI sizing: date/time controls split the row evenly with 8dp gaps and 14x12dp button content padding.
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ActivityDateTimePicker(label: String, value: Long?, tag: String, onChange: (Long?)->Unit) {
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var pendingDate by remember { mutableStateOf<LocalDate?>(null) }
    val zone=ZoneId.systemDefault()
    val locale= remember { Locale.getDefault() }
    val display=value?.let { DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm",locale).withZone(zone).format(Instant.ofEpochMilli(it)) }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick={showDate=true},modifier=Modifier.weight(1f).testTag(tag),contentPadding=PaddingValues(horizontal=14.dp,vertical=12.dp)) {
            Icon(Icons.Outlined.CalendarMonth,null);Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f),horizontalAlignment=Alignment.Start) {
                Text(label,style=MaterialTheme.typography.labelMedium)
                Text(display ?: "Not selected",color=if(value==null) Muted else MaterialTheme.colorScheme.onSurface)
            }
            Icon(Icons.Outlined.AccessTime,null)
        }
        if(value!=null) TextButton(onClick={onChange(null)}) { Text(stringResource(R.string.clear)) }
    }
    if(showDate) key(value) {
        val initial=value ?: System.currentTimeMillis()
        val localDate=Instant.ofEpochMilli(initial).atZone(zone).toLocalDate()
        val utcDate=localDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val picker=rememberDatePickerState(initialSelectedDateMillis=utcDate)
        DatePickerDialog(onDismissRequest={showDate=false},confirmButton={
            TextButton(onClick={
                pendingDate=picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate() } ?: localDate
                showDate=false;showTime=true
            }) { Text(stringResource(R.string.next)) }
        },dismissButton={TextButton(onClick={showDate=false}) { Text(stringResource(R.string.cancel)) }}) {
            DatePicker(state=picker,modifier=Modifier.testTag("${tag}_calendar"))
        }
    }
    if(showTime) key(pendingDate,value) {
        val initial=value?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: java.time.LocalTime.now(zone)
        val picker=rememberTimePickerState(initialHour=initial.hour,initialMinute=initial.minute,is24Hour=true)
        AlertDialog(onDismissRequest={showTime=false},title={Text(stringResource(R.string.select_time))},text={Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
            TimePicker(state=picker,modifier=Modifier.testTag("${tag}_clock"))
        }},
            confirmButton={TextButton(onClick={
                val date=pendingDate ?: LocalDate.now(zone)
                onChange(date.atTime(picker.hour,picker.minute).atZone(zone).toInstant().toEpochMilli());showTime=false
            }) { Text(stringResource(R.string.apply)) }},dismissButton={TextButton(onClick={showTime=false}) { Text(stringResource(R.string.cancel)) }})
    }
}

// Activity stays grouped on the overview, then See more opens one searchable category at a time.
// UI sizing/text: activity uses 24dp page padding, 14dp row gaps, 16dp cards, titleLarge sections, and bodySmall timestamps.
@Composable internal fun ActivityHistory(session: SessionViewModel) {
    val formatter=remember { DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm:ss z",
        Locale.getDefault()).withZone(ZoneId.systemDefault()) }
    var from by remember { mutableStateOf<Long?>(null) }
    var to by remember { mutableStateOf<Long?>(null) }
    fun inRange(time: Long)=(from==null || time>=from!!) && (to==null || time<=to!!+59_999)
    val settings=session.settingsHistory.filter { inRange(it.timestamp) }
    val triggers=session.triggerHistory.filter { inRange(it.timestamp) }
    val attempts=session.attempts.filter { inRange(it.timestamp) }
    val checkouts=session.checkoutHistory.filter { inRange(it.timestamp) }
    val privacy=session.privacyHistory.filter { inRange(it.timestamp) }
    val selected=session.activityCategory
    fun <T> visible(category: ActivityCategory,rows: List<T>)=if(selected==category) rows else rows.take(5)
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState).testTag("activity_list"),state=listState,contentPadding=PaddingValues(start=24.dp,end=24.dp,top=24.dp,bottom=112.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { TextButton(onClick=session::activityBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,null); Text(if(selected==null) "Safe home" else "All activity") } }
        item { Heading(if(selected==null) "Account activity" else when(selected) {
            ActivityCategory.Settings -> "Settings history";ActivityCategory.Triggers -> "Trigger history"
            ActivityCategory.PinAttempts -> "PIN attempt history";ActivityCategory.Checkouts -> "Checkout attempts"
            ActivityCategory.Privacy -> "Privacy events"
        },"Search activity between optional local date and time values.") }
        item {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                ActivityDateTimePicker("From",from,"activity_from") { from=it }
                ActivityDateTimePicker("To",to,"activity_to") { to=it }
            }
        }
        if(selected==null || selected==ActivityCategory.Settings) {
            item { Text(stringResource(R.string.settings_history),style=MaterialTheme.typography.titleLarge) }
            if(settings.isEmpty()) item { Text(stringResource(R.string.no_settings_activity_yet)) }
            items(visible(ActivityCategory.Settings,settings),key={it.id}) { event ->
                Card(Modifier.fillMaxWidth().testTag("settings_history_row")) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text("${event.area} · ${event.action}",fontWeight= FontWeight.SemiBold)
                    Text(formatter.format(Instant.ofEpochMilli(event.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted)
                    event.changes.forEach { change -> Text("${change.field}: ${change.before.ifBlank { "Empty" }} → ${change.after.ifBlank { "Empty" }}",style=MaterialTheme.typography.bodySmall) }
                } }
            }
            if(selected==null && settings.size>5) item { TextButton(onClick={session.showActivityCategory(ActivityCategory.Settings)}) { Text(stringResource(R.string.see_more)) } }
        }
        if(selected==null || selected==ActivityCategory.Triggers) {
            item { HorizontalDivider();Text(stringResource(R.string.trigger_history),style=MaterialTheme.typography.titleLarge) }
            if(triggers.isEmpty()) item { Text(stringResource(R.string.no_trigger_activity_yet)) }
            items(visible(ActivityCategory.Triggers,triggers),key={it.id}) { event ->
                Card(Modifier.fillMaxWidth().testTag("trigger_history_row")) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(event.source,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text(formatter.format(Instant.ofEpochMilli(event.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted)
                    if(event.pinAttempts>0) Text("${stringResource(R.string.pin_attempts)}: ${event.pinAttempts} · ${event.lastResult}")
                } }
            }
            if(selected==null && triggers.size>5) item { TextButton(onClick={session.showActivityCategory(ActivityCategory.Triggers)}) { Text(stringResource(R.string.see_more)) } }
        }
        if(selected==null || selected==ActivityCategory.PinAttempts) {
            item { HorizontalDivider();Text(stringResource(R.string.pin_attempt_history),style=MaterialTheme.typography.titleLarge) }
            if(attempts.isEmpty()) item { Text(stringResource(R.string.no_failed_attempts_recorded)) }
            items(visible(ActivityCategory.PinAttempts,attempts),key={it.id}) { attempt ->
                Card(Modifier.fillMaxWidth().testTag("attempt_row")) { Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.incorrect_pin));Text(formatter.format(Instant.ofEpochMilli(attempt.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted)
                } }
            }
            if(selected==null && attempts.size>5) item { TextButton(onClick={session.showActivityCategory(ActivityCategory.PinAttempts)}) { Text(stringResource(R.string.see_more)) } }
        }
        if(selected==null || selected==ActivityCategory.Checkouts) {
            item { HorizontalDivider();Text(stringResource(R.string.checkout_attempts),style=MaterialTheme.typography.titleLarge) }
            if(checkouts.isEmpty()) item { Text(stringResource(R.string.no_checkout_attempts_yet)) }
            items(visible(ActivityCategory.Checkouts,checkouts),key={it.id}) { event ->
                Card(Modifier.fillMaxWidth().testTag("safe_checkout_history_row")) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(formatter.format(Instant.ofEpochMilli(event.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted)
                    event.foods.forEach { Text("${it.quantity} × ${it.food}") };Text(event.error)
                } }
            }
            if(selected==null && checkouts.size>5) item { TextButton(onClick={session.showActivityCategory(ActivityCategory.Checkouts)}) { Text(stringResource(R.string.see_more)) } }
        }
        if(selected==null || selected==ActivityCategory.Privacy) {
            item { HorizontalDivider();Text(stringResource(R.string.privacy_events),style=MaterialTheme.typography.titleLarge) }
            if(privacy.isEmpty()) item { Text(stringResource(R.string.no_capture_events_reported_by_android)) }
            items(visible(ActivityCategory.Privacy,privacy),key={it.id}) { event ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(event.action);Text(formatter.format(Instant.ofEpochMilli(event.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted) } }
            }
            if(selected==null && privacy.size>5) item { TextButton(onClick={session.showActivityCategory(ActivityCategory.Privacy)}) { Text(stringResource(R.string.see_more)) } }
        }
    }
}
