package com.olive.app.ui

import android.graphics.Paint
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// Keep the calculator looking like a calculator, with no account controls on the cover.
// UI sizing/color: calculator breakpoints, display boxes, key-row weights, type sizes, and all Olive palette roles are controlled here.
@Composable fun CalculatorCover(session: SessionViewModel, interactive: Boolean=true) {
    val input=remember { CalculatorInput() }
    var solver by remember { mutableStateOf<CalculatorSolver?>(null) }
    var graphing by remember { mutableStateOf(false) }
    var angleMode by remember { mutableStateOf(CalculatorAngleMode.RADIANS) }
    LaunchedEffect(session.coverReset) { input.reset() }
    if(graphing) {
        GraphingCalculator(interactive,angleMode,{angleMode=it}) { graphing=false }
        return
    }
    val background=Cream
    val number=WarmWhite
    val utility=LightSage
    val operatorColor=SageGreen
    val accent=OliveGreen
    // The calculator has one page, so Back stays here instead of exposing the phone home screen.
    BackHandler(interactive) { }
    BoxWithConstraints(Modifier.fillMaxSize().background(background).testTag("calculator_cover")) {
    // Sizing note: these are the calculator's compact breakpoints. Increase 720dp/350dp if a translated layout needs compact mode earlier.
    // The keypad rows share whatever height remains so the calculator always fits without scrolling.
    val compact=maxHeight<720.dp || maxWidth<350.dp
    val spacing=if(compact) 6.dp else 12.dp
    val functionHeight=if(compact) 44.dp else 52.dp
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(start=if(compact) 12.dp else 20.dp,end=if(compact) 12.dp else 20.dp,top=if(compact) 8.dp else 14.dp,bottom=2.dp),
        verticalArrangement=Arrangement.spacedBy(spacing)) {
        // Each pair is compact/regular text. Retest Polynomial and Quadratic first because those labels grow most in translation.
        LocalizedText("Better Calculator",color=OliveGreen,fontSize=if(compact) 17.sp else 19.sp,fontWeight=FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick={solver=CalculatorSolver.Polynomial},modifier=Modifier.weight(1f).testTag("polynomial_solver"),contentPadding=PaddingValues(horizontal=4.dp)) {
                LocalizedText("Polynomial",fontSize=if(compact) 12.sp else 14.sp,maxLines=1)
            }
            FilledTonalButton(onClick={solver=CalculatorSolver.Quadratic},modifier=Modifier.weight(1f).testTag("quadratic_solver"),contentPadding=PaddingValues(horizontal=4.dp)) {
                LocalizedText("Quadratic",fontSize=if(compact) 12.sp else 14.sp,maxLines=1)
            }
            FilledTonalButton(onClick={graphing=true},modifier=Modifier.weight(1f).testTag("graph_editor"),contentPadding=PaddingValues(horizontal=4.dp)) {
                LocalizedText("Y =")
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            LocalizedText("Angle mode",color=Muted,modifier=Modifier.weight(1f))
            FilterChip(selected=angleMode==CalculatorAngleMode.RADIANS,onClick={angleMode=CalculatorAngleMode.RADIANS},
                label={LocalizedText("RAD")},modifier=Modifier.testTag("calculator_radians"))
            FilterChip(selected=angleMode==CalculatorAngleMode.DEGREES,onClick={angleMode=CalculatorAngleMode.DEGREES},
                label={LocalizedText("DEG")},modifier=Modifier.testTag("calculator_degrees"))
        }
        Spacer(Modifier.height(if(compact) 2.dp else 10.dp))
        // Fixed display boxes keep every button in place when an empty expression becomes a result or its font shrinks.
        Box(Modifier.fillMaxWidth().height(if(compact) 20.dp else 24.dp),contentAlignment=Alignment.CenterEnd) {
            LocalizedText(input.expression,modifier=Modifier.fillMaxWidth(),color=Muted,
                fontSize=if(compact) 15.sp else 18.sp,textAlign=TextAlign.End,maxLines=1)
        }
        Box(Modifier.fillMaxWidth().height(if(compact) 48.dp else 60.dp),contentAlignment=Alignment.CenterEnd) {
            // The display uses a second size step for long answers while its outer height remains unchanged.
            LocalizedText(input.display,modifier=Modifier.fillMaxWidth().testTag("calculator_display"),color=Ink,
                fontSize=if(compact) { if(input.display.length>12) 25.sp else 38.sp } else { if(input.display.length>12) 31.sp else 48.sp },fontFamily=FontFamily.Monospace,
                textAlign=TextAlign.End,maxLines=1)
        }
        if(!compact) Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            listOf("sin","cos","tan").forEach { function ->
                FilledTonalButton(onClick={input.applyTrig(function,angleMode)},enabled=!session.busy,
                    modifier=Modifier.weight(1f).height(functionHeight).testTag("calc_$function"),shape=RoundedCornerShape(18.dp)) {
                    LocalizedText(function,fontSize=if(compact) 16.sp else 18.sp,fontWeight=FontWeight.Medium)
                }
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f),verticalArrangement=Arrangement.spacedBy(spacing)) {
            listOf(listOf("AC","±","%","÷"),listOf("7","8","9","×"),listOf("4","5","6","−"),
                listOf("1","2","3","+"),listOf("⌫","0",".","=")).forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    row.forEach { key ->
                        val isOperator=key in setOf("+","−","×","÷")
                        val buttonColor=when {
                            key=="=" -> accent
                            isOperator -> operatorColor
                            key in setOf("AC","±","%","⌫") -> utility
                            else -> number
                        }
                        Button(onClick={input.press(key)?.let(session::submitCalculatorPin)},enabled=!session.busy,
                            modifier=Modifier.weight(1f).fillMaxHeight().testTag("calc_key_${when(key) { "=" -> "equals"; "AC" -> "clear"; "⌫" -> "backspace"; else -> key }}"),
                            shape=RoundedCornerShape(20.dp),contentPadding=PaddingValues(0.dp),
                            colors=ButtonDefaults.buttonColors(containerColor=buttonColor,contentColor=if(key=="=") MaterialTheme.colorScheme.onPrimary else if(isOperator) OliveGreen else Ink)) {
                            LocalizedText(key,fontSize=if(compact) { if(key=="AC") 15.sp else 21.sp } else if(key=="AC") 18.sp else 25.sp,fontWeight=FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
    }
    solver?.let { selected ->
        EquationSolverDialog(selected) { solver=null }
    }
}
// The Y= editor plots equations on an olive grid without sending equation data off the device.
// UI sizing/color: the graph fills remaining height; grid/label colors, 32px unit scale, 11sp markings, pan, and zoom live here.
@Composable private fun GraphingCalculator(interactive: Boolean, angleMode: CalculatorAngleMode,
    onAngleModeChange: (CalculatorAngleMode)->Unit, onClose: ()->Unit) {
    var equation by remember { mutableStateOf("x^2") }
    var plottedEquation by remember { mutableStateOf("x^2") }
    var graph by remember { mutableStateOf(GraphExpression.compile("x^2",angleMode)) }
    var error by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    BackHandler(interactive,onBack=onClose)
    Column(Modifier.fillMaxSize().background(Cream).safeDrawingPadding().padding(18.dp).testTag("graphing_calculator"),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onClose,modifier=Modifier.testTag("close_graph")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back") }
            LocalizedText("Graphing Calculator",style=MaterialTheme.typography.titleLarge,color=OliveGreen,fontWeight=FontWeight.SemiBold)
        }
        OutlinedTextField(equation,{equation=it.take(160);error=null},label={LocalizedText("Y =")},
            placeholder={LocalizedText("x^2 - 4")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("graph_equation"))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            LocalizedText("Angle mode",color=Muted,modifier=Modifier.weight(1f))
            FilterChip(selected=angleMode==CalculatorAngleMode.RADIANS,onClick={
                onAngleModeChange(CalculatorAngleMode.RADIANS)
                graph=GraphExpression.compile(plottedEquation,CalculatorAngleMode.RADIANS)
            },label={LocalizedText("RAD")},modifier=Modifier.testTag("angle_radians"))
            FilterChip(selected=angleMode==CalculatorAngleMode.DEGREES,onClick={
                onAngleModeChange(CalculatorAngleMode.DEGREES)
                graph=GraphExpression.compile(plottedEquation,CalculatorAngleMode.DEGREES)
            },label={LocalizedText("DEG")},modifier=Modifier.testTag("angle_degrees"))
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            Button(onClick={
                runCatching { GraphExpression.compile(equation,angleMode) }
                    .onSuccess { graph=it;plottedEquation=equation.trim();error=null }
                    .onFailure { error=it.message ?: "Unable to graph this equation." }
            },enabled=equation.isNotBlank(),modifier=Modifier.weight(1f).testTag("plot_equation")) { LocalizedText("Graph") }
            OutlinedButton(onClick={zoom=(zoom/1.35f).coerceAtLeast(0.2f)},modifier=Modifier.testTag("graph_zoom_out")) { LocalizedText("−") }
            OutlinedButton(onClick={zoom=(zoom*1.35f).coerceAtMost(5f)},modifier=Modifier.testTag("graph_zoom_in")) { LocalizedText("+") }
            TextButton(onClick={zoom=1f;panOffset=Offset.Zero},modifier=Modifier.testTag("graph_reset")) { LocalizedText("Reset") }
        }
        error?.let { LocalizedText(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("graph_error")) }
        LocalizedText("y = $plottedEquation",color=OliveGreen,fontWeight=FontWeight.Medium)
        Canvas(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(WarmWhite)
            .pointerInput(interactive) {
                if(interactive) detectTransformGestures { _,pan,gestureZoom,_ ->
                    panOffset=Offset(
                        (panOffset.x+pan.x).coerceIn(-20_000f,20_000f),
                        (panOffset.y+pan.y).coerceIn(-20_000f,20_000f),
                    )
                    zoom=(zoom*gestureZoom).coerceIn(0.2f,5f)
                }
            }.testTag("graph_plot")) {
            val unit=32f*zoom
            val centre=Offset(size.width/2f+panOffset.x,size.height/2f+panOffset.y)
            val gridColor=SoftGray
            // Grid labels step from 5 to 10 to 25 as the user zooms out, keeping markings readable.
            val majorStep=when {
                zoom>=0.85f -> 5f
                zoom>=0.4f -> 10f
                else -> 25f
            }
            val minorPixels=unit*(majorStep/5f)
            val majorPixels=unit*majorStep
            var x=((centre.x%minorPixels)+minorPixels)%minorPixels
            while(x<=size.width) { drawLine(gridColor,Offset(x,0f),Offset(x,size.height),1f);x+=minorPixels }
            var y=((centre.y%minorPixels)+minorPixels)%minorPixels
            while(y<=size.height) { drawLine(gridColor,Offset(0f,y),Offset(size.width,y),1f);y+=minorPixels }

            val labelPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=android.graphics.Color.rgb(69,97,60)
                textSize=11.sp.toPx()
                textAlign=Paint.Align.CENTER
            }
            val firstX=kotlin.math.floor((0f-centre.x)/majorPixels).toInt()
            val lastX=kotlin.math.ceil((size.width-centre.x)/majorPixels).toInt()
            val xLabelY=(centre.y+16.dp.toPx()).coerceIn(14.dp.toPx(),size.height-4.dp.toPx())
            for(index in firstX..lastX) {
                val lineX=centre.x+index*majorPixels
                drawLine(OliveGreen.copy(alpha=.28f),Offset(lineX,0f),Offset(lineX,size.height),1.5f)
                drawIntoCanvas { it.nativeCanvas.drawText((index*majorStep).toInt().toString(),lineX,xLabelY,labelPaint) }
            }
            val firstY=kotlin.math.ceil((centre.y-size.height)/majorPixels).toInt()
            val lastY=kotlin.math.floor(centre.y/majorPixels).toInt()
            labelPaint.textAlign=Paint.Align.LEFT
            val yLabelX=(centre.x+6.dp.toPx()).coerceIn(4.dp.toPx(),size.width-34.dp.toPx())
            for(index in firstY..lastY) {
                val lineY=centre.y-index*majorPixels
                drawLine(OliveGreen.copy(alpha=.28f),Offset(0f,lineY),Offset(size.width,lineY),1.5f)
                if(index!=0) drawIntoCanvas {
                    it.nativeCanvas.drawText((index*majorStep).toInt().toString(),yLabelX,lineY-3.dp.toPx(),labelPaint)
                }
            }
            drawLine(Muted,Offset(0f,centre.y),Offset(size.width,centre.y),2f)
            drawLine(Muted,Offset(centre.x,0f),Offset(centre.x,size.height),2f)
            var previous: Offset?=null
            var pixel=0f
            while(pixel<=size.width) {
                val inputX=(pixel-centre.x)/unit
                val outputY=runCatching { graph.evaluate(inputX.toDouble()) }.getOrDefault(Double.NaN)
                val point=Offset(pixel,centre.y-(outputY.toFloat()*unit))
                val visible=outputY.isFinite() && point.y in (-size.height*3f)..(size.height*4f)
                val prior=previous
                if(visible && prior!=null && kotlin.math.abs(point.y-prior.y)<size.height*1.5f)
                    drawLine(OliveGreen,prior,point,3.5f,cap=StrokeCap.Round)
                previous=if(visible) point else null
                pixel+=2f
            }
        }
        LocalizedText(if(angleMode==CalculatorAngleMode.RADIANS) "Trigonometric functions use radians." else "Trigonometric functions use degrees.",style=MaterialTheme.typography.bodySmall,color=Muted)
    }
}

private enum class CalculatorSolver { Polynomial, Quadratic }

// Each solver stays in a small calculator-style popup and handles the equation entirely on this device.
// UI sizing/text: solver dialogs use Material dialog sizing, two-line equation input, bodySmall examples, and OliveGreen results.
@Composable private fun EquationSolverDialog(type: CalculatorSolver, onClose: ()->Unit) {
    var equation by remember(type) { mutableStateOf("") }
    var result by remember(type) { mutableStateOf<String?>(null) }
    var error by remember(type) { mutableStateOf<String?>(null) }
    val quadratic=type==CalculatorSolver.Quadratic
    AlertDialog(
        onDismissRequest=onClose,
        title={LocalizedText(if(quadratic) "Quadratic equation" else "Polynomial calculation")},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(equation,{equation=it.take(160);result=null;error=null},
                label={LocalizedText("Equation")},
                placeholder={LocalizedText(if(quadratic) "x^2 - 5x + 6 = 0" else "(x^2 - 1) / (x - 1)")},
                singleLine=false,minLines=2,modifier=Modifier.fillMaxWidth().testTag("equation_input"))
            LocalizedText(if(quadratic) "Example: x^2 - 5x + 6 = 0" else "Example: (x^2 - 1) / (x - 1)",style=MaterialTheme.typography.bodySmall,color=Muted)
            result?.let { LocalizedText(it,color=OliveGreen,fontWeight=FontWeight.SemiBold,modifier=Modifier.testTag("equation_result")) }
            error?.let { LocalizedText(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("equation_error")) }
        }},
        confirmButton={Button(onClick={
            runCatching { if(quadratic) EquationSolver.quadratic(equation) else EquationSolver.calculatePolynomial(equation) }
                .onSuccess { result=it;error=null }
                .onFailure { result=null;error=it.message ?: "Unable to solve this equation." }
        },enabled=equation.isNotBlank(),modifier=Modifier.testTag("solve_equation")) { LocalizedText("Solve") }},
        dismissButton={TextButton(onClick=onClose) { LocalizedText("Close") }},
        containerColor=Cream,
    )
}
