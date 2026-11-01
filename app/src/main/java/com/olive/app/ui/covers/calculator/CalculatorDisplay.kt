// Calculator cover users: draws the calculator input and result area.
package com.olive.app.ui.covers.calculator

import com.olive.app.ui.shared.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.R
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.shared.rememberResourceText

// The bounded history and display stay separate from keypad layout and key dispatch.
@Composable
internal fun ColumnScope.CalculatorDisplay(
    input: CalculatorInput,
    compact: Boolean,
    scientificExpanded: Boolean
) {
    val uiText = rememberResourceText()
    // UI sizing: history scrolls in its own bounded area and the clipped display can never overlap the keys.
    Column(Modifier.fillMaxWidth().weight(if (scientificExpanded) .65f else 1.25f)) {
        Row(
            Modifier.fillMaxWidth().height(if (compact) 28.dp else 32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                uiText(R.string.ui_history),
                modifier = Modifier.weight(1f),
                color = Muted,
                style = MaterialTheme.typography.labelSmall
            )
            if (input.history.isNotEmpty()) {
                TextButton(
                    onClick = input::clearHistory,
                    modifier = Modifier.height(if (compact) 28.dp else 32.dp)
                        .testTag("clear_calculator_history"),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text(
                        stringResource(R.string.clear),
                        fontSize = if (compact) 11.sp else 12.sp,
                        color = OliveGreen
                    )
                }
            }
        }
        if (input.history.isEmpty()) Spacer(Modifier.weight(1f)) else LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("calculator_history_list"),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            itemsIndexed(
                input.history,
                key = { index, entry -> "$index:${entry.expression}:${entry.result}" }) { index, entry ->
                TextButton(
                    onClick = { input.reuse(entry) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp)
                        .testTag("calculator_history_$index").semantics(mergeDescendants = true) {},
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
                ) {
                    Text(
                        entry.expression,
                        modifier = Modifier.weight(1f),
                        color = Muted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = if (compact) 11.sp else 13.sp,
                        maxLines = 1,
                        textAlign = TextAlign.Start
                    )
                    Text(
                        "= ${entry.result}", color = OliveGreen, fontFamily = FontFamily.Monospace,
                        fontSize = if (compact) 11.sp else 13.sp, maxLines = 1
                    )
                }
            }
        }
        val displayScroll = rememberScrollState()
        val resultSize = if (compact) {
            if (input.display.length > 12) 25.sp else 38.sp
        } else {
            if (input.display.length > 12) 31.sp else 48.sp
        }
        val expressionSize = if (compact) 12.sp else 14.sp
        val displayHeight =
            with(LocalDensity.current) { (resultSize.toDp() * 1.4f + expressionSize.toDp() * 1.4f + 8.dp) }
        LaunchedEffect(
            input.display,
            displayScroll.maxValue
        ) { displayScroll.scrollTo(displayScroll.maxValue) }
        Column(
            modifier = Modifier.fillMaxWidth().height(displayHeight).clipToBounds()
                .padding(bottom = 4.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(
                if (input.expression.endsWith("=")) input.expression else "",
                modifier = Modifier.fillMaxWidth(),
                color = Muted,
                fontSize = expressionSize,
                textAlign = TextAlign.End,
                maxLines = 1
            )
            Row(
                Modifier.fillMaxWidth().clipToBounds().horizontalScroll(displayScroll),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    if (input.display == "Error") uiText(R.string.ui_error) else input.display,
                    modifier = Modifier.testTag("calculator_display")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    color = Ink,
                    fontSize = resultSize,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

// Key layout and scientific dispatch are independent of responsive screen rendering.
internal class ScientificKeypad(private val input: CalculatorInput) {
    val baseRows = listOf(
        listOf("AC", "()", "%", "÷"),
        listOf("7", "8", "9", "×"),
        listOf("4", "5", "6", "−"),
        listOf("1", "2", "3", "+"),
        listOf(".", "0", "⌫", "=")
    )

    fun scientificRows(inverse: Boolean) = if (inverse) listOf(
        listOf("√", "π", "^", "!"),
        listOf("INV", "sin⁻¹", "cos⁻¹", "tan⁻¹"),
        listOf("x²", "e", "eˣ", "10ˣ"),
    ) else listOf(
        listOf("√", "π", "^", "!"),
        listOf("INV", "sin", "cos", "tan"),
        listOf("x²", "e", "ln", "log"),
    )

    fun press(
        key: String,
        angleMode: CalculatorAngleMode,
        onInverse: () -> Unit,
        onPin: (String) -> Unit
    ) {
        when (key) {
            "INV" -> onInverse()
            "sin", "cos", "tan" -> input.beginTrig(key, false, angleMode)
            "sin⁻¹", "cos⁻¹", "tan⁻¹" -> input.beginTrig(key.substringBefore('⁻'), true, angleMode)
            "√", "ln", "log" -> input.beginFunction(key, angleMode)
            else -> input.press(key)?.let(onPin)
        }
    }
}

// This screen keeps the bigger math tools away from the fixed everyday keypad.
// UI sizing/color: tool spacing, card corners, and result padding are all controlled in this file.
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun CalculatorTools(
    interactive: Boolean,
    angleMode: CalculatorAngleMode,
    onAngleModeChange: (CalculatorAngleMode) -> Unit,
    onClose: () -> Unit,
) {
    val uiText = rememberResourceText()
    val categories = remember { AdvancedTool.entries.map { it.category }.distinct() }
    var category by remember { mutableIntStateOf(categories.first()) }
    var selected by remember { mutableStateOf(AdvancedTool.entries.first()) }
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    BackHandler(interactive, onBack = onClose)

    Column(
        Modifier.fillMaxSize().background(Cream).safeDrawingPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("close_math_tools")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiText(R.string.ui_back))
            }
            Text(
                uiText(R.string.ui_math_tools),
                style = MaterialTheme.typography.headlineSmall,
                color = OliveGreen,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(uiText(R.string.ui_angle), color = Muted, modifier = Modifier.weight(1f))
            FilterChip(
                selected = angleMode == CalculatorAngleMode.RADIANS,
                onClick = { onAngleModeChange(CalculatorAngleMode.RADIANS) },
                label = { Text(stringResource(R.string.rad)) })
            FilterChip(
                selected = angleMode == CalculatorAngleMode.DEGREES,
                onClick = { onAngleModeChange(CalculatorAngleMode.DEGREES) },
                label = { Text(stringResource(R.string.deg)) })
        }
        // Categories scroll sideways so translated labels never squeeze off the screen.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categories.forEach { item ->
                FilterChip(selected = category == item, onClick = {
                    category = item
                    selected = AdvancedTool.entries.first { it.category == item }
                    input = ""
                    result = null
                    error = null
                }, label = { Text(uiText(item)) })
            }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { menuOpen = it }) {
                OutlinedTextField(
                    value = uiText(selected.title), onValueChange = {}, readOnly = true,
                    label = { Text(stringResource(R.string.calculation)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menuOpen) },
                    modifier = Modifier.fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .testTag("advanced_tool_picker"),
                )
                ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    AdvancedTool.entries.filter { it.category == category }.forEach { tool ->
                        DropdownMenuItem(text = { Text(uiText(tool.title)) }, onClick = {
                            selected = tool
                            input = ""
                            result = null
                            error = null
                            menuOpen =
                                false
                        })
                    }
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it.take(1200)
                    result = null
                    error = null
                },
                label = { Text(stringResource(R.string.input)) },
                supportingText = {
                    Text(
                        uiText(
                            R.string.ui_example_value,
                            uiText(selected.example)
                        )
                    )
                },
                minLines = 3,
                maxLines = 9,
                modifier = Modifier.fillMaxWidth().testTag("advanced_tool_input")
                    .keepVisibleWhileTyping(),
            )
            Button(
                onClick = {
                    runCatching {
                        AdvancedCalculatorEngine.calculate(
                            selected,
                            input,
                            angleMode,
                            uiText
                        )
                    }
                        .onSuccess {
                            result = it
                            error = null
                        }
                        .onFailure {
                            result = null
                            error =
                                uiText.failure(it, R.string.ui_check_the_values_and_try_again)
                        }
                },
                enabled = input.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("advanced_tool_calculate"),
                shape = RoundedCornerShape(18.dp),
            ) { Text(uiText(R.string.ui_calculate), fontWeight = FontWeight.SemiBold) }
            result?.let {
                Surface(
                    color = LightSage,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().testTag("advanced_tool_result")
                        .semantics(mergeDescendants = true) {}) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            uiText(R.string.ui_result),
                            color = OliveGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(it, fontFamily = FontFamily.Monospace, color = Ink)
                    }
                }
            }
            error?.let {
                AccessibleMessage(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("advanced_tool_error")
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
