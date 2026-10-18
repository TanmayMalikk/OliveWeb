package com.olive.app.ui.covers.calculator
import com.olive.app.ui.rememberResourceText
import androidx.compose.foundation.rememberScrollState
import com.olive.app.ui.*

import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.res.stringResource
import com.olive.app.R
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal enum class CalculatorSolver { Polynomial, Quadratic }

// Each solver stays in a small calculator-style popup and handles the equation entirely on this device.
// UI sizing/text: solver dialogs use Material dialog sizing, two-line equation input, bodySmall examples, and OliveGreen results.
@Composable internal fun EquationSolverDialog(type: CalculatorSolver, onClose: ()->Unit) {
    val uiText=rememberResourceText()
    var equation by remember(type) { mutableStateOf("") }
    var result by remember(type) { mutableStateOf<String?>(null) }
    var error by remember(type) { mutableStateOf<String?>(null) }
    val quadratic=type==CalculatorSolver.Quadratic
    AlertDialog(
        onDismissRequest=onClose,
        title={Text(if(quadratic) uiText(R.string.quadratic_equation) else uiText(R.string.polynomial_calculation))},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(equation,{equation=it.take(160);result=null;error=null},
                label={Text(stringResource(R.string.equation))},
                placeholder={Text(if(quadratic) uiText(R.string.ui_x_2_5x_6_0) else uiText(R.string.ui_x_2_1_x_1))},
                singleLine=false,minLines=2,modifier=Modifier.fillMaxWidth().testTag("equation_input").keepVisibleWhileTyping())
            Text(if(quadratic) uiText(R.string.example_x_2_5x_6_0) else uiText(R.string.example_x_2_1_x_1),style=MaterialTheme.typography.bodySmall,color=Muted)
            result?.let { Text(it,color=OliveGreen,fontWeight=FontWeight.SemiBold,modifier=Modifier.testTag("equation_result").semantics { liveRegion=LiveRegionMode.Polite }) }
            error?.let { AccessibleMessage(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("equation_error")) }
        }},
        confirmButton={Button(onClick={
            runCatching { if(quadratic) EquationSolver.quadratic(equation) else EquationSolver.calculatePolynomial(equation) }
                .onSuccess { result=it;error=null }
                .onFailure { result=null;error=uiText.failure(it,R.string.ui_unable_to_solve_this_equation) }
        },enabled=equation.isNotBlank(),modifier=Modifier.testTag("solve_equation")) { Text(stringResource(R.string.solve)) }},
        dismissButton={TextButton(onClick=onClose) { Text(stringResource(R.string.close)) }},
        containerColor=Cream,
    )
}
