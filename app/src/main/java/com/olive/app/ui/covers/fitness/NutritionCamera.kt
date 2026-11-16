package com.olive.app.ui.covers.fitness

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.olive.app.ocr.PaddleOcrManager
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File

// Full-resolution camera input is private, bounded, and deleted after recognition or cancellation.
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
            finally { file.delete() }
        }
    }
    fun launch() {
        try {
            val directory = File(context.cacheDir, "fitness-scans").apply { mkdirs() }
            directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach(File::delete)
            val file = File.createTempFile("label-", ".jpg", directory)
            pending = file.absolutePath
            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.documents", file))
        } catch (_: Exception) { pending?.let { File(it).delete() }; pending = null; failure() }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launch() else failure()
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launch()
        else permission.launch(Manifest.permission.CAMERA)
    }
}

internal suspend fun readNutritionPhoto(context: android.content.Context, bytes: ByteArray): String {
    val engine = PaddleOcrManager(context.applicationContext)
    try {
        val result = engine.recognizeDocument(bytes)
        check(result.success)
        return result.extractedText
    } finally { withContext(NonCancellable) { engine.release() } }
}

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

internal fun validFoodBarcode(value: String): Boolean {
    if (value.length !in listOf(8, 12, 13) || value.any { !it.isDigit() }) return false
    val sum = value.dropLast(1).reversed().mapIndexed { index, digit -> digit.digitToInt() * if (index % 2 == 0) 3 else 1 }.sum()
    return (10 - sum % 10) % 10 == value.last().digitToInt()
}

internal fun barcodeFromLabelText(text: String): String? {
    val candidates = Regex("""(?<![0-9])(?:[0-9][ \t-]*){8,}(?![0-9])""")
        .findAll(text).map { it.value.filter(Char::isDigit) }.filter(::validFoodBarcode).distinct().toList()
    return candidates.singleOrNull()
}
