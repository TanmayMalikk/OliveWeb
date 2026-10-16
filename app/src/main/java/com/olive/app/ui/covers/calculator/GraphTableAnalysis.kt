package com.olive.app.ui
import com.olive.app.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

private enum class GraphCalculation(val label:String,val inputLabel:String,val example:String) {
    Value("Value","X value","Example: 2"),
    Zero("Zero","Search range","Example: -10 ; 10"),
    Minimum("Minimum","Search range","Example: -10 ; 10"),
    Maximum("Maximum","Search range","Example: -10 ; 10"),
    Derivative("Derivative","X value","Example: 2"),
    Integral("Integral","Lower and upper bounds","Example: 0 ; 5"),
    Intersection("Intersection","Search range","Example: -10 ; 10"),
}

internal data class GraphTableData(val headers:List<String>,val rows:List<List<String>>)

// Build rows separately from the UI so range validation and every Y= column stay reliable.
internal fun buildGraphTable(equations:List<String>,mode:CalculatorAngleMode,start:Double,end:Double,step:Double):GraphTableData {
    require(equations.isNotEmpty()) { "Graph at least one equation first." }
    require(start.isFinite() && end.isFinite() && step.isFinite()) { "Use valid numbers for the range." }
    require(end>=start) { "End X must be greater than or equal to Start X." }
    require(step>0.0) { "Step must be greater than zero." }
    val rowCount=floor((end-start)/step+1e-10).toInt()+1
    require(rowCount in 1..500) { "Choose a range that creates 500 rows or fewer." }
    val compiled=equations.map { GraphExpression.compile(it,mode) }
    val rows=List(rowCount) { index ->
        val x=start+index*step
        listOf(formatGraphNumber(x))+compiled.map { graph ->
            runCatching { formatGraphNumber(graph.evaluate(x)) }.getOrDefault("undefined")
        }
    }
    return GraphTableData(listOf("X")+equations.indices.map { "Y${it+1}" },rows)
}

// This popup keeps the range controls, table, and analysis tools in separate readable sections.
@Composable
internal fun GraphTableAnalysisDialog(equations:List<String>,mode:CalculatorAngleMode,onClose:()->Unit) {
    var section by remember { mutableStateOf("Table") }
    var startInput by remember { mutableStateOf("-10") }
    var endInput by remember { mutableStateOf("10") }
    var stepInput by remember { mutableStateOf("1") }
    var table by remember { mutableStateOf<GraphTableData?>(null) }
    var equationIndex by remember { mutableIntStateOf(0) }
    var comparisonIndex by remember { mutableIntStateOf(if(equations.size>1) 1 else 0) }
    var calculation by remember { mutableStateOf(GraphCalculation.Value) }
    var analysisInput by remember { mutableStateOf("2") }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun defaultInput(item:GraphCalculation)=when(item) {
        GraphCalculation.Value,GraphCalculation.Derivative -> "2"
        GraphCalculation.Integral -> "0 ; 5"
        else -> "-10 ; 10"
    }

    AlertDialog(
        onDismissRequest=onClose,
        title={Text(stringResource(R.string.graph_table_analysis))},
        text={Column(Modifier.fillMaxWidth().heightIn(max=610.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("Table","Analyze").forEach { item ->
                    FilterChip(selected=section==item,onClick={section=item;error=null},label={Text(item)},modifier=Modifier.weight(1f))
                }
            }
            HorizontalDivider()
            if(section=="Table") {
                GraphTableSection(startInput,{startInput=it;table=null;error=null},endInput,{endInput=it;table=null;error=null},stepInput,{stepInput=it;table=null;error=null},table,error) {
                    runCatching {
                        buildGraphTable(equations,mode,parseCalculatorNumber(startInput),parseCalculatorNumber(endInput),parseCalculatorNumber(stepInput))
                    }.onSuccess { table=it;error=null }.onFailure { table=null;error=it.message ?: "Check the table range." }
                }
            } else {
                GraphAnalysisSection(
                    equations=equations,equationIndex=equationIndex,onEquationChange={ selected ->
                        equationIndex=selected
                        if(comparisonIndex==selected) comparisonIndex=equations.indices.firstOrNull { it!=selected } ?: selected
                        result=null;error=null
                    },
                    comparisonIndex=comparisonIndex,onComparisonChange={comparisonIndex=it;result=null;error=null},
                    calculation=calculation,onCalculationChange={calculation=it;analysisInput=defaultInput(it);result=null;error=null},
                    analysisInput=analysisInput,onInputChange={analysisInput=it.take(120);result=null;error=null},result=result,error=error,
                    onCalculate={
                        runCatching {
                            val equation=equations[equationIndex]
                            when(calculation) {
                                GraphCalculation.Value -> "Y${equationIndex+1}(${analysisInput.trim()}) = ${formatGraphNumber(GraphExpression.compile(equation,mode).evaluate(parseCalculatorNumber(analysisInput)))}"
                                GraphCalculation.Zero -> AdvancedCalculatorEngine.calculate(AdvancedTool.Zero,"$equation ; $analysisInput",mode)
                                GraphCalculation.Minimum -> AdvancedCalculatorEngine.calculate(AdvancedTool.Minimum,"$equation ; $analysisInput",mode)
                                GraphCalculation.Maximum -> AdvancedCalculatorEngine.calculate(AdvancedTool.Maximum,"$equation ; $analysisInput",mode)
                                GraphCalculation.Derivative -> AdvancedCalculatorEngine.calculate(AdvancedTool.Derivative,"$equation ; $analysisInput",mode)
                                GraphCalculation.Integral -> AdvancedCalculatorEngine.calculate(AdvancedTool.Integral,"$equation ; $analysisInput",mode)
                                GraphCalculation.Intersection -> {
                                    require(equations.size>1) { "Graph at least two equations first." }
                                    require(comparisonIndex!=equationIndex) { "Choose two different equations." }
                                    AdvancedCalculatorEngine.calculate(AdvancedTool.Intersection,"$equation ; ${equations[comparisonIndex]} ; $analysisInput",mode)
                                }
                            }
                        }.onSuccess { result=it;error=null }.onFailure { result=null;error=it.message ?: "Check the analysis values." }
                    },
                )
            }
        }},
        confirmButton={TextButton(onClick=onClose) { Text(stringResource(R.string.done)) }},
    )
}

@Composable
private fun GraphTableSection(
    start:String,onStartChange:(String)->Unit,end:String,onEndChange:(String)->Unit,step:String,onStepChange:(String)->Unit,
    table:GraphTableData?,error:String?,onGenerate:()->Unit,
) {
    Text("X range",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
    Text("Choose the first X value, last X value, and distance between rows.",style=MaterialTheme.typography.bodySmall,color=Muted)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        RangeField(start,onStartChange,"Start X",Modifier.weight(1f).testTag("table_start_x"))
        RangeField(end,onEndChange,"End X",Modifier.weight(1f).testTag("table_end_x"))
        RangeField(step,onStepChange,"Step",Modifier.weight(1f).testTag("table_step_x"))
    }
    Button(onClick=onGenerate,modifier=Modifier.fillMaxWidth().testTag("make_graph_table")) { Text(stringResource(R.string.generate_table)) }
    error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
    table?.let { data ->
        Text("Values",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
        GraphValueTable(data)
    }
}

@Composable
private fun RangeField(value:String,onChange:(String)->Unit,label:String,modifier:Modifier=Modifier) {
    OutlinedTextField(value,{onChange(it.take(24))},label={Text(label)},singleLine=true,modifier=modifier)
}

@Composable
private fun GraphValueTable(data:GraphTableData) {
    val horizontal=rememberScrollState()
    val vertical=rememberScrollState()
    LaunchedEffect(data) { horizontal.scrollTo(0);vertical.scrollTo(0) }
    Text("Starts at X = ${data.rows.firstOrNull()?.firstOrNull().orEmpty()}",style=MaterialTheme.typography.labelMedium,color=Muted)
    // UI sizing/color: the table stays at 100% size inside a fixed 280dp viewport with a scrollbar on each axis.
    Surface(shape=RoundedCornerShape(12.dp),color=Paper,tonalElevation=2.dp,
        modifier=Modifier.fillMaxWidth().height(280.dp).border(1.dp,SoftGray,RoundedCornerShape(12.dp))) {
        Box(Modifier.fillMaxSize().oliveScrollbar(vertical).oliveHorizontalScrollbar(horizontal)) {
            Column(Modifier.horizontalScroll(horizontal).verticalScroll(vertical).padding(end=7.dp,bottom=7.dp).testTag("graph_table_rows")) {
                Row(Modifier.background(SageGreen)) {
                    data.headers.forEachIndexed { index,header -> GraphTableCell(header,index==0,true) }
                }
                data.rows.forEachIndexed { rowIndex,row ->
                    Row(Modifier.background(if(rowIndex%2==0) Paper else Cream)) {
                        row.forEachIndexed { index,value -> GraphTableCell(value,index==0,false) }
                    }
                }
            }
        }
    }
    Text("Use the right and bottom scrollbars to move through rows and columns.",style=MaterialTheme.typography.labelSmall,color=Muted)
}

@Composable
private fun GraphTableCell(value:String,isX:Boolean,isHeader:Boolean) {
    // UI sizing/color: change these widths together if translated headers need more room.
    Box(Modifier.width(if(isX) 92.dp else 132.dp).height(42.dp).border(.5.dp,SoftGray).padding(horizontal=8.dp),contentAlignment=Alignment.Center) {
        Text(value,fontFamily=FontFamily.Monospace,fontWeight=if(isHeader) FontWeight.Bold else FontWeight.Normal,
            color=if(isHeader) Ink else if(isX) OliveGreen else Ink,textAlign=TextAlign.Center,maxLines=1)
    }
}

@Composable
private fun GraphAnalysisSection(
    equations:List<String>,equationIndex:Int,onEquationChange:(Int)->Unit,comparisonIndex:Int,onComparisonChange:(Int)->Unit,
    calculation:GraphCalculation,onCalculationChange:(GraphCalculation)->Unit,analysisInput:String,onInputChange:(String)->Unit,
    result:String?,error:String?,onCalculate:()->Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("1. Choose a function",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            equations.indices.forEach { index ->
                FilterChip(selected=equationIndex==index,onClick={onEquationChange(index)},label={Text("Y${index+1}")})
            }
        }
        Surface(color=WarmWhite,shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth()) {
            Text("Y${equationIndex+1} = ${equations[equationIndex]}",fontFamily=FontFamily.Monospace,modifier=Modifier.padding(12.dp))
        }

        Text("2. Choose an analysis",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            GraphCalculation.entries.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    row.forEach { item ->
                        FilterChip(selected=calculation==item,onClick={onCalculationChange(item)},
                            label={Text(item.label,fontSize=13.sp)},modifier=Modifier.weight(1f).height(34.dp))
                    }
                    if(row.size==1) Spacer(Modifier.weight(1f))
                }
            }
        }

        if(calculation==GraphCalculation.Intersection && equations.size>1) {
            Text("Compare with",style=MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                equations.indices.filter { it!=equationIndex }.forEach { index ->
                    FilterChip(selected=comparisonIndex==index,onClick={onComparisonChange(index)},label={Text("Y${index+1}")})
                }
            }
        }

        OutlinedTextField(analysisInput,onInputChange,label={Text(calculation.inputLabel)},supportingText={Text(calculation.example)},singleLine=true,modifier=Modifier.fillMaxWidth())
        Button(onClick=onCalculate,modifier=Modifier.fillMaxWidth().testTag("run_graph_analysis")) { Text("Calculate ${calculation.label.lowercase()}") }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
        result?.let {
            Surface(color=PaleOlive,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().testTag("graph_analysis_result")) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("Result",style=MaterialTheme.typography.labelLarge,color=OliveGreen,fontWeight=FontWeight.Bold)
                    Text(it,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

internal fun formatGraphNumber(value:Double):String = if(!value.isFinite()) "undefined" else formatCalculatorNumber(value,7)
