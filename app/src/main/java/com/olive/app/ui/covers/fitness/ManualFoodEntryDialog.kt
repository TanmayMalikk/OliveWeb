package com.olive.app.ui.covers.fitness

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveTextButton as TextButton
import com.olive.app.ui.shared.oliveErrorShake
import com.olive.app.ui.shared.rememberFieldErrorShakeTrigger
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.olive.app.R
import com.olive.app.ocr.PaddleOcrManager
import com.olive.app.ui.shared.LoadingSkeleton
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.SkeletonLayout
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Displays the manual food entry dialog overlay.
 *
 * @param onDismiss Callback invoked when the dialog or sheet is dismissed.
 * @param onContinue Callback invoked when continue changes or occurs.
 * @param initialFood Optional initial food supplied to [ManualFoodEntryDialog].
 */
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
    val hintColor = Muted
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
                var manualShake by remember { mutableIntStateOf(0) }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (scanning) LoadingSkeleton(SkeletonLayout.Form, rows = 2)
                    scanMessage?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                    val nameShake = rememberFieldErrorShakeTrigger(if (draft.name.isBlank()) "name" else null, manualShake)
                    OutlinedTextField(draft.name, { draft = draft.copy(name = it.take(160)) },
                        label = { Text(stringResource(R.string.nutrition_food_name)) }, singleLine = true,
                        colors = fieldColors, isError = draft.name.isBlank(),
                        enabled = !scanning, modifier = Modifier.fillMaxWidth().testTag("manual_food_name").oliveErrorShake(nameShake))
                    val servingShake = rememberFieldErrorShakeTrigger(if (draft.serving.isBlank()) "serving" else null, manualShake)
                    OutlinedTextField(draft.serving, { draft = draft.copy(serving = it.take(120)) },
                        label = { Text(stringResource(R.string.nutrition_serving_short)) },
                        placeholder = { Text("1 bar (40 g)") }, singleLine = true,
                        colors = fieldColors, isError = draft.serving.isBlank(),
                        enabled = !scanning, modifier = Modifier.fillMaxWidth().testTag("manual_food_serving").oliveErrorShake(servingShake))
                    val fields = LabelNutrient.entries.filter { it.required || extras }
                    fields.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { field ->
                                val value = draft.nutrients[field].orEmpty()
                                val fieldInvalid = draft.amount(field) == null
                                val fieldShake = rememberFieldErrorShakeTrigger(if (fieldInvalid) field.name else null, manualShake)
                                OutlinedTextField(value, { draft = draft.copy(nutrients = draft.nutrients + (field to it.take(24))) },
                                    label = { Text(stringResource(field.label), style = MaterialTheme.typography.bodySmall) },
                                    suffix = { Text(field.unit) }, singleLine = true, enabled = !scanning,
                                    colors = fieldColors, isError = fieldInvalid,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.weight(1f).testTag("manual_${field.name}").oliveErrorShake(fieldShake))
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
                    Button(
                        onClick = {
                            val food = draft.food()
                            if (food == null) manualShake += 1
                            else onContinue(food)
                        },
                        enabled = !scanning,
                        modifier = Modifier.testTag("manual_food_continue"),
                    ) { Text(stringResource(R.string.nutrition_choose_portion)) }
                }
            }
        }
    }
}

internal enum class LabelNutrient(val label: Int, val unit: String, val required: Boolean = false) {
    CALORIES(R.string.calories, "kcal", true),
    PROTEIN(R.string.protein, "g", true),
    CARBS(R.string.carbohydrates, "g", true),
    FAT(R.string.fat, "g", true),
    SATURATED_FAT(R.string.saturated_fat, "g"),
    TRANS_FAT(R.string.trans_fat, "g"),
    FIBER(R.string.fiber, "g"),
    SUGAR(R.string.sugar, "g"),
    SODIUM(R.string.sodium, "mg"),
    CHOLESTEROL(R.string.cholesterol, "mg"),
    POTASSIUM(R.string.potassium, "mg"),
}

internal data class ManualNutritionDraft(
    val name: String = "",
    val serving: String = "",
    val nutrients: Map<LabelNutrient, String> = emptyMap(),
) {
    /**
     * Computes amount.
     *
     * @param field Field supplied to [amount].
     * @return The computed amount number, or `null` if unavailable.
     */
    fun amount(field: LabelNutrient): Float? {
        val raw = nutrients[field].orEmpty().trim()
        if (raw.isEmpty()) return if (field.required) null else 0f
        if (!Regex("""(?:[0-9]+(?:[.,][0-9]*)?|[.,][0-9]+)""").matches(raw)) return null
        return raw.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1_000_000f }
    }

    val valid: Boolean get() = name.isNotBlank() && serving.isNotBlank() &&
        LabelNutrient.entries.all { amount(it) != null }

    /**
     * Returns the food.
     *
     * @return The resulting [NutritionFood], or `null` if unavailable.
     */
    fun food(): NutritionFood? {
        if (!valid) return null
        return NutritionFood(
            id = "manual:${UUID.randomUUID()}", name = name.trim(), brand = "",
            servingDescription = serving.trim(), source = FoodDataSource.MANUAL,
            calories = amount(LabelNutrient.CALORIES)!!,
            proteinGrams = amount(LabelNutrient.PROTEIN)!!,
            carbohydrateGrams = amount(LabelNutrient.CARBS)!!,
            fatGrams = amount(LabelNutrient.FAT)!!,
            saturatedFatGrams = amount(LabelNutrient.SATURATED_FAT)!!,
            transFatGrams = amount(LabelNutrient.TRANS_FAT)!!,
            fiberGrams = amount(LabelNutrient.FIBER)!!,
            sugarGrams = amount(LabelNutrient.SUGAR)!!,
            sodiumMilligrams = amount(LabelNutrient.SODIUM)!!,
            cholesterolMilligrams = amount(LabelNutrient.CHOLESTEROL)!!,
            potassiumMilligrams = amount(LabelNutrient.POTASSIUM)!!,
        )
    }
}

internal data class NutritionLabelReading(
    val serving: String?,
    val nutrients: Map<LabelNutrient, String>,
)

/**
 * OCR only proposes values. Missing fields remain blank for the user to check against the label.
 *
 * @param text Text string to display, parse, or send.
 * @return The resulting [NutritionLabelReading] for parse nutrition label.
 */
internal fun parseNutritionLabel(text: String): NutritionLabelReading {
    val lines = text.take(30_000).lines().map(String::trim).filter(String::isNotEmpty)
    val labels = mapOf(
        LabelNutrient.CALORIES to "(?:calories|energy|énergie)",
        LabelNutrient.PROTEIN to "(?:protein|protéines?)",
        LabelNutrient.CARBS to "(?:(?:total )?carbohydrates?|glucides)",
        LabelNutrient.FAT to "(?:(?:total )?fat|lipides)",
        LabelNutrient.SATURATED_FAT to "(?:saturated(?: fat)?|saturés?)",
        LabelNutrient.TRANS_FAT to "(?:trans(?: fat)?)",
        LabelNutrient.FIBER to "(?:(?:dietary )?fib(?:er|re)|fibres)",
        LabelNutrient.SUGAR to "(?:(?:total )?sugars?|sucres)",
        LabelNutrient.SODIUM to "sodium",
        LabelNutrient.CHOLESTEROL to "(?:cholesterol|cholestérol)",
        LabelNutrient.POTASSIUM to "potassium",
    )
    val values = mutableMapOf<LabelNutrient, String>()
    for ((field, label) in labels) {
        val index = lines.indexOfFirst { Regex("""^$label\b""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        if (index < 0) continue
        val line = lines[index]
        val remainder = line.replaceFirst(Regex("""^$label\b""", RegexOption.IGNORE_CASE), "")
        // A following line is used only when it starts with a value, never another nutrient label.
        val next = lines.getOrNull(index + 1).orEmpty().takeIf {
            Regex("""^[0-9]""").containsMatchIn(it)
        }.orEmpty()
        val candidate = if (remainder.any(Char::isDigit)) remainder else next
        val number = if (field == LabelNutrient.CALORIES) {
            // Prefer kcal explicitly; never interpret kJ as kcal or a daily-value percentage as energy.
            Regex("""(?<![0-9.,-])([0-9]+(?:[.,][0-9]+)?)\s*kcal""", RegexOption.IGNORE_CASE)
                .find(candidate)?.groupValues?.get(1)
                ?: Regex("""^\s*[:/]?\s*([0-9]+(?:[.,][0-9]+)?)(?![0-9.,])\s*(?:$|cal\b)""", RegexOption.IGNORE_CASE)
                    .find(candidate)?.groupValues?.get(1)
        } else {
            Regex("""(?<![0-9.,-])([0-9]+(?:[.,][0-9]+)?)\s*(mg|g)\b""", RegexOption.IGNORE_CASE)
                .find(candidate)?.let { match ->
                    val amount = match.groupValues[1].replace(',', '.').toFloatOrNull() ?: return@let null
                    val unit = match.groupValues[2].lowercase()
                    val converted = when {
                        field.unit == "mg" && unit == "g" -> amount * 1000f
                        field.unit == "g" && unit == "mg" -> amount / 1000f
                        else -> amount
                    }
                    converted.takeIf { it.isFinite() && it in 0f..1_000_000f }?.toString()
                }
        }
        number?.replace(',', '.')?.let { values[field] = it }
    }
    val serving = lines.firstNotNullOfOrNull { line ->
        Regex("""^(?:serving size|portion|per|pour)\s*[:/]?\s+(.+)$""", RegexOption.IGNORE_CASE)
            .find(line)?.groupValues?.get(1)?.trim()?.takeIf {
                it.any(Char::isDigit) && !it.contains('%') && !it.contains("container", ignoreCase = true)
            }
    }
    return NutritionLabelReading(serving, values)
}

/**
 * Computes manual draft from food.
 *
 * @param food Food supplied to [manualDraftFromFood].
 * @return The resulting manual draft from food.
 */
internal fun manualDraftFromFood(food: NutritionFood) = ManualNutritionDraft(food.name, food.servingDescription,
    listOf(food.calories, food.proteinGrams, food.carbohydrateGrams, food.fatGrams,
        food.saturatedFatGrams, food.transFatGrams, food.fiberGrams, food.sugarGrams,
        food.sodiumMilligrams, food.cholesterolMilligrams, food.potassiumMilligrams)
        .mapIndexed { index, value -> LabelNutrient.entries[index] to java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString() }.toMap())

/**
 * Full-resolution camera input is private, bounded, and deleted after recognition or cancellation.
 *
 * @param onPhoto Callback invoked when photo changes or occurs.
 * @param onError Callback invoked when an error occurs.
 * @return The resulting [() -> Unit] for remember nutrition camera.
 */
@Composable
internal fun rememberNutritionCamera(onPhoto: suspend (ByteArray) -> Unit, onError: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val consumer by rememberUpdatedState(onPhoto)
    val failure by rememberUpdatedState(onError)
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val file = pending?.let(::File)
        pending = null
        if (file != null) scope.launch {
            try {
                if (captured) {
                    val bytes = withContext(Dispatchers.IO) {
                        require(file.length() in 1..(12L * 1024 * 1024))
                        file.readBytes()
                    }
                    consumer(bytes)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { failure() }
            finally { withContext(NonCancellable + Dispatchers.IO) { file.delete() } }
        }
    }
    /**
     * Launches the target.
     */
    fun launch() {
        scope.launch {
            var prepared: File? = null
            try {
                val file = withContext(Dispatchers.IO) {
                    val directory = File(context.cacheDir, "fitness-scans").apply { mkdirs() }
                    directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }
                        ?.forEach(File::delete)
                    File.createTempFile("label-", ".jpg", directory).also { prepared = it }
                }
                pending = file.absolutePath
                camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.documents", file))
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { prepared?.delete() }
                pending = null
                throw cancelled
            } catch (_: Exception) {
                withContext(Dispatchers.IO) { prepared?.delete() }
                pending = null
                failure()
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launch() else failure()
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launch()
        else permission.launch(Manifest.permission.CAMERA)
    }
}

/**
 * Reads nutrition photo.
 *
 * @param context Android context used to access system services, resources, or storage.
 * @param bytes Raw byte array payload.
 * @return The resulting read nutrition photo string.
 */
internal suspend fun readNutritionPhoto(context: android.content.Context, bytes: ByteArray): String {
    val engine = PaddleOcrManager(context.applicationContext)
    try {
        val result = engine.recognizeDocument(bytes)
        check(result.success)
        return result.extractedText
    } finally { withContext(NonCancellable) { engine.release() } }
}

/**
 * Reads barcode photo.
 *
 * @param context Android context used to access system services, resources, or storage.
 * @param bytes Raw byte array payload.
 * @return The resulting read barcode photo string, or `null` when unavailable.
 */
internal suspend fun readBarcodePhoto(context: android.content.Context, bytes: ByteArray): String? {
    // Decode the bars first; OCR of printed digits is a fallback for a damaged barcode.
    val options = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_EAN_13,
        Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A).build()
    val scanner = BarcodeScanning.getClient(options)
    val bitmap = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    try {
        val results = withContext(NonCancellable) { scanner.process(InputImage.fromBitmap(bitmap, 0)).await() }
        val codes = results.mapNotNull { it.rawValue }.filter(::validFoodBarcode).map { it.padStart(13, '0') }.distinct()
        if (codes.size == 1) return codes.single()
        if (codes.size > 1) return null // Let the user reframe one label instead of choosing the wrong product.
    } finally { scanner.close(); bitmap.recycle() }
    return barcodeFromLabelText(readNutritionPhoto(context, bytes))?.padStart(13, '0')
}

/**
 * Validates food barcode.
 *
 * @param value Input value to store, parse, or format.
 * @return `true` if valid food barcode, or `false` otherwise.
 */
internal fun validFoodBarcode(value: String): Boolean {
    if (value.length !in listOf(8, 12, 13) || value.any { !it.isDigit() }) return false
    val sum = value.dropLast(1).reversed().mapIndexed { index, digit -> digit.digitToInt() * if (index % 2 == 0) 3 else 1 }.sum()
    return (10 - sum % 10) % 10 == value.last().digitToInt()
}

/**
 * Computes barcode from label text.
 *
 * @param text Text string to display, parse, or send.
 * @return The resulting barcode from label text string, or `null` when unavailable.
 */
internal fun barcodeFromLabelText(text: String): String? {
    val candidates = Regex("""(?<![0-9])(?:[0-9][ \t-]*){8,}(?![0-9])""")
        .findAll(text).map { it.value.filter(Char::isDigit) }.filter(::validFoodBarcode).distinct().toList()
    return candidates.singleOrNull()
}

/**
 * Match the diary storage range and accept the decimal separator used by the keyboard locale.
 *
 * @param value Input value to store, parse, or format.
 * @return The computed parse serving amount number, or `null` if unavailable.
 */
internal fun parseServingAmount(value: String): Float? {
    val clean = value.trim()
    if (!Regex("""(?:[0-9]+(?:[.,][0-9]*)?|[.,][0-9]+)""").matches(clean)) return null
    return clean.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it in 0.0001f..10_000f }
}

internal data class FoodPortion(val food: NutritionFood, val servings: Float)
internal data class ServingBasis(val amount: Float, val unit: String)
/**
 * Computes serving basis.
 *
 * @param description Description supplied to [servingBasis].
 * @return The resulting [ServingBasis], or `null` if unavailable.
 */
internal fun servingBasis(description: String): ServingBasis? {
    val match = Regex("""([0-9]+(?:[.,][0-9]+)?)\s*(kg|ml|oz|g|l)\b""", RegexOption.IGNORE_CASE).find(description) ?: return null
    val raw = match.groupValues[1].replace(',', '.').toFloatOrNull() ?: return null
    val unit = match.groupValues[2].lowercase()
    val amount = raw * if (unit == "kg" || unit == "l") 1000f else 1f
    return ServingBasis(amount, when(unit) { "kg" -> "g"; "l" -> "ml"; else -> unit }).takeIf { amount.isFinite() && amount > 0 }
}
/**
 * Computes serving units.
 *
 * @param food Food supplied to [servingUnits].
 * @return The resulting serving units.
 */
internal fun servingUnits(food: NutritionFood) = (listOf(food) + food.servingOptions)
    .mapNotNull { servingBasis(it.servingDescription)?.unit }.distinct()
/**
 * Computes custom food portion.
 *
 * @param food Food supplied to [customFoodPortion].
 * @param amount Amount supplied to [customFoodPortion].
 * @param unit Unit supplied to [customFoodPortion].
 * @return The resulting [FoodPortion], or `null` if unavailable.
 */
internal fun customFoodPortion(food: NutritionFood, amount: String, unit: String): FoodPortion? {
    val quantity = parseServingAmount(amount) ?: return null
    if (unit == "serving") return FoodPortion(food, quantity)
    val base = (listOf(food) + food.servingOptions).mapNotNull { option ->
        servingBasis(option.servingDescription)?.takeIf { it.unit == unit }?.let { option to it.amount }
    }.maxByOrNull { it.second } ?: return null
    val factor = quantity / base.second
    return FoodPortion(base.first, factor).takeIf { factor.isFinite() && factor in 0.0001f..10_000f }
}

/**
 * Fitness cover shows this add food dialog view so the user can view its details and use its actions.
 *
 * @param food Food supplied to [AddFoodDialog].
 * @param onDismiss Callback invoked when the dialog or sheet is dismissed.
 * @param onAdd Callback invoked when add changes or occurs.
 * @param initialServings Initial servings supplied to [AddFoodDialog].
 * @param initialMeal Initial meal supplied to [AddFoodDialog].
 * @param editing Whether to apply editing.
 * @param onEditFood Callback invoked when edit food changes or occurs.
 * @param isSaved Whether is saved.
 * @param onToggleSaved Callback invoked when toggle saved changes or occurs.
 */
@Composable
internal fun AddFoodDialog(
    food: NutritionFood,
    onDismiss: () -> Unit,
    onAdd: (NutritionFood, Float, MealType) -> Unit,
    initialServings: Float = 1f,
    initialMeal: MealType = MealType.BREAKFAST,
    editing: Boolean = false,
    onEditFood: (() -> Unit)? = null,
    isSaved: Boolean = false,
    onToggleSaved: ((Boolean) -> Unit)? = null,
) {
    var servings by remember { mutableStateOf(initialServings.toString()) }
    var choice by remember(food.id) { mutableStateOf(food) }
    var servingMenu by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    var customUnit by remember { mutableStateOf(servingUnits(food).firstOrNull() ?: "serving") }
    val portion = if (custom) customFoodPortion(food, servings, customUnit)
        else parseServingAmount(servings)?.let { FoodPortion(choice, it) }
    var meal by remember { mutableStateOf(initialMeal) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(food.name, modifier = Modifier.weight(1f))
                onToggleSaved?.let { toggle -> FoodBookmark(isSaved) { toggle(!isSaved) } }
                onEditFood?.let { edit -> IconButton(onClick = edit) {
                    com.olive.app.ui.shared.LocalizedIcon(Icons.Outlined.Edit, stringResource(R.string.nutrition_edit_details))
                } }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (food.servingOptions.isNotEmpty() || food.servingDescription.isNotBlank()) {
                    Box {
                        OutlinedButton(onClick = { servingMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (custom) stringResource(R.string.nutrition_custom_portion) else choice.servingDescription)
                        }
                        DropdownMenu(expanded = servingMenu, onDismissRequest = { servingMenu = false }) {
                            food.servingOptions.ifEmpty { listOf(food) }.forEach { option ->
                                DropdownMenuItem(text = { Text(option.servingDescription) }, onClick = {
                                    choice = option
                                    custom = false
                                    servings = "1"
                                    servingMenu = false
                                })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.nutrition_custom_portion)) }, onClick = {
                                custom = true; servings = ""; servingMenu = false
                            })
                        }
                    }
                }
                if (custom) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (servingUnits(food) + "serving").forEach { unit ->
                        FilterChip(selected = customUnit == unit, onClick = { customUnit = unit },
                            label = { Text(if (unit == "serving") stringResource(R.string.servings) else unit) })
                    }
                }
                Text(
                    "${(portion?.food ?: choice).servingDescription} · ${((portion?.food?.calories ?: 0f) * (portion?.servings ?: 0f)).displayAmount()} kcal · " +
                            "P ${((portion?.food?.proteinGrams ?: 0f) * (portion?.servings ?: 0f)).displayAmount()} g · " +
                            "C ${((portion?.food?.carbohydrateGrams ?: 0f) * (portion?.servings ?: 0f)).displayAmount()} g · F ${((portion?.food?.fatGrams ?: 0f) * (portion?.servings ?: 0f)).displayAmount()} g",
                )
                OutlinedTextField(
                    value = servings,
                    onValueChange = { value -> servings = value.take(12) },
                    label = { Text(if (custom) stringResource(R.string.nutrition_amount) + " ($customUnit)" else stringResource(R.string.servings)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                MealType.entries.forEach { option ->
                    FilterChip(
                        selected = meal == option,
                        onClick = { meal = option },
                        label = { Text(option.localizedName()) },
                        leadingIcon = if (meal == option) {
                            { com.olive.app.ui.shared.LocalizedIcon(Icons.Outlined.Check, null) }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { portion?.let { onAdd(it.food, it.servings, meal) } },
                enabled = portion != null,
            ) { Text(stringResource(if (editing) R.string.save else R.string.add_food)) }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

internal enum class FoodLibrarySection(val label: Int) {
    Search(R.string.search),
    Recent(R.string.nutrition_recent_foods),
    Saved(R.string.nutrition_saved_foods),
}

/**
 * Composes the remember saved foods UI element.
 *
 * @param library Optional library supplied to [rememberSavedFoods].
 * @return List of resulting remember saved foods items.
 */
@Composable
internal fun rememberSavedFoods(library: FitnessFoodLibrary?): List<SavedFood> {
    var saved by remember(library) { mutableStateOf(library?.savedFoods().orEmpty()) }
    DisposableEffect(library) {
        val observer = library?.observeSavedFoods { saved = it }
        onDispose { observer?.close() }
    }
    return saved
}

/**
 * Composes the food bookmark UI element.
 *
 * @param saved Whether to apply saved.
 * @param enabled Whether this feature or control is currently enabled.
 * @param onToggle Callback invoked when toggle changes or occurs.
 */
@Composable
internal fun FoodBookmark(saved: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    IconButton(onClick = onToggle, enabled = enabled) {
        com.olive.app.ui.shared.LocalizedIcon(
            if (saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
            stringResource(if (saved) R.string.nutrition_unsave_food else R.string.nutrition_save_food),
        )
    }
}

/**
 * Computes recent food items for this lazy list scope.
 *
 * @param foods Collection of foods values to process.
 * @param searches Collection of searches values to process.
 * @param enabled Whether this feature or control is currently enabled.
 * @param onFood Callback invoked when food changes or occurs.
 * @param onSearch Callback invoked when search changes or occurs.
 */
internal fun LazyListScope.recentFoodItems(
    foods: List<FoodLogEntry>,
    searches: List<RecentFoodSearch>,
    enabled: Boolean,
    onFood: (FoodLogEntry) -> Unit,
    onSearch: (RecentFoodSearch) -> Unit,
) {
    if (searches.isNotEmpty()) item {
        Text(stringResource(R.string.nutrition_recent_searches))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            searches.forEach { search ->
                SuggestionChip(onClick = { onSearch(search) }, enabled = enabled, label = { Text(search.query) })
            }
        }
    }
    if (foods.isEmpty()) item { Text(stringResource(R.string.nutrition_no_recent_foods)) }
    items(foods, key = { it.food.id }) { entry ->
        FoodLibraryRow(entry.food, enabled, onOpen = { onFood(entry) })
    }
}

/**
 * Computes saved food items for this lazy list scope.
 *
 * @param foods Collection of foods values to process.
 * @param repository Repository supplied to [savedFoodItems].
 * @param enabled Whether this feature or control is currently enabled.
 * @param onFood Callback invoked when food changes or occurs.
 * @param onRemove Callback invoked when remove changes or occurs.
 */
internal fun LazyListScope.savedFoodItems(
    foods: List<SavedFood>,
    repository: FoodNutritionDataSource,
    enabled: Boolean,
    onFood: (NutritionFood) -> Unit,
    onRemove: (String) -> Unit,
) {
    if (foods.isEmpty()) item { Text(stringResource(R.string.nutrition_no_saved_foods)) }
    items(foods, key = SavedFood::id) { saved ->
        SavedFoodRow(saved, repository, enabled, onFood, onRemove)
    }
}

/**
 * Renders the saved food row composable.
 *
 * @param saved Saved supplied to [SavedFoodRow].
 * @param repository Repository supplied to [SavedFoodRow].
 * @param enabled Whether this feature or control is currently enabled.
 * @param onFood Callback invoked when food changes or occurs.
 * @param onRemove Callback invoked when remove changes or occurs.
 */
@Composable
private fun SavedFoodRow(
    saved: SavedFood,
    repository: FoodNutritionDataSource,
    enabled: Boolean,
    onFood: (NutritionFood) -> Unit,
    onRemove: (String) -> Unit,
) {
    var retry by remember { mutableIntStateOf(0) }
    val food = saved.food
    if (food != null) {
        FoodLibraryRow(food, enabled, onOpen = { onFood(food) }, onRemove = { onRemove(saved.id) })
        return
    }
    val result by produceState<Result<NutritionFood>?>(null, saved.id, repository, retry) {
        value = null
        value = try {
            Result.success(checkNotNull(repository.foodById(saved.id)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }
    val loaded = result?.getOrNull()
    when {
        loaded != null -> FoodLibraryRow(loaded, enabled, onOpen = { onFood(loaded) }, onRemove = { onRemove(saved.id) })
        result == null -> LoadingSkeleton(SkeletonLayout.FoodResults, Modifier.fillMaxWidth())
        else -> ListItem(
            headlineContent = { Text(stringResource(R.string.nutrition_saved_unavailable)) },
            supportingContent = {
                TextButton(onClick = { retry++ }, enabled = enabled) { Text(stringResource(R.string.retry)) }
            },
            trailingContent = { FoodBookmark(true, enabled) { onRemove(saved.id) } },
        )
    }
}

/**
 * Renders the food library row composable.
 *
 * @param food Food supplied to [FoodLibraryRow].
 * @param enabled Whether this feature or control is currently enabled.
 * @param onOpen Callback invoked when open changes or occurs.
 * @param onRemove Callback invoked when remove changes or occurs.
 */
@Composable
private fun FoodLibraryRow(food: NutritionFood, enabled: Boolean, onOpen: () -> Unit, onRemove: (() -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(food.name) },
        supportingContent = {
            Text(listOf(food.brand, food.servingDescription, "${food.calories.displayAmount()} kcal")
                .filter(String::isNotBlank).joinToString(" · "))
        },
        modifier = Modifier.clickable(enabled = enabled, onClickLabel = stringResource(R.string.add_to_diary), onClick = onOpen),
        trailingContent = onRemove?.let { remove -> { FoodBookmark(true, enabled, remove) } },
    )
}
