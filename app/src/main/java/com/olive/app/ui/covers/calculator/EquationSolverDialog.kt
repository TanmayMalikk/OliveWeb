package com.olive.app.ui.covers.calculator
import com.olive.app.ui.*

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
    var equation by remember(type) { mutableStateOf("") }
    var result by remember(type) { mutableStateOf<String?>(null) }
    var error by remember(type) { mutableStateOf<String?>(null) }
    val quadratic=type==CalculatorSolver.Quadratic
    AlertDialog(
        onDismissRequest=onClose,
        title={Text(if(quadratic) "Quadratic equation" else "Polynomial calculation")},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(equation,{equation=it.take(160);result=null;error=null},
                label={Text(stringResource(R.string.equation))},
                placeholder={Text(if(quadratic) "x^2 - 5x + 6 = 0" else "(x^2 - 1) / (x - 1)")},
                singleLine=false,minLines=2,modifier=Modifier.fillMaxWidth().testTag("equation_input"))
            Text(if(quadratic) "Example: x^2 - 5x + 6 = 0" else "Example: (x^2 - 1) / (x - 1)",style=MaterialTheme.typography.bodySmall,color=Muted)
            result?.let { Text(it,color=OliveGreen,fontWeight=FontWeight.SemiBold,modifier=Modifier.testTag("equation_result")) }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("equation_error")) }
        }},
        confirmButton={Button(onClick={
            runCatching { if(quadratic) EquationSolver.quadratic(equation) else EquationSolver.calculatePolynomial(equation) }
                .onSuccess { result=it;error=null }
                .onFailure { result=null;error=it.message ?: "Unable to solve this equation." }
        },enabled=equation.isNotBlank(),modifier=Modifier.testTag("solve_equation")) { Text(stringResource(R.string.solve)) }},
        dismissButton={TextButton(onClick=onClose) { Text(stringResource(R.string.close)) }},
        containerColor=Cream,
    )
}
