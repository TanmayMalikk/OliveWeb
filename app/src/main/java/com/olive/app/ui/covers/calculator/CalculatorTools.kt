package com.olive.app.ui.covers.calculator

import androidx.compose.ui.res.stringResource
import com.olive.app.R
import com.olive.app.ui.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// This screen keeps the bigger math tools away from the fixed everyday keypad.
// UI sizing/color: tool spacing, card corners, and result padding are all controlled in this file.
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun CalculatorTools(
    interactive: Boolean,
    angleMode: CalculatorAngleMode,
    onAngleModeChange: (CalculatorAngleMode)->Unit,
    onClose: ()->Unit,
) {
    val categories=remember { AdvancedTool.entries.map { it.category }.distinct() }
    var category by remember { mutableStateOf(categories.first()) }
    var selected by remember { mutableStateOf(AdvancedTool.entries.first()) }
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    BackHandler(interactive,onBack=onClose)

    Column(
        Modifier.fillMaxSize().background(Cream).safeDrawingPadding().padding(horizontal=18.dp,vertical=12.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onClose,modifier=Modifier.testTag("close_math_tools")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back")
            }
            Text("Math tools",style=MaterialTheme.typography.headlineSmall,color=OliveGreen,fontWeight=FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("Angle",color=Muted,modifier=Modifier.weight(1f))
            FilterChip(selected=angleMode==CalculatorAngleMode.RADIANS,onClick={onAngleModeChange(CalculatorAngleMode.RADIANS)},label={Text(stringResource(R.string.rad))})
            FilterChip(selected=angleMode==CalculatorAngleMode.DEGREES,onClick={onAngleModeChange(CalculatorAngleMode.DEGREES)},label={Text(stringResource(R.string.deg))})
        }
        // Categories scroll sideways so translated labels never squeeze off the screen.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            categories.forEach { item ->
                FilterChip(selected=category==item,onClick={
                    category=item
                    selected=AdvancedTool.entries.first { it.category==item }
                    input="";result=null;error=null
                },label={Text(item)})
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            ExposedDropdownMenuBox(expanded=menuOpen,onExpandedChange={menuOpen=it}) {
                OutlinedTextField(
                    value=selected.title,onValueChange={},readOnly=true,
                    label={Text(stringResource(R.string.calculation))},
                    trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(menuOpen)},
                    modifier=Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).testTag("advanced_tool_picker"),
                )
                ExposedDropdownMenu(expanded=menuOpen,onDismissRequest={menuOpen=false}) {
                    AdvancedTool.entries.filter { it.category==category }.forEach { tool ->
                        DropdownMenuItem(text={Text(tool.title)},onClick={
                            selected=tool;input="";result=null;error=null;menuOpen=false
                        })
                    }
                }
            }
            OutlinedTextField(
                value=input,onValueChange={input=it.take(1200);result=null;error=null},
                label={Text(stringResource(R.string.input))},supportingText={Text("Example: ${selected.example}")},
                minLines=3,maxLines=9,modifier=Modifier.fillMaxWidth().testTag("advanced_tool_input"),
            )
            Button(
                onClick={
                    runCatching { AdvancedCalculatorEngine.calculate(selected,input,angleMode) }
                        .onSuccess { result=it;error=null }
                        .onFailure { result=null;error=it.message ?: "Check the values and try again." }
                },
                enabled=input.isNotBlank(),modifier=Modifier.fillMaxWidth().height(52.dp).testTag("advanced_tool_calculate"),
                shape=RoundedCornerShape(18.dp),
            ) { Text("Calculate",fontWeight=FontWeight.SemiBold) }
            result?.let {
                Surface(color=LightSage,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().testTag("advanced_tool_result")) {
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("Result",color=OliveGreen,fontWeight=FontWeight.SemiBold)
                        Text(it,fontFamily=FontFamily.Monospace,color=Ink)
                    }
                }
            }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("advanced_tool_error")) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
