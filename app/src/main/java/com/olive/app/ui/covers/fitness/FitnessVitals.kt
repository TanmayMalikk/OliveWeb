// Fitness cover users with a connected watch: shows today's heart-rate summary.
package com.olive.app.ui.covers.fitness

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.ui.shared.Ink
import com.olive.app.ui.shared.LightSage
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.PaleOlive
import com.olive.app.ui.shared.Paper
import com.olive.app.ui.shared.oliveScrollbar
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import kotlin.math.ceil
import kotlin.math.floor

@Composable
internal fun FitnessVitalsPage(snapshot: FitnessSnapshot) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val vitals = snapshot.heartRateVitals
    LazyColumn(
        modifier = Modifier.fillMaxSize().oliveScrollbar(listState).testTag("fitness_vitals"),
        state = listState,
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = stringResource(R.string.fitness_vitals),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.fitness_vitals_today_description),
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item { HeartRateGraphCard(snapshot.heartRateHistory) }

        if (vitals != null) {
            item {
                WatchStatusCard(vitals, snapshot.watchConnected)
            }
            item {
                VitalsGrid(vitals)
            }
            item {
                Text(
                    text = stringResource(R.string.fitness_vitals_resting_explanation),
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            item {
                WatchMessageCard(
                    message = when {
                        snapshot.watchConnected -> stringResource(
                            R.string.fitness_vitals_waiting_for_reading
                        )
                        snapshot.watchSeenWithinFourteenDays -> stringResource(
                            R.string.fitness_vitals_no_recent_readings
                        )
                        else -> stringResource(R.string.fitness_vitals_watch_required)
                    }
                )
            }
        }
    }
}

@Composable
private fun HeartRateGraphCard(history: List<HeartRateVitals>) {
    val noData = stringResource(R.string.fitness_vitals_no_data)
    val overallLowest = history.minOfOrNull(HeartRateVitals::lowestBpm) ?: 0
    val overallPeak = history.maxOfOrNull(HeartRateVitals::peakBpm) ?: 0
    val totalSamples = history.sumOf(HeartRateVitals::sampleCount)
    val overallAverage = if (totalSamples == 0) 0 else {
        history.sumOf { it.averageBpm * it.sampleCount } / totalSamples
    }
    val graphDescription = if (history.isEmpty()) {
        noData
    } else {
        stringResource(
            R.string.fitness_vitals_graph_description,
            history.size,
            overallLowest,
            overallPeak,
        )
    }
    val minimum = if (history.isEmpty()) 50f else {
        (floor(overallLowest / 25f) * 25f).coerceAtLeast(0f)
    }
    val maximum = if (history.isEmpty()) 200f else {
        (ceil(overallPeak / 25f) * 25f).coerceAtLeast(minimum + 50f)
    }
    val axisLabels = generateSequence(minimum.toInt()) { previous ->
        (previous + 25).takeIf { it <= maximum.toInt() }
    }.toList().asReversed()
    val endDate = LocalDate.now()
    val startDate = endDate.minusDays(13)
    val locale = LocalConfiguration.current.locales[0]
    val shortDate = DateTimeFormatter.ofPattern("MMM d", locale)

    Card(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = graphDescription
        },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Paper),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.fitness_heart_rate_graph),
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (history.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.fitness_vitals_range,
                        overallLowest,
                        overallPeak,
                    ),
                    color = OliveGreen,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.fitness_vitals_average, overallAverage),
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val left = 42.dp.toPx()
                    val right = size.width - 6.dp.toPx()
                    val top = 8.dp.toPx()
                    val bottom = size.height - 22.dp.toPx()
                    val width = right - left
                    val height = bottom - top

                    axisLabels.forEachIndexed { index, _ ->
                        val y = top + height * index / (axisLabels.size - 1).coerceAtLeast(1).toFloat()
                        drawLine(
                            color = LightSage,
                            start = Offset(left, y),
                            end = Offset(right, y),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    if (history.isNotEmpty()) {
                        history.forEach { day ->
                            val dayIndex = java.time.temporal.ChronoUnit.DAYS
                                .between(startDate, day.date).toInt().coerceIn(0, 13)
                            val x = left + width * dayIndex / 13f
                            val lowFraction = (day.lowestBpm - minimum) / (maximum - minimum)
                            val peakFraction = (day.peakBpm - minimum) / (maximum - minimum)
                            val isLatest = day == history.last()
                            drawCircle(
                                color = if (isLatest) OliveGreen else OliveGreen.copy(alpha = 0.55f),
                                radius = if (isLatest) 6.dp.toPx() else 4.dp.toPx(),
                                center = Offset(x, bottom - height * peakFraction),
                            )
                            drawLine(
                                color = if (isLatest) OliveGreen else OliveGreen.copy(alpha = 0.55f),
                                start = Offset(x, bottom - height * lowFraction),
                                end = Offset(x, bottom - height * peakFraction),
                                strokeWidth = if (isLatest) 12.dp.toPx() else 8.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.align(Alignment.CenterStart).height(148.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    axisLabels.forEach { label ->
                        Text(label.toString(), color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (history.isEmpty()) {
                    Text(
                        text = noData,
                        modifier = Modifier.clip(RoundedCornerShape(18.dp))
                            .background(Paper.copy(alpha = 0.92f)).padding(16.dp),
                        color = Muted,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 42.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(startDate.format(shortDate), color = Muted, style = MaterialTheme.typography.labelSmall)
                Text(endDate.minusDays(6).format(shortDate), color = Muted, style = MaterialTheme.typography.labelSmall)
                Text(endDate.format(shortDate), color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun WatchStatusCard(vitals: HeartRateVitals, watchConnected: Boolean) {
    val locale = LocalConfiguration.current.locales[0]
    val dateText = if (vitals.date == LocalDate.now()) {
        stringResource(R.string.today)
    } else {
        vitals.date.format(
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(PaleOlive).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "•",
            color = if (watchConnected) OliveGreen else Muted,
            style = MaterialTheme.typography.headlineMedium,
        )
        Column {
            Text(
                text = stringResource(
                    if (watchConnected) {
                        R.string.fitness_watch_connected
                    } else {
                        R.string.fitness_watch_not_connected
                    }
                ),
                color = Ink,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.fitness_vitals_data_date, dateText),
                color = Muted,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = stringResource(
                    R.string.fitness_vitals_updated,
                    DateFormat.getTimeInstance(DateFormat.SHORT)
                        .format(Date(vitals.lastUpdatedAt)),
                ),
                color = Muted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun VitalsGrid(vitals: HeartRateVitals) {
    val bpm = stringResource(R.string.fitness_bpm)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VitalsMetricCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.fitness_peak_heart_rate),
                value = vitals.peakBpm.toString(),
                unit = bpm,
            )
            VitalsMetricCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.fitness_lowest_heart_rate),
                value = vitals.lowestBpm.toString(),
                unit = bpm,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VitalsMetricCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.fitness_resting_heart_rate),
                value = vitals.restingBpm?.toString() ?: "—",
                unit = bpm,
            )
            VitalsMetricCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.fitness_average_heart_rate),
                value = vitals.averageBpm.toString(),
                unit = bpm,
            )
        }
    }
}

@Composable
private fun VitalsMetricCard(
    modifier: Modifier,
    label: String,
    value: String,
    unit: String,
) {
    Card(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label, $value $unit"
        },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Paper),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, color = Muted, style = MaterialTheme.typography.labelLarge)
            Text(
                text = value,
                color = Ink,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(unit, color = OliveGreen, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun WatchMessageCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = LightSage),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(20.dp),
            color = Ink,
        )
    }
}
