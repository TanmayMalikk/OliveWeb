package com.olive.app.ui.covers.fitness

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.olive.app.R
import com.olive.app.ui.shared.LoadingSkeleton
import com.olive.app.ui.shared.SkeletonLayout

@Composable
internal fun ManualFoodEntryDialog(onDismiss: () -> Unit, onContinue: (NutritionFood) -> Unit, initialFood: NutritionFood? = null) {
    val draftSaver = remember {
        listSaver<ManualNutritionDraft, String>(
            save = { listOf(it.name, it.serving) + LabelNutrient.entries.map { field -> it.nutrients[field].orEmpty() } },
            restore = { values -> ManualNutritionDraft(values[0], values[1], LabelNutrient.entries.associateWith { values.getOrNull(it.ordinal + 2).orEmpty() }) },
        )
    }
    var draft by rememberSaveable(stateSaver = draftSaver) { mutableStateOf(initialFood?.let(::manualDraftFromFood) ?: ManualNutritionDraft()) }
    var extras by rememberSaveable { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf<Int?>(null) }
    // Empty labels stay visually lighter than entered values; the outline identifies missing inputs.
    val hintColor = Color(0xFF6B7268)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        unfocusedLabelColor = hintColor,
        focusedLabelColor = hintColor,
        errorLabelColor = hintColor,
        unfocusedPlaceholderColor = hintColor,
        focusedPlaceholderColor = hintColor,
        errorPlaceholderColor = hintColor,
    )
    val context = LocalContext.current
    val camera = rememberNutritionCamera(onPhoto = { bytes ->
        scanning = true
        try {
            val reading = parseNutritionLabel(readNutritionPhoto(context, bytes))
            draft = draft.copy(serving = draft.serving.ifBlank { reading.serving.orEmpty() },
                nutrients = draft.nutrients + reading.nutrients.filterKeys { draft.nutrients[it].isNullOrBlank() })
            if (reading.nutrients.keys.any { !it.required }) extras = true
            scanMessage = if (reading.nutrients.isEmpty()) R.string.nutrition_scan_failed else R.string.nutrition_scan_review
        } finally { scanning = false }
    }, onError = { scanning = false; scanMessage = R.string.nutrition_scan_failed })
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.9f).testTag("manual_food_dialog"),
            shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.nutrition_manual_short), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = camera, enabled = !scanning) {
                        Icon(Icons.Outlined.PhotoCamera, stringResource(R.string.nutrition_scan_label))
                    }
                }
                Text(stringResource(R.string.nutrition_label_short), style = MaterialTheme.typography.bodySmall)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (scanning) LoadingSkeleton(SkeletonLayout.Form, rows = 2)
                    scanMessage?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                    OutlinedTextField(draft.name, { draft = draft.copy(name = it.take(160)) },
                        label = { Text(stringResource(R.string.nutrition_food_name)) }, singleLine = true,
                        colors = fieldColors, isError = draft.name.isBlank(),
                        enabled = !scanning, modifier = Modifier.fillMaxWidth().testTag("manual_food_name"))
                    OutlinedTextField(draft.serving, { draft = draft.copy(serving = it.take(120)) },
                        label = { Text(stringResource(R.string.nutrition_serving_short)) },
                        placeholder = { Text("1 bar (40 g)") }, singleLine = true,
                        colors = fieldColors, isError = draft.serving.isBlank(),
                        enabled = !scanning, modifier = Modifier.fillMaxWidth().testTag("manual_food_serving"))
                    val fields = LabelNutrient.entries.filter { it.required || extras }
                    fields.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { field ->
                                val value = draft.nutrients[field].orEmpty()
                                OutlinedTextField(value, { draft = draft.copy(nutrients = draft.nutrients + (field to it.take(24))) },
                                    label = { Text(stringResource(field.label), style = MaterialTheme.typography.bodySmall) },
                                    suffix = { Text(field.unit) }, singleLine = true, enabled = !scanning,
                                    colors = fieldColors, isError = draft.amount(field) == null,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.weight(1f).testTag("manual_${field.name}"))
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    TextButton(onClick = { extras = !extras }, enabled = !scanning) {
                        Text(stringResource(if (extras) R.string.nutrition_fewer_nutrients else R.string.nutrition_more_nutrients))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = { draft.food()?.let(onContinue) }, enabled = !scanning && draft.valid,
                        modifier = Modifier.testTag("manual_food_continue")) { Text(stringResource(R.string.nutrition_choose_portion)) }
                }
            }
        }
    }
}
