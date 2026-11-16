package com.olive.app.ui.covers.fitness

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.olive.app.R

@Composable
internal fun WaterTracker(totalMl: Int, goalMl: Int, enabled: Boolean, onAdd: (Int, Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val target = (totalMl.toFloat() / goalMl.coerceAtLeast(1)).coerceIn(0f, 1f)
    val fill by animateFloatAsState(target, animationSpec = tween(1000), label = "water_fill")
    val outline = Color(0xFF176B9B)
    val water = Color(0xFF258ED2)
    val ripples = rememberInfiniteTransition(label = "water_ripples")
    val phase by ripples.animateFloat(0f, (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "water_wave_phase")
    val description = stringResource(R.string.nutrition_water_progress, totalMl, goalMl)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Row(Modifier.clickable(enabled = enabled, role = Role.Button) { open = true }
        .semantics { contentDescription = description; progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f) }
        .padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(42.dp, 54.dp)) {
            val glass = Path().apply {
                moveTo(size.width * .12f, size.height * .08f); lineTo(size.width * .88f, size.height * .08f)
                lineTo(size.width * .76f, size.height * .92f); lineTo(size.width * .24f, size.height * .92f); close()
            }
            drawPath(glass, Color(0xFFE4F3FC))
            clipPath(glass) {
                if (fill > 0f) {
                    val level = size.height * (.92f - .84f * fill)
                    val amplitude = minOf(1.8.dp.toPx(), size.height * .84f * fill / 3f)
                    fun wave(offset: Float): Path = Path().apply {
                        moveTo(0f, size.height)
                        for (step in 0..32) {
                            val x = size.width * step / 32f
                            val y = level + sin(x / size.width * 2f * PI.toFloat() + phase + offset) * amplitude
                            lineTo(x, y)
                        }
                        lineTo(size.width, size.height)
                        close()
                    }
                    drawPath(wave(PI.toFloat()), water.copy(alpha = .35f))
                    drawPath(wave(0f), water.copy(alpha = .85f))
                }
                drawLine(Color.White.copy(alpha = .8f), Offset(size.width * .24f, size.height * .2f),
                    Offset(size.width * .29f, size.height * .62f), strokeWidth = 1.5.dp.toPx())
            }
            drawPath(glass, outline, style = Stroke(2.dp.toPx()))
        }
        Column(Modifier.padding(start = 6.dp)) {
            Text(stringResource(R.string.nutrition_water), style = MaterialTheme.typography.labelLarge)
            Text("$totalMl / $goalMl ml", style = MaterialTheme.typography.labelSmall)
        }
    }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onAdd(-250, goalMl) }, enabled = enabled && totalMl > 0) {
                Icon(Icons.Outlined.Remove, stringResource(R.string.nutrition_water_minus))
            }
            Text("250 ml", style = MaterialTheme.typography.labelSmall)
            IconButton(onClick = { onAdd(250, goalMl) }, enabled = enabled) {
                Icon(Icons.Outlined.Add, stringResource(R.string.nutrition_water_plus))
            }
        }
    }
    if (open) {
        var amount by remember { mutableStateOf("250") }
        var goal by remember { mutableStateOf(goalMl.toString()) }
        AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.nutrition_add_water)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(amount, { amount = it.take(5) }, label = { Text(stringResource(R.string.nutrition_water_amount)) },
                    suffix = { Text("ml") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(150, 250, 500).forEach { value ->
                        SuggestionChip(onClick = { amount = value.toString() }, label = { Text("$value ml") })
                    }
                }
                OutlinedTextField(goal, { goal = it.take(5) }, label = { Text(stringResource(R.string.nutrition_water_goal)) },
                    suffix = { Text("ml") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            } },
            confirmButton = { Button(onClick = { onAdd(amount.toInt(), goal.toInt()); open = false },
                enabled = amount.toIntOrNull()?.let { it in 1..10000 } == true && goal.toIntOrNull()?.let { it in 250..10000 } == true) { Text(stringResource(R.string.nutrition_add_water)) } },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } })
    }
}
