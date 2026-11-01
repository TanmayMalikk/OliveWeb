// Calculator cover users: builds a value table from a graph.
package com.olive.app.ui.covers.calculator

import com.olive.app.ui.shared.*

import com.olive.app.ui.shared.ResourceFailure
import com.olive.app.ui.shared.rememberResourceText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import com.olive.app.R
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
import kotlin.math.floor

internal enum class GraphCalculation(val label: Int, val inputLabel: Int, val example: Int) {
    Value(R.string.ui_value, R.string.ui_x_value, R.string.ui_example_2),
    Zero(R.string.ui_zero, R.string.ui_search_range, R.string.ui_example_10_10),
    Minimum(R.string.ui_minimum, R.string.ui_search_range, R.string.ui_example_10_10),
    Maximum(R.string.ui_maximum, R.string.ui_search_range, R.string.ui_example_10_10),
    Derivative(R.string.ui_derivative, R.string.ui_x_value, R.string.ui_example_2),
    Integral(R.string.ui_integral, R.string.ui_lower_and_upper_bounds, R.string.ui_example_0_5),
    Intersection(R.string.ui_intersection, R.string.ui_search_range, R.string.ui_example_10_10),
}

internal data class GraphTableData(val headers: List<String>, val rows: List<List<String>>)

// Build rows separately from the UI so range validation and every Y= column stay reliable.
internal fun buildGraphTable(
    equations: List<String>,
    mode: CalculatorAngleMode,
    start: Double,
    end: Double,
    step: Double
): GraphTableData {
    requireText(equations.isNotEmpty()) { ResourceFailure(R.string.ui_graph_at_least_one_equation_first) }
    requireText(start.isFinite() && end.isFinite() && step.isFinite()) { ResourceFailure(R.string.ui_use_valid_numbers_for_the_range) }
    requireText(end >= start) { ResourceFailure(R.string.ui_end_x_must_be_greater_than_or_equal_to_start_x) }
    requireText(step > 0.0) { ResourceFailure(R.string.ui_step_must_be_greater_than_zero) }
    val rowCount = floor((end - start) / step + 1e-10).toInt() + 1
    requireText(rowCount in 1..500) { ResourceFailure(R.string.ui_choose_a_range_that_creates_500_rows_or_fewer) }
    val compiled = equations.map { GraphExpression.compile(it, mode) }
    val rows = List(rowCount) { index ->
        val x = start + index * step
        listOf(formatGraphNumber(x)) + compiled.map { graph ->
            runCatching { formatGraphNumber(graph.evaluate(x)) }.getOrDefault("undefined")
        }
    }
    return GraphTableData(listOf("X") + equations.indices.map { "Y${it + 1}" }, rows)
}

// This popup keeps the range controls, table, and analysis tools in separate readable sections.
@Composable
internal fun GraphTableAnalysisDialog(
    equations: List<String>,
    mode: CalculatorAngleMode,
    onClose: () -> Unit
) {
    val uiText = rememberResourceText()
    var section by remember { mutableStateOf("table") }
    var startInput by remember { mutableStateOf("-10") }
    var endInput by remember { mutableStateOf("10") }
    var stepInput by remember { mutableStateOf("1") }
    var table by remember { mutableStateOf<GraphTableData?>(null) }
    var equationIndex by remember { mutableIntStateOf(0) }
    var comparisonIndex by remember { mutableIntStateOf(if (equations.size > 1) 1 else 0) }
    var calculation by remember { mutableStateOf(GraphCalculation.Value) }
    var analysisInput by remember { mutableStateOf("2") }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun defaultInput(item: GraphCalculation) = when (item) {
        GraphCalculation.Value, GraphCalculation.Derivative -> "2"
        GraphCalculation.Integral -> "0 ; 5"
        else -> "-10 ; 10"
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.graph_table_analysis)) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 610.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "table" to R.string.ui_table,
                        "analyze" to R.string.ui_analyze
                    ).forEach { (item, label) ->
                        FilterChip(
                            selected = section == item,
                            onClick = {
                                section = item
                                error = null
                            },
                            label = { Text(uiText(label)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                HorizontalDivider()
                if (section == "table") {
                    GraphTableSection(
                        startInput,
                        {
                            startInput = it
                            table = null
                            error = null
                        },
                        endInput,
                        {
                            endInput = it
                            table = null
                            error = null
                        },
                        stepInput,
                        {
                            stepInput = it
                            table = null
                            error = null
                        },
                        table,
                        error
                    ) {
                        runCatching {
                            buildGraphTable(
                                equations,
                                mode,
                                parseCalculatorNumber(startInput),
                                parseCalculatorNumber(endInput),
                                parseCalculatorNumber(stepInput)
                            )
                        }.onSuccess {
                            table = it
                            error = null
                        }.onFailure {
                            table = null
                            error =
                                uiText.failure(it, R.string.ui_check_the_table_range)
                        }
                    }
                } else {
                    GraphAnalysisSection(
                        equations = equations,
                        equationIndex = equationIndex,
                        onEquationChange = { selected ->
                            equationIndex = selected
                            if (comparisonIndex == selected) comparisonIndex =
                                equations.indices.firstOrNull { it != selected } ?: selected
                            result = null
                            error = null
                        },
                        comparisonIndex = comparisonIndex,
                        onComparisonChange = {
                            comparisonIndex = it
                            result = null
                            error = null
                        },
                        calculation = calculation,
                        onCalculationChange = {
                            calculation = it
                            analysisInput = defaultInput(it)
                            result =
                                null
                            error = null
                        },
                        analysisInput = analysisInput,
                        onInputChange = {
                            analysisInput = it.take(120)
                            result = null
                            error = null
                        },
                        result = result,
                        error = error,
                        onCalculate = {
                            runCatching {
                                val equation = equations[equationIndex]
                                when (calculation) {
                                    GraphCalculation.Value -> uiText(
                                        R.string.ui_yvalue_value_value,
                                        equationIndex + 1,
                                        analysisInput.trim(),
                                        formatGraphNumber(
                                            GraphExpression.compile(equation, mode)
                                                .evaluate(parseCalculatorNumber(analysisInput))
                                        )
                                    )

                                    GraphCalculation.Zero -> AdvancedCalculatorEngine.calculate(
                                        AdvancedTool.Zero,
                                        "$equation ; $analysisInput",
                                        mode,
                                        uiText
                                    )

                                    GraphCalculation.Minimum -> AdvancedCalculatorEngine.calculate(
                                        AdvancedTool.Minimum,
                                        "$equation ; $analysisInput",
                                        mode,
                                        uiText
                                    )

                                    GraphCalculation.Maximum -> AdvancedCalculatorEngine.calculate(
                                        AdvancedTool.Maximum,
                                        "$equation ; $analysisInput",
                                        mode,
                                        uiText
                                    )

                                    GraphCalculation.Derivative -> AdvancedCalculatorEngine.calculate(
                                        AdvancedTool.Derivative,
                                        "$equation ; $analysisInput",
                                        mode,
                                        uiText
                                    )

                                    GraphCalculation.Integral -> AdvancedCalculatorEngine.calculate(
                                        AdvancedTool.Integral,
                                        "$equation ; $analysisInput",
                                        mode,
                                        uiText
                                    )

                                    GraphCalculation.Intersection -> {
                                        requireText(equations.size > 1) { ResourceFailure(R.string.ui_graph_at_least_two_equations_first) }
                                        require(comparisonIndex != equationIndex) { uiText(R.string.ui_choose_two_different_equations) }
                                        AdvancedCalculatorEngine.calculate(
                                            AdvancedTool.Intersection,
                                            "$equation ; ${equations[comparisonIndex]} ; $analysisInput",
                                            mode,
                                            uiText
                                        )
                                    }
                                }
                            }.onSuccess {
                                result = it
                                error = null
                            }.onFailure {
                                result = null
                                error =
                                    uiText.failure(it, R.string.ui_check_the_analysis_values)
                            }
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.done)) } },
    )
}

@Composable
private fun GraphTableSection(
    start: String,
    onStartChange: (String) -> Unit,
    end: String,
    onEndChange: (String) -> Unit,
    step: String,
    onStepChange: (String) -> Unit,
    table: GraphTableData?,
    error: String?,
    onGenerate: () -> Unit,
) {
    val uiText = rememberResourceText()
    Text(
        uiText(R.string.ui_x_range),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        uiText(R.string.ui_choose_the_first_x_value_last_x_value_and_distance_between_rows),
        style = MaterialTheme.typography.bodySmall,
        color = Muted
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RangeField(
            start,
            onStartChange,
            uiText(R.string.ui_start_x),
            Modifier.weight(1f).testTag("table_start_x")
        )
        RangeField(
            end,
            onEndChange,
            uiText(R.string.ui_end_x),
            Modifier.weight(1f).testTag("table_end_x")
        )
        RangeField(
            step,
            onStepChange,
            uiText(R.string.a11y_step),
            Modifier.weight(1f).testTag("table_step_x")
        )
    }
    Button(
        onClick = onGenerate,
        modifier = Modifier.fillMaxWidth().testTag("make_graph_table")
    ) { Text(stringResource(R.string.generate_table)) }
    error?.let {
        AccessibleMessage(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }
    table?.let { data ->
        Text(
            uiText(R.string.ui_values),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        GraphValueTable(data)
    }
}

@Composable
private fun RangeField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value,
        { onChange(it.take(24)) },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.keepVisibleWhileTyping()
    )
}

@Composable
private fun GraphValueTable(data: GraphTableData) {
    val uiText = rememberResourceText()
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    LaunchedEffect(data) {
        horizontal.scrollTo(0)
        vertical.scrollTo(0)
    }
    Text(
        uiText(R.string.ui_starts_at_x_value, data.rows.firstOrNull()?.firstOrNull().orEmpty()),
        style = MaterialTheme.typography.labelMedium,
        color = Muted
    )
    // UI sizing/color: the table stays at 100% size inside a fixed 280dp viewport with a scrollbar on each axis.
    Surface(
        shape = RoundedCornerShape(12.dp), color = Paper, tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().height(280.dp)
            .border(1.dp, SoftGray, RoundedCornerShape(12.dp))
    ) {
        Box(Modifier.fillMaxSize().oliveScrollbar(vertical).oliveHorizontalScrollbar(horizontal)) {
            Column(
                Modifier.horizontalScroll(horizontal).verticalScroll(vertical)
                    .padding(end = 7.dp, bottom = 7.dp).testTag("graph_table_rows")
            ) {
                Row(
                    Modifier.background(SageGreen).testTag("graph_table_header")
                        .semantics(mergeDescendants = true) {}) {
                    data.headers.forEachIndexed { index, header ->
                        GraphTableCell(
                            header,
                            index == 0,
                            true
                        )
                    }
                }
                data.rows.forEachIndexed { rowIndex, row ->
                    Row(
                        Modifier.background(if (rowIndex % 2 == 0) Paper else Cream)
                            .testTag("graph_table_row_$rowIndex")
                            .semantics(mergeDescendants = true) {}) {
                        row.forEachIndexed { index, value ->
                            GraphTableCell(
                                value,
                                index == 0,
                                false
                            )
                        }
                    }
                }
            }
        }
    }
    Text(
        uiText(R.string.ui_use_the_right_and_bottom_scrollbars_to_move_through_rows_and_colu),
        style = MaterialTheme.typography.labelSmall,
        color = Muted
    )
}

@Composable
private fun GraphTableCell(value: String, isX: Boolean, isHeader: Boolean) {
    val uiText = rememberResourceText()
    // UI sizing/color: change these widths together if translated headers need more room.
    Box(
        Modifier.width(if (isX) 92.dp else 132.dp).height(42.dp).border(.5.dp, SoftGray)
            .padding(horizontal = 8.dp), contentAlignment = Alignment.Center
    ) {
        Text(
            if (value == "undefined") uiText(R.string.ui_undefined) else value,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
            color = if (isHeader) Ink else if (isX) OliveGreen else Ink,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
