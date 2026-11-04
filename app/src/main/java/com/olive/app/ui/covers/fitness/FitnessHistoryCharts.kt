// Fitness cover users: draws readable 14-day activity and nutrition history graphs.
package com.olive.app.ui.covers.fitness

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.ui.shared.Ink
import com.olive.app.ui.shared.LightSage
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.Paper
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private val DistanceGreen = Color(0xFF78935C)
private val BurnedOrange = Color(0xFFB56D44)
private val EatenBrown = Color(0xFF765B3D)
private val ProteinGreen = Color(0xFF45613C)
private val CarbohydrateGold = Color(0xFFC1963E)
private val FatPlum = Color(0xFF875E78)

private data class HistorySeries(
    val name: String,
    val color: Color,
    val values: List<Float>,
)

private data class ChartAxisScale(
    val maximum: Float,
    val increment: Float,
) {
    val labelsDescending: List<Float>
        get() {
            val intervalCount = (maximum / increment).roundToInt().coerceAtLeast(1)
            return (intervalCount downTo 0).map { interval -> increment * interval }
        }
}

// Draws the fitness history charts for the fitness cover.
@Composable
internal fun FitnessHistoryCharts(days: List<FitnessHistoryDay>) {
    val stepsUnit = stringResource(R.string.fitness_steps_unit)
    val kilometresUnit = stringResource(R.string.fitness_kilometres_unit)
    val kilocaloriesUnit = stringResource(R.string.fitness_kilocalories_unit)
    val gramsUnit = stringResource(R.string.fitness_grams_unit)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.fitness_history_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Ink,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.fitness_history_description),
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
        }

        HistoryLineChartCard(
            title = stringResource(R.string.fitness_steps),
            unit = stepsUnit,
            days = days,
            series = listOf(
                HistorySeries(
                    stringResource(R.string.fitness_steps),
                    OliveGreen,
                    days.map { it.steps.toFloat() },
                )
            ),
            valueText = { it.roundToInt().toString() },
            useBars = true,
        )
        HistoryLineChartCard(
            title = stringResource(R.string.distance),
            unit = kilometresUnit,
            days = days,
            series = listOf(
                HistorySeries(
                    stringResource(R.string.distance),
                    DistanceGreen,
                    days.map(FitnessHistoryDay::distanceKm),
                )
            ),
            valueText = { String.format(Locale.ROOT, "%.2f", it) },
            useBars = true,
        )
        HistoryLineChartCard(
            title = stringResource(R.string.calories),
            unit = kilocaloriesUnit,
            days = days,
            series = listOf(
                HistorySeries(
                    stringResource(R.string.fitness_calories_burned),
                    BurnedOrange,
                    days.map(FitnessHistoryDay::caloriesBurned),
                ),
                HistorySeries(
                    stringResource(R.string.fitness_calories_eaten),
                    EatenBrown,
                    days.map(FitnessHistoryDay::caloriesEaten),
                ),
            ),
            valueText = { it.roundToInt().toString() },
            useBars = true,
        )
        HistoryLineChartCard(
            title = stringResource(R.string.fitness_macros),
            unit = gramsUnit,
            days = days,
            series = listOf(
                HistorySeries(
                    stringResource(R.string.protein),
                    ProteinGreen,
                    days.map(FitnessHistoryDay::proteinGrams),
                ),
                HistorySeries(
                    stringResource(R.string.fitness_carbs),
                    CarbohydrateGold,
                    days.map(FitnessHistoryDay::carbohydrateGrams),
                ),
                HistorySeries(
                    stringResource(R.string.fat),
                    FatPlum,
                    days.map(FitnessHistoryDay::fatGrams),
                ),
            ),
            valueText = { it.roundToInt().toString() },
            useBars = false,
        )
    }
}

// Groups a graph, its legend, and the latest values for one fitness measure.
@Composable
private fun HistoryLineChartCard(
    title: String,
    unit: String,
    days: List<FitnessHistoryDay>,
    series: List<HistorySeries>,
    valueText: (Float) -> String,
    useBars: Boolean,
) {
    val highestValue = series.maxOfOrNull { line -> line.values.maxOrNull() ?: 0f }
        ?.coerceAtLeast(1f) ?: 1f
    val axisScale = calculateChartAxisScale(highestValue)
    val chartDescription = pluralStringResource(
        R.plurals.fitness_chart_description,
        days.size,
        title,
        days.size,
    )
    val averageDescriptions = series.map { line ->
        val average = if (line.values.isEmpty()) 0f else line.values.average().toFloat()
        stringResource(
            R.string.fitness_chart_average,
            line.name,
            valueText(average),
            unit,
        )
    }
    val overviewDescription = buildString {
        append(chartDescription)
        append(' ')
        averageDescriptions.forEach { average ->
            append(average)
            append(' ')
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Paper),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                    contentDescription = overviewDescription
                },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.fitness_history_daily_total),
                        color = Muted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(
                    stringResource(
                        R.string.fitness_history_max,
                        valueText(highestValue),
                        unit,
                    ),
                    color = Muted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                series.forEach { line -> HistoryLegendItem(line, unit, valueText) }
            }

            HistoryChart(
                series = series,
                axisScale = axisScale,
                description = chartDescription,
                useBars = useBars,
                valueText = valueText,
            )
            HistoryDateAxis(days)
        }
    }
}

// Names one line in the graph and shows its latest recorded value.
@Composable
private fun HistoryLegendItem(
    series: HistorySeries,
    unit: String,
    valueText: (Float) -> String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(Modifier.size(9.dp).background(series.color, CircleShape))
        Column {
            Text(series.name, color = Ink, style = MaterialTheme.typography.labelMedium)
            Text(
                stringResource(
                    R.string.fitness_history_latest,
                    valueText(series.values.lastOrNull() ?: 0f),
                    unit,
                ),
                color = Muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// Draws the graph lines or bars and labels its vertical scale.
@Composable
private fun HistoryChart(
    series: List<HistorySeries>,
    axisScale: ChartAxisScale,
    description: String,
    useBars: Boolean,
    valueText: (Float) -> String,
) {
    Row(Modifier.fillMaxWidth().height(145.dp)) {
        Column(
            modifier = Modifier.width(42.dp).fillMaxHeight().padding(vertical = 8.dp)
                .clearAndSetSemantics { },
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            // Put the largest scale label at the top of the graph.
            axisScale.labelsDescending.forEach { axisValue ->
                Text(
                    text = valueText(axisValue),
                    color = Muted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Canvas(
            modifier = Modifier.weight(1f).fillMaxHeight().clearAndSetSemantics {
                contentDescription = description
            }
        ) {
            val horizontalPadding = 6.dp.toPx()
            val verticalPadding = 8.dp.toPx()
            val chartWidth = size.width - horizontalPadding * 2f
            val chartHeight = size.height - verticalPadding * 2f

            val axisLabels = axisScale.labelsDescending
            // Draw a guide line beside every vertical-axis label.
            axisLabels.forEachIndexed { index, _ ->
                val y = verticalPadding + chartHeight * index / (axisLabels.size - 1).coerceAtLeast(1).toFloat()
                drawLine(
                    color = LightSage.copy(alpha = 0.7f),
                    start = Offset(horizontalPadding, y),
                    end = Offset(size.width - horizontalPadding, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            // Distance and calorie charts use bars to compare each day.
            if (useBars) {
                drawHistoryBars(
                    series,
                    axisScale.maximum,
                    horizontalPadding,
                    verticalPadding,
                    chartWidth,
                    chartHeight,
                )
                return@Canvas
            }

            // Draw one colored line for each measure in the chart.
            series.forEach { line ->
                if (line.values.isEmpty()) return@forEach
                val path = Path()
                // Place each reading at its matching day on the graph.
                line.values.forEachIndexed { index, value ->
                    val fraction = if (line.values.size == 1) 0.5f else {
                        index.toFloat() / (line.values.size - 1).toFloat()
                    }
                    val x = horizontalPadding + chartWidth * fraction
                    val safeValue = value.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
                    val y = verticalPadding + chartHeight * (1f - safeValue / axisScale.maximum)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = line.color,
                    style = Stroke(
                        width = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
                // Mark the daily readings so they are easy to pick out.
                line.values.forEachIndexed { index, value ->
                    val fraction = if (line.values.size == 1) 0.5f else {
                        index.toFloat() / (line.values.size - 1).toFloat()
                    }
                    val x = horizontalPadding + chartWidth * fraction
                    val safeValue = value.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
                    val y = verticalPadding + chartHeight * (1f - safeValue / axisScale.maximum)
                    drawCircle(line.color, radius = 3.dp.toPx(), center = Offset(x, y))
                }
            }
        }
    }
}

// Fitness history graphs: chooses readable, even Y-axis increments instead of arbitrary fractions.
private fun calculateChartAxisScale(highestValue: Float): ChartAxisScale {
    val safeHighest = highestValue.takeIf(Float::isFinite)?.coerceAtLeast(1f) ?: 1f
    val roughIncrement = safeHighest / 5f
    val magnitude = 10f.pow(floor(log10(roughIncrement)))
    val normalizedIncrement = roughIncrement / magnitude
    val roundedIncrement = when {
        normalizedIncrement <= 1f -> 1f
        normalizedIncrement <= 2f -> 2f
        normalizedIncrement <= 2.5f -> 2.5f
        normalizedIncrement <= 5f -> 5f
        else -> 10f
    }
    val increment = roundedIncrement * magnitude
    val maximum = ceil(safeHighest / increment) * increment
    return ChartAxisScale(maximum = maximum, increment = increment)
}

// Draws separate bars for each daily measure in the fitness history chart.
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHistoryBars(
    series: List<HistorySeries>,
    highestValue: Float,
    horizontalPadding: Float,
    verticalPadding: Float,
    chartWidth: Float,
    chartHeight: Float,
) {
    val dayCount = series.maxOfOrNull { it.values.size } ?: return
    if (dayCount == 0) return
    val groupWidth = chartWidth / dayCount.toFloat()
    val barCount = series.size.coerceAtLeast(1)
    val barWidth = (groupWidth * 0.58f / barCount).coerceAtLeast(2.dp.toPx())

    // Give each measure its own position inside the day's bar group.
    series.forEachIndexed { seriesIndex, line ->
        // Skip empty readings and draw the rest from the chart baseline.
        line.values.forEachIndexed { dayIndex, rawValue ->
            val value = rawValue.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
            if (value == 0f) return@forEachIndexed
            val centreOffset = (seriesIndex - (barCount - 1) / 2f) * barWidth
            val x = horizontalPadding + groupWidth * (dayIndex + 0.5f) + centreOffset
            val baseline = verticalPadding + chartHeight
            val top = verticalPadding + chartHeight * (1f - value / highestValue)
            drawLine(
                color = line.color,
                start = Offset(x, baseline),
                end = Offset(x, top),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

// Labels the first, middle, and last day shown in the graph.
@Composable
private fun HistoryDateAxis(days: List<FitnessHistoryDay>) {
    if (days.isEmpty()) return
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = DateTimeFormatter.ofPattern("MMM d", locale)
    val middle = days[days.lastIndex / 2].date.format(dateFormat)
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
        Spacer(Modifier.width(50.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(days.first().date.format(dateFormat), color = Muted, style = MaterialTheme.typography.labelSmall)
            Text(middle, color = Muted, style = MaterialTheme.typography.labelSmall)
            Text(days.last().date.format(dateFormat), color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}
