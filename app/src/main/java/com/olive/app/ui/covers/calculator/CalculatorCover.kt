package com.olive.app.ui.covers.calculator
import com.olive.app.ui.*

import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
// Keep the calculator looking like a calculator, with no account controls on the cover.
// UI sizing/color: calculator breakpoints, display boxes, key-row weights, type sizes, and all Olive palette roles are controlled here.
@Composable fun CalculatorCover(session: SessionViewModel, interactive: Boolean=true) {
    val context=LocalContext.current
    val calculatorAccountId=session.coverAccountId ?: "guest"
    val historyStore=remember(context,calculatorAccountId) { CalculatorHistoryStore(context,calculatorAccountId) }
    val input=remember(historyStore) { CalculatorInput() }
    var historyLoaded by remember(historyStore) { mutableStateOf(false) }
    LaunchedEffect(historyStore) {
        input.restoreHistory(withContext(Dispatchers.IO) { historyStore.load() })
        historyLoaded=true
    }
    LaunchedEffect(input.history,historyLoaded) {
        if(historyLoaded) withContext(Dispatchers.IO) { historyStore.save(input.history) }
    }
    var solver by remember { mutableStateOf<CalculatorSolver?>(null) }
    var graphing by remember { mutableStateOf(false) }
    var tools by remember { mutableStateOf(false) }
    var angleMode by remember { mutableStateOf(CalculatorAngleMode.RADIANS) }
    var inverseTrig by remember { mutableStateOf(false) }
    var scientificExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(session.coverReset) { input.reset() }
    if(graphing) {
        GraphingCalculator(interactive,angleMode,{angleMode=it}) { graphing=false }
        return
    }
    if(tools) {
        CalculatorTools(interactive,angleMode,{angleMode=it}) { tools=false }
        return
    }
    // The calculator has one page, so Back stays here instead of exposing the phone home screen.
    BackHandler(interactive) { }
    BoxWithConstraints(Modifier.fillMaxSize().background(Cream).testTag("calculator_cover")) {
        val compact=maxHeight<720.dp || maxWidth<350.dp
        val rowSpacing=if(compact) 5.dp else 7.dp
        val baseRows=listOf(listOf("AC","()","%","÷"),listOf("7","8","9","×"),listOf("4","5","6","−"),listOf("1","2","3","+"),listOf(".","0","⌫","="))
        val scientificRows=if(inverseTrig) listOf(
            listOf("√","π","^","!"),listOf("INV","sin⁻¹","cos⁻¹","tan⁻¹"),listOf("x²","e","eˣ","10ˣ"),
        ) else listOf(
            listOf("√","π","^","!"),listOf("INV","sin","cos","tan"),listOf("x²","e","ln","log"),
        )
        fun pressCalculatorKey(key:String) {
            when(key) {
                "INV" -> inverseTrig=!inverseTrig
                "sin","cos","tan" -> input.beginTrig(key,false,angleMode)
                "sin⁻¹","cos⁻¹","tan⁻¹" -> input.beginTrig(key.substringBefore('⁻'),true,angleMode)
                "√","ln","log" -> input.beginFunction(key,angleMode)
                else -> input.press(key)?.let(session::submitCalculatorPin)
            }
        }
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal=if(compact) 10.dp else 16.dp,vertical=if(compact) 6.dp else 10.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                LocalizedText("Better Calculator",color=OliveGreen,fontSize=if(compact) 16.sp else 18.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick={solver=CalculatorSolver.Polynomial},modifier=Modifier.weight(1f).height(if(compact) 38.dp else 44.dp).testTag("polynomial_solver"),contentPadding=PaddingValues(horizontal=2.dp)) {
                    LocalizedText("Polynomial",fontSize=if(compact) 11.sp else 13.sp,maxLines=1)
                }
                FilledTonalButton(onClick={solver=CalculatorSolver.Quadratic},modifier=Modifier.weight(1f).height(if(compact) 38.dp else 44.dp).testTag("quadratic_solver"),contentPadding=PaddingValues(horizontal=2.dp)) {
                    LocalizedText("Quadratic",fontSize=if(compact) 11.sp else 13.sp,maxLines=1)
                }
                FilledTonalButton(onClick={graphing=true},modifier=Modifier.weight(1f).height(if(compact) 38.dp else 44.dp).testTag("graph_editor"),contentPadding=PaddingValues(horizontal=2.dp)) {
                    LocalizedText("Y =",fontSize=if(compact) 12.sp else 14.sp)
                }
                FilledTonalButton(onClick={tools=true},modifier=Modifier.weight(1f).height(if(compact) 38.dp else 44.dp).testTag("math_tools"),contentPadding=PaddingValues(horizontal=2.dp)) {
                    LocalizedText("Tools",fontSize=if(compact) 11.sp else 13.sp,maxLines=1)
                }
            }
            Row(Modifier.fillMaxWidth().height(if(compact) 42.dp else 48.dp),verticalAlignment=Alignment.CenterVertically) {
                LocalizedText("Angle mode",color=Muted,modifier=Modifier.weight(1f))
                FilterChip(selected=angleMode==CalculatorAngleMode.RADIANS,onClick={angleMode=CalculatorAngleMode.RADIANS},
                    label={LocalizedText("RAD")},modifier=Modifier.testTag("calculator_radians"))
                Spacer(Modifier.width(6.dp))
                FilterChip(selected=angleMode==CalculatorAngleMode.DEGREES,onClick={angleMode=CalculatorAngleMode.DEGREES},
                    label={LocalizedText("DEG")},modifier=Modifier.testTag("calculator_degrees"))
            }
            // UI sizing: history scrolls in its own bounded area and the clipped display can never overlap the keys.
            Column(Modifier.fillMaxWidth().weight(if(scientificExpanded) .65f else 1.25f)) {
                Row(Modifier.fillMaxWidth().height(if(compact) 28.dp else 32.dp),verticalAlignment=Alignment.CenterVertically) {
                    LocalizedText("History",modifier=Modifier.weight(1f),color=Muted,style=MaterialTheme.typography.labelSmall)
                    if(input.history.isNotEmpty()) {
                        TextButton(onClick=input::clearHistory,modifier=Modifier.height(if(compact) 28.dp else 32.dp)
                            .testTag("clear_calculator_history"),contentPadding=PaddingValues(horizontal=6.dp,vertical=0.dp)) {
                            LocalizedText("Clear",fontSize=if(compact) 11.sp else 12.sp,color=OliveGreen)
                        }
                    }
                }
                if(input.history.isEmpty()) Spacer(Modifier.weight(1f)) else LazyColumn(
                    modifier=Modifier.fillMaxWidth().weight(1f).testTag("calculator_history_list"),
                    verticalArrangement=Arrangement.spacedBy(1.dp),
                ) {
                    itemsIndexed(input.history,key={ index,entry -> "$index:${entry.expression}:${entry.result}" }) { index,entry ->
                        TextButton(onClick={input.reuse(entry)},modifier=Modifier.fillMaxWidth().heightIn(min=24.dp)
                            .testTag("calculator_history_$index"),contentPadding=PaddingValues(horizontal=2.dp,vertical=0.dp)) {
                            Text(entry.expression,modifier=Modifier.weight(1f),color=Muted,fontFamily=FontFamily.Monospace,
                                fontSize=if(compact) 11.sp else 13.sp,maxLines=1,textAlign=TextAlign.Start)
                            Text("= ${entry.result}",color=OliveGreen,fontFamily=FontFamily.Monospace,
                                fontSize=if(compact) 11.sp else 13.sp,maxLines=1)
                        }
                    }
                }
                val displayScroll=rememberScrollState()
                LaunchedEffect(input.display,displayScroll.maxValue) { displayScroll.scrollTo(displayScroll.maxValue) }
                Column(
                    modifier=Modifier.fillMaxWidth().height(if(compact) 54.dp else 68.dp).clipToBounds(),
                    horizontalAlignment=Alignment.End,
                    verticalArrangement=Arrangement.Bottom,
                ) {
                    LocalizedText(if(input.expression.endsWith("=")) input.expression else "",modifier=Modifier.fillMaxWidth(),color=Muted,
                        fontSize=if(compact) 12.sp else 14.sp,textAlign=TextAlign.End,maxLines=1)
                    Row(Modifier.fillMaxWidth().clipToBounds().horizontalScroll(displayScroll),horizontalArrangement=Arrangement.End) {
                        Text(input.display,modifier=Modifier.testTag("calculator_display"),color=Ink,
                            fontSize=if(compact) { if(input.display.length>12) 25.sp else 38.sp } else { if(input.display.length>12) 31.sp else 48.sp },
                            fontFamily=FontFamily.Monospace,textAlign=TextAlign.End,maxLines=1,softWrap=false)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(if(compact) 30.dp else 36.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={scientificExpanded=!scientificExpanded},modifier=Modifier.size(if(compact) 30.dp else 36.dp).testTag("scientific_toggle")) {
                    Icon(if(scientificExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        if(scientificExpanded) "Hide scientific operators" else "Show scientific operators")
                }
                LocalizedText(if(scientificExpanded) "Hide scientific operators" else "Show scientific operators",style=MaterialTheme.typography.labelSmall,color=Muted)
            }
            val rows=if(scientificExpanded) scientificRows+baseRows else baseRows
            Column(Modifier.fillMaxWidth().weight(if(scientificExpanded) 2.15f else 1.45f),verticalArrangement=Arrangement.spacedBy(rowSpacing)) {
                rows.forEachIndexed { rowIndex,row ->
                    Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(rowSpacing)) {
                        row.forEach { key ->
                            CalculatorKey(key,compact,rowIndex<scientificRows.size && scientificExpanded,!session.busy) { pressCalculatorKey(key) }
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

@Composable
private fun RowScope.CalculatorKey(key:String,compact:Boolean,scientific:Boolean,enabled:Boolean,onClick:()->Unit) {
    val isOperator=key in setOf("+","−","×","÷")
    val background=when { key=="="->OliveGreen;isOperator->SageGreen;key in setOf("AC","()","%","⌫")->LightSage;scientific->PaleOlive;else->WarmWhite }
    val tag=when(key) { "="->"equals";"AC"->"clear";"⌫"->"backspace";else->key }
    Button(onClick=onClick,enabled=enabled,modifier=Modifier.weight(1f).fillMaxHeight().testTag("calc_key_$tag"),
        shape=RoundedCornerShape(28.dp),contentPadding=PaddingValues(horizontal=2.dp,vertical=0.dp),
        colors=ButtonDefaults.buttonColors(containerColor=background,contentColor=if(key=="=") Paper else if(isOperator) OliveGreen else Ink)) {
        LocalizedText(key,fontSize=if(scientific) { if(compact) 13.sp else 16.sp } else { if(compact) 19.sp else 24.sp },fontWeight=FontWeight.Medium,maxLines=1)
    }
}
// The Y= editor plots equations on an olive grid without sending equation data off the device.
// UI sizing/color: the graph fills remaining height; grid/label colors, 32px unit scale, 11sp markings, pan, and zoom live here.
@Composable private fun GraphingCalculator(interactive: Boolean, angleMode: CalculatorAngleMode,
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
            LocalizedText("Graphing Calculator",style=MaterialTheme.typography.titleLarge,color=OliveGreen,fontWeight=FontWeight.SemiBold)
        }
        OutlinedTextField(equation,{equation=it.take(500);error=null},label={LocalizedText("Y = (one function per line)")},
            placeholder={LocalizedText("x^2 - 4\n2x + 3")},minLines=2,maxLines=4,modifier=Modifier.fillMaxWidth().testTag("graph_equation"))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            LocalizedText("Angle mode",color=Muted,modifier=Modifier.weight(1f))
            FilterChip(selected=angleMode==CalculatorAngleMode.RADIANS,onClick={
                onAngleModeChange(CalculatorAngleMode.RADIANS)
                graphs=plottedEquations.map { GraphExpression.compile(it,CalculatorAngleMode.RADIANS) }
            },label={LocalizedText("RAD")},modifier=Modifier.testTag("angle_radians"))
            FilterChip(selected=angleMode==CalculatorAngleMode.DEGREES,onClick={
                onAngleModeChange(CalculatorAngleMode.DEGREES)
                graphs=plottedEquations.map { GraphExpression.compile(it,CalculatorAngleMode.DEGREES) }
            },label={LocalizedText("DEG")},modifier=Modifier.testTag("angle_degrees"))
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            Button(onClick={
                runCatching {
                    equation.lines().map { it.trim() }.filter { it.isNotBlank() }.also { require(it.size in 1..6) { "Enter one to six functions." } }
                        .let { lines -> lines to lines.map { GraphExpression.compile(it,angleMode) } }
                }.onSuccess { (lines,compiled) -> graphs=compiled;plottedEquations=lines;error=null }
                    .onFailure { error=it.message ?: "Unable to graph this equation." }
            },enabled=equation.isNotBlank(),modifier=Modifier.weight(1f).testTag("plot_equation")) { LocalizedText("Graph") }
            TextButton(onClick={zoom=1f;panOffset=Offset.Zero},modifier=Modifier.testTag("graph_reset")) { LocalizedText("Reset") }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={graphTools=true},modifier=Modifier.weight(1f).testTag("graph_table_analysis")) { LocalizedText("Table & analysis") }
            OutlinedButton(onClick={statPlots=true},modifier=Modifier.weight(1f).testTag("stat_plots")) { LocalizedText("Stat plots") }
        }
        error?.let { LocalizedText(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("graph_error")) }
        LocalizedText(plottedEquations.mapIndexed { index,item -> "Y${index+1}=$item" }.joinToString("   "),color=OliveGreen,fontWeight=FontWeight.Medium,maxLines=2)
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
            // The keyboard can briefly collapse this area; wait for usable space instead of drawing into invalid bounds.
            if(size.width<48.dp.toPx() || size.height<48.dp.toPx()) return@Canvas
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
            val plotColors=listOf(OliveGreen,DarkOlive,MossGreen,OliveBrown,Muted,Ink)
            graphs.forEachIndexed { graphIndex,graph ->
                var previous: Offset?=null
                var pixel=0f
                while(pixel<=size.width) {
                    val input=(pixel-centre.x)/unit
                    val output=runCatching { graph.evaluate(input.toDouble()) }.getOrDefault(Double.NaN)
                    val point=Offset(pixel,centre.y-output.toFloat()*unit)
                    val visible=point.x.isFinite() && point.y.isFinite() && point.x in (-size.width*3f)..(size.width*4f) && point.y in (-size.height*3f)..(size.height*4f)
                    val prior=previous
                    if(visible && prior!=null && kotlin.math.abs(point.y-prior.y)<size.height*1.5f)
                        drawLine(plotColors[graphIndex%plotColors.size],prior,point,3.5f,cap=StrokeCap.Round)
                    previous=if(visible) point else null
                    pixel+=2f
                }
            }
        }
        LocalizedText(if(angleMode==CalculatorAngleMode.RADIANS) "Trigonometric functions use radians." else "Trigonometric functions use degrees.",style=MaterialTheme.typography.bodySmall,color=Muted)
    }
    if(graphTools) GraphTableAnalysisDialog(plottedEquations,angleMode) { graphTools=false }
    if(statPlots) StatPlotDialog { statPlots=false }
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
