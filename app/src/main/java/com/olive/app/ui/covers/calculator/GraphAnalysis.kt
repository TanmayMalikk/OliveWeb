// Contains the graph analysis code for this part of Olive.
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
import androidx.compose.ui.unit.sp
import kotlin.math.floor


@Composable
internal fun GraphAnalysisSection(
    equations: List<String>,
    equationIndex: Int,
    onEquationChange: (Int) -> Unit,
    comparisonIndex: Int,
    onComparisonChange: (Int) -> Unit,
    calculation: GraphCalculation,
    onCalculationChange: (GraphCalculation) -> Unit,
    analysisInput: String,
    onInputChange: (String) -> Unit,
    result: String?,
    error: String?,
    onCalculate: () -> Unit,
) {
    val uiText = rememberResourceText()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            uiText(R.string.ui_1_choose_a_function),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            equations.indices.forEach { index ->
                FilterChip(
                    selected = equationIndex == index,
                    onClick = { onEquationChange(index) },
                    label = { Text("Y${index + 1}") })
            }
        }
        Surface(
            color = WarmWhite,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Y${equationIndex + 1} = ${equations[equationIndex]}",
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(12.dp)
            )
        }

        Text(
            uiText(R.string.ui_2_choose_an_analysis),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            GraphCalculation.entries.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { item ->
                        FilterChip(
                            selected = calculation == item,
                            onClick = { onCalculationChange(item) },
                            label = { Text(uiText(item.label), fontSize = 13.sp) },
                            modifier = Modifier.weight(1f).height(34.dp)
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        if (calculation == GraphCalculation.Intersection && equations.size > 1) {
            Text(uiText(R.string.ui_compare_with), style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                equations.indices.filter { it != equationIndex }.forEach { index ->
                    FilterChip(
                        selected = comparisonIndex == index,
                        onClick = { onComparisonChange(index) },
                        label = { Text("Y${index + 1}") })
                }
            }
        }

        OutlinedTextField(
            analysisInput,
            onInputChange,
            label = { Text(uiText(calculation.inputLabel)) },
            supportingText = { Text(uiText(calculation.example)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().keepVisibleWhileTyping()
        )
        Button(
            onClick = onCalculate,
            modifier = Modifier.fillMaxWidth().testTag("run_graph_analysis")
        ) { Text(uiText(R.string.ui_calculate_value, uiText(calculation.label))) }
        error?.let {
            AccessibleMessage(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        result?.let {
            Surface(
                color = PaleOlive,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("graph_analysis_result")
                    .semantics(mergeDescendants = true) {}) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        uiText(R.string.ui_result),
                        style = MaterialTheme.typography.labelLarge,
                        color = OliveGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        it,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

// Formats graph number.
internal fun formatGraphNumber(value: Double): String =
    if (!value.isFinite()) "undefined" else formatCalculatorNumber(value, 7)
