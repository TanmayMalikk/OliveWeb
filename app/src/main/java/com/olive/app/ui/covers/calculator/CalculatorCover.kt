// Contains the calculator cover code for this part of Olive.
package com.olive.app.ui.covers.calculator

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.ui.shared.rememberResourceText

import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.olive.app.ui.shared.LocalizedIcon as Icon
import androidx.compose.ui.res.stringResource
import com.olive.app.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.olive.app.ui.covers.shared.CoverSettingsPage

// Keep the calculator looking like a calculator, with no account controls on the cover.
// This shell owns responsive keypad layout; CalculatorDisplay renders history/results and ScientificKeypad dispatches keys.
@Composable
internal fun CalculatorCover(session: SessionViewModel, interactive: Boolean = true) {
    val uiText = rememberResourceText()
    val context = LocalContext.current
    val calculatorAccountId = session.coverAccountId ?: "guest"
    val historyStore = remember(context, calculatorAccountId) {
        CalculatorHistoryStore(
            context,
            calculatorAccountId
        )
    }
    val input = remember(historyStore) { CalculatorInput() }
    var historyLoaded by remember(historyStore) { mutableStateOf(false) }
    LaunchedEffect(historyStore) {
        input.restoreHistory(withContext(Dispatchers.IO) { historyStore.load() })
        historyLoaded = true
    }
    LaunchedEffect(input.history, historyLoaded) {
        if (historyLoaded) withContext(Dispatchers.IO) { historyStore.save(input.history) }
    }
    var solver by remember { mutableStateOf<CalculatorSolver?>(null) }
    var graphing by remember { mutableStateOf(false) }
    var tools by remember { mutableStateOf(false) }
    var angleMode by remember { mutableStateOf(CalculatorAngleMode.RADIANS) }
    var inverseTrig by remember { mutableStateOf(false) }
    var scientificExpanded by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var fitnessOpen by remember { mutableStateOf(false) }
    LaunchedEffect(session.coverReset) { input.reset() }
    if (fitnessOpen) {
        FitnessTrackerScreen(session.coverAccountId ?: "guest") { fitnessOpen = false }
        return
    }
    if (settingsOpen) {
        CoverSettingsPage(session) { settingsOpen = false }
        return
    }
    if (graphing) {
        GraphingCalculator(interactive, angleMode, { angleMode = it }) { graphing = false }
        return
    }
    if (tools) {
        CalculatorTools(interactive, angleMode, { angleMode = it }) { tools = false }
        return
    }
    // Graph and tool screens handle their own Back actions; Back on the main keypad stays inside the cover.
    BackHandler(interactive) { }
    BoxWithConstraints(Modifier.fillMaxSize().background(Cream).testTag("calculator_cover")) {
        val compact = maxHeight < 720.dp || maxWidth < 350.dp
        val rowSpacing = if (compact) 5.dp else 7.dp
        val keypad = remember(input) { ScientificKeypad(input) }
        val scientificRows = keypad.scientificRows(inverseTrig)
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = if (compact) 10.dp else 16.dp)
                .padding(bottom = if (compact) 6.dp else 10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Calculate,
                    null,
                    Modifier.size(if (compact) 18.dp else 21.dp).testTag("cover_brand_calculator"),
                    tint = OliveGreen
                )
                Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
                Text(
                    stringResource(R.string.better_calculator),
                    color = OliveGreen,
                    fontSize = if (compact) 16.sp else 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { fitnessOpen = true },
                    enabled = interactive,
                    modifier = Modifier.testTag("calculator_fitness"),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.DirectionsWalk, stringResource(R.string.fitness_calculator))
                }
                IconButton(
                    onClick = { settingsOpen = true },
                    enabled = interactive,
                    modifier = Modifier.testTag("calculator_settings"),
                ) {
                    Icon(Icons.Outlined.Settings, stringResource(R.string.settings))
                }
            }
            Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(
                    onClick = { solver = CalculatorSolver.Polynomial },
                    modifier = Modifier.weight(1f).height(if (compact) 38.dp else 44.dp)
                        .testTag("polynomial_solver"),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    Text(
                        stringResource(R.string.polynomial),
                        fontSize = if (compact) 11.sp else 13.sp,
                        maxLines = 1
                    )
                }
                FilledTonalButton(
                    onClick = { solver = CalculatorSolver.Quadratic },
                    modifier = Modifier.weight(1f).height(if (compact) 38.dp else 44.dp)
                        .testTag("quadratic_solver"),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    Text(
                        stringResource(R.string.quadratic),
                        fontSize = if (compact) 11.sp else 13.sp,
                        maxLines = 1
                    )
                }
                FilledTonalButton(
                    onClick = { graphing = true },
                    modifier = Modifier.weight(1f).height(if (compact) 38.dp else 44.dp)
                        .testTag("graph_editor"),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    Text("Y =", fontSize = if (compact) 12.sp else 14.sp)
                }
                FilledTonalButton(
                    onClick = { tools = true },
                    modifier = Modifier.weight(1f).height(if (compact) 38.dp else 44.dp)
                        .testTag("math_tools"),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    Text(
                        uiText(R.string.ui_tools),
                        fontSize = if (compact) 11.sp else 13.sp,
                        maxLines = 1
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().height(if (compact) 42.dp else 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.angle_mode),
                    color = Muted,
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = angleMode == CalculatorAngleMode.RADIANS,
                    onClick = { angleMode = CalculatorAngleMode.RADIANS },
                    label = { Text(stringResource(R.string.rad)) },
                    modifier = Modifier.testTag("calculator_radians")
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = angleMode == CalculatorAngleMode.DEGREES,
                    onClick = { angleMode = CalculatorAngleMode.DEGREES },
                    label = { Text(stringResource(R.string.deg)) },
                    modifier = Modifier.testTag("calculator_degrees")
                )
            }
            CalculatorDisplay(input, compact, scientificExpanded)
            Row(
                Modifier.fillMaxWidth().height(if (compact) 30.dp else 36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { scientificExpanded = !scientificExpanded },
                    modifier = Modifier.size(if (compact) 30.dp else 36.dp)
                        .testTag("scientific_toggle")
                ) {
                    Icon(
                        if (scientificExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        if (scientificExpanded) uiText(R.string.ui_hide_scientific_operators) else uiText(
                            R.string.ui_show_scientific_operators
                        )
                    )
                }
                Text(
                    if (scientificExpanded) uiText(R.string.ui_hide_scientific_operators) else uiText(
                        R.string.ui_show_scientific_operators
                    ), style = MaterialTheme.typography.labelSmall, color = Muted
                )
            }
            val rows = if (scientificExpanded) scientificRows + keypad.baseRows else keypad.baseRows
            Column(
                Modifier.fillMaxWidth().weight(if (scientificExpanded) 2.15f else 1.45f),
                verticalArrangement = Arrangement.spacedBy(rowSpacing)
            ) {
                rows.forEachIndexed { rowIndex, row ->
                    Row(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(rowSpacing)
                    ) {
                        row.forEach { key ->
                            CalculatorKey(
                                key,
                                compact,
                                rowIndex < scientificRows.size && scientificExpanded,
                                !session.busy,
                                selected = if (key == "INV") inverseTrig else null
                            ) {
                                keypad.press(
                                    key,
                                    angleMode,
                                    { inverseTrig = !inverseTrig },
                                    session::submitCalculatorPin
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    solver?.let { selected ->
        EquationSolverDialog(selected) { solver = null }
    }
}

@Composable
private fun RowScope.CalculatorKey(
    key: String,
    compact: Boolean,
    scientific: Boolean,
    enabled: Boolean,
    selected: Boolean? = null,
    onClick: () -> Unit
) {
    val uiText = rememberResourceText()
    val isOperator = key in setOf("+", "−", "×", "÷")
    val background = when {
        key == "=" -> OliveGreen
        isOperator -> SageGreen
        key in setOf(
            "AC",
            "()",
            "%",
            "⌫"
        ) -> LightSage

        scientific -> PaleOlive
        else -> WarmWhite
    }
    val tag = when (key) {
        "=" -> "equals"
        "AC" -> "clear"
        "⌫" -> "backspace"
        else -> key
    }
    val spokenKey = when (key) {
        "=" -> uiText(R.string.a11y_equals)
        "AC" -> uiText(R.string.a11y_clear_all)
        "⌫" -> uiText(
            R.string.a11y_delete_digit
        )

        "−" -> uiText(R.string.a11y_minus)
        "×" -> uiText(R.string.a11y_multiply)
        "÷" -> uiText(
            R.string.a11y_divide
        )

        "()" -> uiText(R.string.a11y_parentheses)
        "%" -> uiText(R.string.a11y_percent)
        "." -> uiText(
            R.string.a11y_decimal_point
        )

        "√" -> uiText(R.string.a11y_square_root)
        "π" -> uiText(R.string.a11y_pi)
        "^" -> uiText(R.string.a11y_power)
        "!" -> uiText(
            R.string.a11y_factorial
        )

        "INV" -> uiText(R.string.a11y_inverse_functions)
        "sin" -> uiText(R.string.a11y_sine)
        "cos" -> uiText(R.string.a11y_cosine)
        "tan" -> uiText(
            R.string.a11y_tangent
        )

        "sin⁻¹" -> uiText(R.string.a11y_inverse_sine)
        "cos⁻¹" -> uiText(R.string.a11y_inverse_cosine)
        "tan⁻¹" -> uiText(
            R.string.a11y_inverse_tangent
        )

        "x²" -> uiText(R.string.a11y_squared)
        "eˣ" -> uiText(R.string.a11y_exponential)
        "10ˣ" -> uiText(
            R.string.a11y_ten_to_the_power
        )

        "ln" -> uiText(R.string.a11y_natural_logarithm)
        "log" -> uiText(R.string.a11y_logarithm)
        else -> key
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f).fillMaxHeight().testTag("calc_key_$tag").semantics {
            if (selected != null) this.selected = selected
        },
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = background,
            contentColor = if (key == "=") Paper else if (isOperator) OliveGreen else Ink
        )
    ) {
        Text(
            key,
            modifier = Modifier.clearAndSetSemantics { contentDescription = spokenKey },
            fontSize = if (scientific) {
                if (compact) 13.sp else 16.sp
            } else {
                if (compact) 19.sp else 24.sp
            },
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}
