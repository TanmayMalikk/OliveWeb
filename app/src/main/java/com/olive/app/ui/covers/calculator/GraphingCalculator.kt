// Calculator cover users: draws interactive equation graphs.
package com.olive.app.ui.covers.calculator

import com.olive.app.ui.shared.*

import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.R
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.shared.rememberResourceText

// The Y= editor plots equations on an olive grid without sending equation data off the device.
// This screen owns equations, pan/zoom state and TalkBack graph actions; GraphDrawing renders the grid and curves.
@Composable
internal fun GraphingCalculator(
    interactive: Boolean, angleMode: CalculatorAngleMode,
    onAngleModeChange: (CalculatorAngleMode) -> Unit, onClose: () -> Unit
) {
    val uiText = rememberResourceText()
    var equation by remember { mutableStateOf("x^2\n2x + 3") }
    var plottedEquations by remember { mutableStateOf(listOf("x^2", "2x + 3")) }
    var graphs by remember {
        mutableStateOf(plottedEquations.map {
            GraphExpression.compile(
                it,
                angleMode
            )
        })
    }
    var error by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var graphTools by remember { mutableStateOf(false) }
    var statPlots by remember { mutableStateOf(false) }
    BackHandler(interactive, onBack = onClose)
    Column(
        Modifier.fillMaxSize().background(Cream).safeDrawingPadding().padding(18.dp)
            .testTag("graphing_calculator"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.testTag("close_graph")
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiText(R.string.ui_back)) }
            Text(
                stringResource(R.string.graphing_calculator),
                style = MaterialTheme.typography.titleLarge,
                color = OliveGreen,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
        }
        OutlinedTextField(
            equation,
            {
                equation = it.take(500)
                error = null
            },
            label = { Text(stringResource(R.string.y_one_function_per_line)) },
            placeholder = { Text(uiText(R.string.ui_x_2_4_2x_3)) },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth().testTag("graph_equation").keepVisibleWhileTyping()
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.angle_mode), color = Muted, modifier = Modifier.weight(1f))
            FilterChip(
                selected = angleMode == CalculatorAngleMode.RADIANS,
                onClick = {
                    onAngleModeChange(CalculatorAngleMode.RADIANS)
                    graphs = plottedEquations.map {
                        GraphExpression.compile(
                            it,
                            CalculatorAngleMode.RADIANS
                        )
                    }
                },
                label = { Text(stringResource(R.string.rad)) },
                modifier = Modifier.testTag("angle_radians")
            )
            FilterChip(
                selected = angleMode == CalculatorAngleMode.DEGREES,
                onClick = {
                    onAngleModeChange(CalculatorAngleMode.DEGREES)
                    graphs = plottedEquations.map {
                        GraphExpression.compile(
                            it,
                            CalculatorAngleMode.DEGREES
                        )
                    }
                },
                label = { Text(stringResource(R.string.deg)) },
                modifier = Modifier.testTag("angle_degrees")
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    runCatching {
                        equation.lines().map { it.trim() }.filter { it.isNotBlank() }
                            .also { require(it.size in 1..6) { uiText(R.string.ui_enter_one_to_six_functions) } }
                            .let { lines ->
                                lines to lines.map {
                                    GraphExpression.compile(
                                        it,
                                        angleMode
                                    )
                                }
                            }
                    }.onSuccess { (lines, compiled) ->
                        graphs = compiled
                        plottedEquations = lines
                        error = null
                    }
                        .onFailure {
                            error = uiText.failure(it, R.string.ui_unable_to_graph_this_equation)
                        }
                },
                enabled = equation.isNotBlank(),
                modifier = Modifier.weight(1f).testTag("plot_equation")
            ) { Text(stringResource(R.string.graph)) }
            TextButton(
                onClick = {
                    zoom = 1f
                    panOffset = Offset.Zero
                },
                modifier = Modifier.testTag("graph_reset")
            ) { Text(stringResource(R.string.reset)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { graphTools = true },
                modifier = Modifier.weight(1f).testTag("graph_table_analysis")
            ) { Text(stringResource(R.string.table_analysis)) }
            OutlinedButton(
                onClick = { statPlots = true },
                modifier = Modifier.weight(1f).testTag("stat_plots")
            ) { Text(stringResource(R.string.stat_plots)) }
        }
        error?.let {
            AccessibleMessage(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("graph_error")
                    .semantics { liveRegion = LiveRegionMode.Polite })
        }
        Text(plottedEquations.mapIndexed { index, item -> "Y${index + 1}=$item" }
            .joinToString("   "), color = OliveGreen, fontWeight = FontWeight.Medium, maxLines = 2)
        val graphLabel = uiText(R.string.a11y_graph)
        val zoomLabel = uiText(R.string.a11y_zoom)
        val actionLabels = listOf(
            uiText(R.string.a11y_zoom_in),
            uiText(R.string.a11y_zoom_out),
            uiText(R.string.a11y_pan_left),
            uiText(R.string.a11y_pan_right),
            uiText(R.string.a11y_pan_up),
            uiText(R.string.a11y_pan_down)
        )
        Canvas(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp))
                .background(WarmWhite)
                .semantics {
                    contentDescription = graphLabel + ": " + plottedEquations.joinToString("; ")
                    stateDescription = "$zoomLabel: ${
                        kotlin.math.round(zoom * 100).toInt()
                    }%; X: ${
                        kotlin.math.round(-panOffset.x / (32f * zoom)).toInt()
                    }; Y: ${kotlin.math.round(panOffset.y / (32f * zoom)).toInt()}"
                    if (interactive) customActions = listOf(
                        CustomAccessibilityAction(actionLabels[0]) {
                            zoom = (zoom * 1.25f).coerceIn(.2f, 5f)
                            true
                        },
                        CustomAccessibilityAction(actionLabels[1]) {
                            zoom = (zoom / 1.25f).coerceIn(.2f, 5f)
                            true
                        },
                        CustomAccessibilityAction(actionLabels[2]) {
                            panOffset = panOffset.copy(
                                x = (panOffset.x + 64f).coerceIn(
                                    -20_000f,
                                    20_000f
                                )
                            )
                            true
                        },
                        CustomAccessibilityAction(actionLabels[3]) {
                            panOffset = panOffset.copy(
                                x = (panOffset.x - 64f).coerceIn(
                                    -20_000f,
                                    20_000f
                                )
                            )
                            true
                        },
                        CustomAccessibilityAction(actionLabels[4]) {
                            panOffset = panOffset.copy(
                                y = (panOffset.y + 64f).coerceIn(
                                    -20_000f,
                                    20_000f
                                )
                            )
                            true
                        },
                        CustomAccessibilityAction(actionLabels[5]) {
                            panOffset = panOffset.copy(
                                y = (panOffset.y - 64f).coerceIn(
                                    -20_000f,
                                    20_000f
                                )
                            )
                            true
                        },
                    )
                }
                .pointerInput(interactive) {
                    if (interactive) detectTransformGestures { _, pan, gestureZoom, _ ->
                        panOffset = Offset(
                            (panOffset.x + pan.x).coerceIn(-20_000f, 20_000f),
                            (panOffset.y + pan.y).coerceIn(-20_000f, 20_000f),
                        )
                        zoom = (zoom * gestureZoom).coerceIn(0.2f, 5f)
                    }
                }.testTag("graph_plot")
        ) {
            drawGraphPlot(graphs, zoom, panOffset)
        }
        Text(
            if (angleMode == CalculatorAngleMode.RADIANS) uiText(R.string.trigonometric_functions_use_radians) else uiText(
                R.string.trigonometric_functions_use_degrees
            ), style = MaterialTheme.typography.bodySmall, color = Muted
        )
    }
    if (graphTools) GraphTableAnalysisDialog(plottedEquations, angleMode) { graphTools = false }
    if (statPlots) StatPlotDialog { statPlots = false }
}

// Draws graph plot.
private fun DrawScope.drawGraphPlot(
    graphs: List<GraphExpression>,
    zoom: Float,
    panOffset: Offset
) {
    // The keyboard can briefly collapse this area; wait for usable space instead of drawing into invalid bounds.
    if (size.width < 48.dp.toPx() || size.height < 48.dp.toPx()) return
    val unit = 32f * zoom
    val centre = Offset(size.width / 2f + panOffset.x, size.height / 2f + panOffset.y)
    drawGraphGrid(unit, centre, zoom)
    drawGraphCurves(graphs, unit, centre)
}

// Draws graph grid.
private fun DrawScope.drawGraphGrid(unit: Float, centre: Offset, zoom: Float) {
    val gridColor = SoftGray
    // Grid labels step from 5 to 10 to 25 as the user zooms out, keeping markings readable.
    val majorStep = when {
        zoom >= 0.85f -> 5f
        zoom >= 0.4f -> 10f
        else -> 25f
    }
    val minorPixels = unit * (majorStep / 5f)
    val majorPixels = unit * majorStep
    var x = ((centre.x % minorPixels) + minorPixels) % minorPixels
    while (x <= size.width) {
        drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
        x += minorPixels
    }
    var y = ((centre.y % minorPixels) + minorPixels) % minorPixels
    while (y <= size.height) {
        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
        y += minorPixels
    }

    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(69, 97, 60)
        textSize = 11.sp.toPx()
        textAlign = Paint.Align.CENTER
    }
    val firstX = kotlin.math.floor((0f - centre.x) / majorPixels).toInt()
    val lastX = kotlin.math.ceil((size.width - centre.x) / majorPixels).toInt()
    val xLabelY = (centre.y + 16.dp.toPx()).coerceIn(14.dp.toPx(), size.height - 4.dp.toPx())
    for (index in firstX..lastX) {
        val lineX = centre.x + index * majorPixels
        drawLine(OliveGreen.copy(alpha = .28f), Offset(lineX, 0f), Offset(lineX, size.height), 1.5f)
        drawIntoCanvas {
            it.nativeCanvas.drawText(
                (index * majorStep).toInt().toString(),
                lineX,
                xLabelY,
                labelPaint
            )
        }
    }
    val firstY = kotlin.math.ceil((centre.y - size.height) / majorPixels).toInt()
    val lastY = kotlin.math.floor(centre.y / majorPixels).toInt()
    labelPaint.textAlign = Paint.Align.LEFT
    val yLabelX = (centre.x + 6.dp.toPx()).coerceIn(4.dp.toPx(), size.width - 34.dp.toPx())
    for (index in firstY..lastY) {
        val lineY = centre.y - index * majorPixels
        drawLine(OliveGreen.copy(alpha = .28f), Offset(0f, lineY), Offset(size.width, lineY), 1.5f)
        if (index != 0) drawIntoCanvas {
            it.nativeCanvas.drawText(
                (index * majorStep).toInt().toString(),
                yLabelX,
                lineY - 3.dp.toPx(),
                labelPaint
            )
        }
    }
    drawLine(Muted, Offset(0f, centre.y), Offset(size.width, centre.y), 2f)
    drawLine(Muted, Offset(centre.x, 0f), Offset(centre.x, size.height), 2f)
}

// Draws graph curves.
private fun DrawScope.drawGraphCurves(graphs: List<GraphExpression>, unit: Float, centre: Offset) {
    val plotColors = listOf(OliveGreen, DarkOlive, MossGreen, OliveBrown, Muted, Ink)
    graphs.forEachIndexed { graphIndex, graph ->
        var previous: Offset? = null
        var pixel = 0f
        while (pixel <= size.width) {
            val input = (pixel - centre.x) / unit
            val output = runCatching { graph.evaluate(input.toDouble()) }.getOrDefault(Double.NaN)
            val point = Offset(pixel, centre.y - output.toFloat() * unit)
            val visible =
                point.x.isFinite() && point.y.isFinite() && point.x in (-size.width * 3f)..(size.width * 4f) && point.y in (-size.height * 3f)..(size.height * 4f)
            val prior = previous
            if (visible && prior != null && kotlin.math.abs(point.y - prior.y) < size.height * 1.5f)
                drawLine(
                    plotColors[graphIndex % plotColors.size],
                    prior,
                    point,
                    3.5f,
                    cap = StrokeCap.Round
                )
            previous = if (visible) point else null
            pixel += 2f
        }
    }
}
