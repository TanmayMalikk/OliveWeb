package com.olive.app.ui.covers.calculator
import com.olive.app.ui.*

import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.*
import com.olive.app.ui.LocalizedIcon as Icon
import androidx.compose.ui.res.stringResource
import com.olive.app.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// The Y= editor plots equations on an olive grid without sending equation data off the device.
// This screen owns equations, pan/zoom state and TalkBack graph actions; GraphDrawing renders the grid and curves.
@Composable internal fun GraphingCalculator(interactive: Boolean, angleMode: CalculatorAngleMode,
    onAngleModeChange: (CalculatorAngleMode)->Unit, onClose: ()->Unit) {
    var equation by remember { mutableStateOf("x^2\n2x + 3") }
    var plottedEquations by remember { mutableStateOf(listOf("x^2","2x + 3")) }
    var graphs by remember { mutableStateOf(plottedEquations.map { GraphExpression.compile(it,angleMode) }) }
    var error by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var graphTools by remember { mutableStateOf(false) }
    var statPlots by remember { mutableStateOf(false) }
    BackHandler(interactive,onBack=onClose)
    Column(Modifier.fillMaxSize().background(Cream).safeDrawingPadding().padding(18.dp).testTag("graphing_calculator"),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onClose,modifier=Modifier.testTag("close_graph")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back") }
            Text(stringResource(R.string.graphing_calculator),style=MaterialTheme.typography.titleLarge,color=OliveGreen,fontWeight=FontWeight.SemiBold,modifier=Modifier.semantics { heading() })
        }
        OutlinedTextField(equation,{equation=it.take(500);error=null},label={Text(stringResource(R.string.y_one_function_per_line))},
            placeholder={Text("x^2 - 4\n2x + 3")},minLines=2,maxLines=4,modifier=Modifier.fillMaxWidth().testTag("graph_equation").keepVisibleWhileTyping())
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(stringResource(R.string.angle_mode),color=Muted,modifier=Modifier.weight(1f))
            FilterChip(selected=angleMode==CalculatorAngleMode.RADIANS,onClick={
                onAngleModeChange(CalculatorAngleMode.RADIANS)
                graphs=plottedEquations.map { GraphExpression.compile(it,CalculatorAngleMode.RADIANS) }
            },label={Text(stringResource(R.string.rad))},modifier=Modifier.testTag("angle_radians"))
            FilterChip(selected=angleMode==CalculatorAngleMode.DEGREES,onClick={
                onAngleModeChange(CalculatorAngleMode.DEGREES)
                graphs=plottedEquations.map { GraphExpression.compile(it,CalculatorAngleMode.DEGREES) }
            },label={Text(stringResource(R.string.deg))},modifier=Modifier.testTag("angle_degrees"))
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            Button(onClick={
                runCatching {
                    equation.lines().map { it.trim() }.filter { it.isNotBlank() }.also { require(it.size in 1..6) { "Enter one to six functions." } }
                        .let { lines -> lines to lines.map { GraphExpression.compile(it,angleMode) } }
                }.onSuccess { (lines,compiled) -> graphs=compiled;plottedEquations=lines;error=null }
                    .onFailure { error=it.message ?: "Unable to graph this equation." }
            },enabled=equation.isNotBlank(),modifier=Modifier.weight(1f).testTag("plot_equation")) { Text(stringResource(R.string.graph)) }
            TextButton(onClick={zoom=1f;panOffset=Offset.Zero},modifier=Modifier.testTag("graph_reset")) { Text(stringResource(R.string.reset)) }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={graphTools=true},modifier=Modifier.weight(1f).testTag("graph_table_analysis")) { Text(stringResource(R.string.table_analysis)) }
            OutlinedButton(onClick={statPlots=true},modifier=Modifier.weight(1f).testTag("stat_plots")) { Text(stringResource(R.string.stat_plots)) }
        }
        error?.let { AccessibleMessage(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("graph_error").semantics { liveRegion=LiveRegionMode.Polite }) }
        Text(plottedEquations.mapIndexed { index,item -> "Y${index+1}=$item" }.joinToString("   "),color=OliveGreen,fontWeight=FontWeight.Medium,maxLines=2)
        val graphLabel=accessibilityText("Graph")
        val zoomLabel=accessibilityText("Zoom")
        val actionLabels=listOf("Zoom in","Zoom out","Pan left","Pan right","Pan up","Pan down").map { accessibilityText(it) }
        Canvas(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(WarmWhite)
            .semantics {
                contentDescription=graphLabel+": "+plottedEquations.joinToString("; ")
                stateDescription="$zoomLabel: ${kotlin.math.round(zoom*100).toInt()}%; X: ${kotlin.math.round(-panOffset.x/(32f*zoom)).toInt()}; Y: ${kotlin.math.round(panOffset.y/(32f*zoom)).toInt()}"
                if(interactive) customActions=listOf(
                    CustomAccessibilityAction(actionLabels[0]) { zoom=(zoom*1.25f).coerceIn(.2f,5f);true },
                    CustomAccessibilityAction(actionLabels[1]) { zoom=(zoom/1.25f).coerceIn(.2f,5f);true },
                    CustomAccessibilityAction(actionLabels[2]) { panOffset=panOffset.copy(x=(panOffset.x+64f).coerceIn(-20_000f,20_000f));true },
                    CustomAccessibilityAction(actionLabels[3]) { panOffset=panOffset.copy(x=(panOffset.x-64f).coerceIn(-20_000f,20_000f));true },
                    CustomAccessibilityAction(actionLabels[4]) { panOffset=panOffset.copy(y=(panOffset.y+64f).coerceIn(-20_000f,20_000f));true },
                    CustomAccessibilityAction(actionLabels[5]) { panOffset=panOffset.copy(y=(panOffset.y-64f).coerceIn(-20_000f,20_000f));true },
                )
            }
            .pointerInput(interactive) {
                if(interactive) detectTransformGestures { _,pan,gestureZoom,_ ->
                    panOffset=Offset(
                        (panOffset.x+pan.x).coerceIn(-20_000f,20_000f),
                        (panOffset.y+pan.y).coerceIn(-20_000f,20_000f),
                    )
                    zoom=(zoom*gestureZoom).coerceIn(0.2f,5f)
                }
            }.testTag("graph_plot")) {
            drawGraphPlot(graphs,zoom,panOffset)
        }
        Text(if(angleMode==CalculatorAngleMode.RADIANS) "Trigonometric functions use radians." else "Trigonometric functions use degrees.",style=MaterialTheme.typography.bodySmall,color=Muted)
    }
    if(graphTools) GraphTableAnalysisDialog(plottedEquations,angleMode) { graphTools=false }
    if(statPlots) StatPlotDialog { statPlots=false }
}
