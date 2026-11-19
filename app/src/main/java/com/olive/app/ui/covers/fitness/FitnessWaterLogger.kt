package com.olive.app.ui.covers.fitness

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.olive.app.R
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun WaterTracker(totalMl: Int, goalMl: Int, enabled: Boolean, onAdd: (Int, Int) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val fraction = (totalMl.toFloat() / goalMl.coerceAtLeast(1)).coerceIn(0f, 1f)
    val description = stringResource(R.string.nutrition_water_progress, totalMl, goalMl)
    TextButton(
        onClick = { open = true },
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 6.dp),
        modifier = Modifier.semantics {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
        },
    ) {
        WaterGlass(fraction, Modifier.size(42.dp, 56.dp))
        Column(Modifier.padding(start = 4.dp).clearAndSetSemantics { }) {
            Text(stringResource(R.string.nutrition_water), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.fitness_diary_volume, totalMl), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (open) WaterLogDialog(totalMl, goalMl, enabled, onDismiss = { open = false }) { amount, goal ->
        onAdd(amount, goal)
        open = false
    }
}

@Composable
private fun WaterLogDialog(
    totalMl: Int,
    goalMl: Int,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (Int, Int) -> Unit,
) {
    var adjustment by rememberSaveable { mutableIntStateOf(0) }
    var customVisible by rememberSaveable { mutableStateOf(false) }
    var customAmount by rememberSaveable { mutableStateOf("") }
    var goal by rememberSaveable { mutableStateOf(goalMl.toString()) }
    val updatedGoal = goal.toIntOrNull()
    val total = (totalMl.toLong() + adjustment).coerceAtLeast(0)
    fun adjust(amount: Int) {
        adjustment = (adjustment + amount).coerceIn(-minOf(totalMl, 10_000), 10_000)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().imePadding().padding(horizontal = 18.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.close)) }
                    Text(stringResource(R.string.nutrition_add_water), style = MaterialTheme.typography.titleLarge)
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(stringResource(R.string.fitness_diary_water_total), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.fitness_diary_volume, total), style = MaterialTheme.typography.headlineMedium)
                            WaterGlass(total.toFloat() / (updatedGoal?.coerceAtLeast(1) ?: goalMl), Modifier.size(60.dp, 80.dp))
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalIconButton(onClick = { adjust(-250) }, enabled = enabled && total > 0 && adjustment > -10_000) {
                            Icon(Icons.Outlined.Remove, stringResource(R.string.nutrition_water_minus))
                        }
                        listOf(250, 500, 1000).forEach { amount ->
                            val actionLabel = stringResource(R.string.fitness_diary_add_volume, amount)
                            FilledTonalButton(
                                modifier = Modifier.weight(1f).semantics {
                                    contentDescription = actionLabel
                                },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                onClick = { adjust(amount) },
                                enabled = enabled && adjustment <= 10_000 - amount,
                            ) {
                                Text(stringResource(R.string.fitness_diary_volume, amount), Modifier.clearAndSetSemantics { })
                            }
                        }
                    }
                    OutlinedButton(onClick = { customVisible = !customVisible }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.fitness_diary_custom_water))
                    }
                    if (customVisible) {
                        val amount = customAmount.toIntOrNull()
                        OutlinedTextField(
                            customAmount, { customAmount = it.filter(Char::isDigit).take(5) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(stringResource(R.string.nutrition_water_amount)) },
                            suffix = { Text(stringResource(R.string.nutrition_volume_unit)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            trailingIcon = {
                                IconButton(
                                    onClick = { amount?.let { adjust(it); customAmount = ""; customVisible = false } },
                                    enabled = enabled && amount != null && amount in 1..minOf(10_000, 10_000 - adjustment),
                                ) { Icon(Icons.Outlined.Add, stringResource(R.string.nutrition_add_water)) }
                            },
                        )
                    }
                    OutlinedTextField(
                        goal, { goal = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.nutrition_water_goal)) },
                        suffix = { Text(stringResource(R.string.nutrition_volume_unit)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = updatedGoal == null || updatedGoal !in 250..10_000,
                    )
                }
                Button(
                    onClick = { updatedGoal?.let { onSave(adjustment, it) } },
                    enabled = enabled && adjustment != 0 && updatedGoal != null && updatedGoal in 250..10_000,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                ) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

@Composable
private fun WaterGlass(fraction: Float, modifier: Modifier) {
    val target = (fraction).coerceIn(0f, 1f)
    val fill by animateFloatAsState(target, animationSpec = tween(1000), label = "water_fill")
    val outline = Color(0xFF176B9B)
    val water = Color(0xFF258ED2)
    val ripples = rememberInfiniteTransition(label = "water_ripples")
    val phase by ripples.animateFloat(0f, (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "water_wave_phase")
    Canvas(modifier) {
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
}
