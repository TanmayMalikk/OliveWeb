// Contains the stat plot dialog code for this part of Olive.
package com.olive.app.ui.covers.calculator

import com.olive.app.ui.shared.*

import com.olive.app.ui.shared.rememberResourceText

import androidx.compose.foundation.verticalScroll

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import com.olive.app.R

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.apache.commons.math3.distribution.NormalDistribution
import kotlin.math.*

// Groups the data and actions for stat plot type.
private enum class StatPlotType(val label: Int) {
    Scatter(R.string.ui_scatter), XYLine(R.string.ui_xy_line), Histogram(
        R.string.ui_histogram
    ),
    Box(R.string.ui_box_plot), ModifiedBox(R.string.ui_modified_box), Normal(R.string.ui_normal_probability)
}

// Groups the data and actions for stat axis.
internal data class StatAxis(val minimum: Double, val maximum: Double, val increment: Double)

// Pick clean increments from the data span so axes use values such as 1, 5, 10, 25, or 50.
internal fun statAxis(
    minValue: Double,
    maxValue: Double,
    targetTicks: Int = 5,
    wholeNumbers: Boolean = false
): StatAxis {
    var low = minValue
    var high = maxValue
    if (low == high) {
        val spread = max(1.0, abs(low) * .1)
        low -= spread
        high += spread
    }
    val raw = (high - low) / targetTicks.coerceAtLeast(2)
    val magnitude = 10.0.pow(floor(log10(raw.coerceAtLeast(1e-12))))
    val normalized = raw / magnitude
    var increment = when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.5 -> 2.5
        normalized <= 5.0 -> 5.0
        else -> 10.0
    } * magnitude
    if (wholeNumbers) {
        val wholeRaw = raw.coerceAtLeast(1.0)
        val wholeMagnitude = 10.0.pow(floor(log10(wholeRaw)))
        increment = when (wholeRaw / wholeMagnitude) {
            in 0.0..1.0 -> 1.0
            in 1.0..2.0 -> 2.0
            in 2.0..5.0 -> 5.0
            else -> 10.0
        } * wholeMagnitude
    }
    val minimum = floor(low / increment) * increment
    val maximum = ceil(high / increment) * increment
    return StatAxis(minimum, maximum.takeIf { it > minimum } ?: (minimum + increment), increment)
}

// Statistical plots use typed lists and render locally, so private data never leaves the calculator.
@Composable
internal fun StatPlotDialog(onClose: () -> Unit) {
    val uiText = rememberResourceText()
    var type by remember { mutableStateOf(StatPlotType.Scatter) }
    var xText by remember { mutableStateOf("1,2,3,4,5") }
    var yText by remember { mutableStateOf("2,4,3,7,8") }
    var points by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var values by remember { mutableStateOf<List<Double>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.statistical_plots)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatPlotType.entries.forEach { item ->
                        FilterChip(
                            selected = type == item,
                            onClick = {
                                type = item
                                points = emptyList()
                                values = emptyList()
                                error =
                                    null
                            },
                            label = { Text(uiText(item.label)) })
                    }
                }
                if (type == StatPlotType.Scatter || type == StatPlotType.XYLine) {
                    OutlinedTextField(
                        xText,
                        { xText = it.take(500) },
                        label = { Text(stringResource(R.string.x_values)) },
                        modifier = Modifier.fillMaxWidth().keepVisibleWhileTyping()
                    )
                    OutlinedTextField(
                        yText,
                        { yText = it.take(500) },
                        label = { Text(stringResource(R.string.y_values)) },
                        modifier = Modifier.fillMaxWidth().keepVisibleWhileTyping()
                    )
                } else OutlinedTextField(
                    xText,
                    { xText = it.take(500) },
                    label = { Text(stringResource(R.string.data_values)) },
                    modifier = Modifier.fillMaxWidth().keepVisibleWhileTyping()
                )
                Button(onClick = {
                    runCatching {
                        fun parse(text: String) =
                            normalizeCalculatorNotation(text).split(Regex("[,\\s]+"))
                                .filter { it.isNotBlank() }
                                .map(::parseCalculatorNumber).also { require(it.isNotEmpty()) }

                        val x = parse(xText)
                        if (type == StatPlotType.Scatter || type == StatPlotType.XYLine) {
                            val y =
                                parse(yText)
                            require(x.size == y.size) { uiText(R.string.ui_x_and_y_need_the_same_number_of_values) }
                            points =
                                x.zip(y)
                            values = emptyList()
                        } else {
                            values = x
                            points = emptyList()
                        }
                    }.onSuccess { error = null }
                        .onFailure { error = uiText.failure(it, R.string.ui_check_the_data_values) }
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.plot)) }
                if (points.isNotEmpty() || values.isNotEmpty()) StatPlotCanvas(type, points, values)
                error?.let { AccessibleMessage(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.done)) } })
}

@Composable
private fun StatPlotCanvas(
    type: StatPlotType,
    points: List<Pair<Double, Double>>,
    values: List<Double>
) {
    val uiText = rememberResourceText()
    val label = uiText(type.label)
    val description = if (points.isNotEmpty()) points.joinToString("; ") {
        "X: ${formatGraphNumber(it.first)}, Y: ${
            formatGraphNumber(it.second)
        }"
    }
    else values.joinToString(", ", transform = ::formatGraphNumber)
    Canvas(
        Modifier.fillMaxWidth().height(280.dp).background(WarmWhite)
            .semantics { contentDescription = "$label: $description" }) {
        val left = 42.dp.toPx()
        val top = 16.dp.toPx()
        val right = size.width - 12.dp.toPx()
        val bottom = size.height - 32.dp.toPx()
        val width = (right - left).coerceAtLeast(1f)
        val height = (bottom - top).coerceAtLeast(1f)
        if (type == StatPlotType.Scatter || type == StatPlotType.XYLine) {
            val xAxis = statAxis(points.minOf { it.first }, points.maxOf { it.first })
            val yAxis = statAxis(points.minOf { it.second }, points.maxOf { it.second })
            drawStatAxes(xAxis, yAxis, left, top, right, bottom)
            fun plot(pair: Pair<Double, Double>) = Offset(
                left + ((pair.first - xAxis.minimum) / (xAxis.maximum - xAxis.minimum) * width).toFloat(),
                bottom - ((pair.second - yAxis.minimum) / (yAxis.maximum - yAxis.minimum) * height).toFloat(),
            )

            val plotted = points.map(::plot)
            if (type == StatPlotType.XYLine) plotted.zipWithNext()
                .forEach { drawLine(OliveGreen, it.first, it.second, 3f) }
            plotted.forEach { drawCircle(OliveGreen, 6f, it) }
        } else if (type == StatPlotType.Histogram) {
            val bins = ceil(sqrt(values.size.toDouble())).toInt().coerceAtLeast(2)
            val rawMin = values.min()
            val rawMax = values.max()
            val span = (rawMax - rawMin).takeUnless { it == 0.0 } ?: 1.0
            val counts = IntArray(bins)
            values.forEach {
                counts[((it - rawMin) / span * bins).toInt().coerceIn(0, bins - 1)]++
            }
            val xAxis = statAxis(rawMin, rawMax)
            val yAxis = statAxis(0.0, counts.max().coerceAtLeast(1).toDouble(), wholeNumbers = true)
            drawStatAxes(xAxis, yAxis, left, top, right, bottom)
            counts.forEachIndexed { index, count ->
                val valueStart = rawMin + span * index / bins
                val valueEnd = rawMin + span * (index + 1) / bins
                val barLeft =
                    left + ((valueStart - xAxis.minimum) / (xAxis.maximum - xAxis.minimum) * width).toFloat()
                val barRight =
                    left + ((valueEnd - xAxis.minimum) / (xAxis.maximum - xAxis.minimum) * width).toFloat()
                val barTop =
                    bottom - ((count - yAxis.minimum) / (yAxis.maximum - yAxis.minimum) * height).toFloat()
                drawRect(
                    OliveGreen,
                    Offset(barLeft, barTop),
                    Size((barRight - barLeft - 1f).coerceAtLeast(1f), bottom - barTop)
                )
            }
        } else if (type == StatPlotType.Normal) {
            val sorted = values.sorted()
            val normal = NormalDistribution()
            val scores =
                sorted.indices.map { normal.inverseCumulativeProbability((it + .5) / sorted.size) }
            val xAxis = statAxis(sorted.min(), sorted.max())
            val yAxis = statAxis(scores.min(), scores.max())
            drawStatAxes(xAxis, yAxis, left, top, right, bottom)
            sorted.indices.forEach { index ->
                val x =
                    left + ((sorted[index] - xAxis.minimum) / (xAxis.maximum - xAxis.minimum) * width).toFloat()
                val y =
                    bottom - ((scores[index] - yAxis.minimum) / (yAxis.maximum - yAxis.minimum) * height).toFloat()
                drawCircle(OliveGreen, 6f, Offset(x, y))
            }
        } else {
            val sorted = values.sorted()
            fun percentile(p: Double): Double {
                val position = (sorted.size - 1) * p
                val low = floor(position).toInt()
                val high =
                    ceil(position).toInt()
                return sorted[low] + (sorted[high] - sorted[low]) * (position - low)
            }

            val q1 = percentile(.25)
            val median = percentile(.5)
            val q3 = percentile(.75)
            val iqr = q3 - q1
            val displayed =
                if (type == StatPlotType.ModifiedBox) sorted.filter { it >= q1 - 1.5 * iqr && it <= q3 + 1.5 * iqr } else sorted
            val xAxis = statAxis(sorted.min(), sorted.max())
            drawStatAxes(xAxis, null, left, top, right, bottom)
            // Runs the calculation for the x coordinate or input value.
            fun x(value: Double) =
                left + ((value - xAxis.minimum) / (xAxis.maximum - xAxis.minimum) * width).toFloat()

            val centre = (top + bottom) / 2f
            val boxTop = centre - 36.dp.toPx()
            val boxHeight = 72.dp.toPx()
            drawLine(
                OliveGreen,
                Offset(x(displayed.min()), centre),
                Offset(x(displayed.max()), centre),
                3f
            )
            drawRect(
                OliveGreen,
                Offset(x(q1), boxTop),
                Size(x(q3) - x(q1), boxHeight),
                style = Stroke(3f)
            )
            drawLine(
                OliveGreen,
                Offset(x(median), boxTop),
                Offset(x(median), boxTop + boxHeight),
                3f
            )
            if (type == StatPlotType.ModifiedBox) sorted.filter { it !in displayed }
                .forEach { drawCircle(OliveBrown, 6f, Offset(x(it), centre)) }
        }
    }
}

// Draw subtle grid lines plus numeric X/Y markings using the increments chosen from the plotted data.
private fun DrawScope.drawStatAxes(
    xAxis: StatAxis,
    yAxis: StatAxis?,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float
) {
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(115, 119, 108)
        textSize = 10.sp.toPx()
    }

    fun ticks(axis: StatAxis): List<Double> {
        val count = ((axis.maximum - axis.minimum) / axis.increment).roundToInt().coerceIn(1, 20)
        return (0..count).map { axis.minimum + it * axis.increment }
    }
    ticks(xAxis).forEach { value ->
        val x =
            left + ((value - xAxis.minimum) / (xAxis.maximum - xAxis.minimum) * (right - left)).toFloat()
        drawLine(SoftGray, Offset(x, top), Offset(x, bottom), 1f)
        labelPaint.textAlign = Paint.Align.CENTER
        drawIntoCanvas {
            it.nativeCanvas.drawText(
                formatGraphNumber(value),
                x,
                bottom + 15.dp.toPx(),
                labelPaint
            )
        }
    }
    yAxis?.let { axis ->
        ticks(axis).forEach { value ->
            val y =
                bottom - ((value - axis.minimum) / (axis.maximum - axis.minimum) * (bottom - top)).toFloat()
            drawLine(SoftGray, Offset(left, y), Offset(right, y), 1f)
            labelPaint.textAlign = Paint.Align.RIGHT
            drawIntoCanvas {
                it.nativeCanvas.drawText(
                    formatGraphNumber(value),
                    left - 5.dp.toPx(),
                    y + 3.dp.toPx(),
                    labelPaint
                )
            }
        }
    }
    drawLine(Muted, Offset(left, bottom), Offset(right, bottom), 2f)
    drawLine(Muted, Offset(left, top), Offset(left, bottom), 2f)
}
