// All safe-app roles: turns a document photo into the PDF stored by the Documents feature.
package com.olive.app.ui.users.shared.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

private const val PORTRAIT_PAGE_WIDTH = 1240
private const val PORTRAIT_PAGE_HEIGHT = 1754
private const val PAGE_MARGIN = 48f

// Creates a real one-page PDF while keeping the camera photo upright and inside the page edges.
internal fun convertDocumentPhotoToPdf(photo: File, pdfFile: File): File {
    val source = decodeUprightDocumentPhoto(photo)
        ?: error("The captured document image could not be read")
    val landscape = source.width > source.height
    val pageWidth = if (landscape) PORTRAIT_PAGE_HEIGHT else PORTRAIT_PAGE_WIDTH
    val pageHeight = if (landscape) PORTRAIT_PAGE_WIDTH else PORTRAIT_PAGE_HEIGHT
    val document = PdfDocument()

    // Draws the document against a white page and scales it without stretching the text.
    try {
        val page = document.startPage(
            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create(),
        )
        page.canvas.drawColor(Color.WHITE)
        val availableWidth = pageWidth - PAGE_MARGIN * 2f
        val availableHeight = pageHeight - PAGE_MARGIN * 2f
        val scale = minOf(availableWidth / source.width, availableHeight / source.height)
        val drawnWidth = source.width * scale
        val drawnHeight = source.height * scale
        val left = (pageWidth - drawnWidth) / 2f
        val top = (pageHeight - drawnHeight) / 2f
        page.canvas.drawBitmap(
            source,
            null,
            RectF(left, top, left + drawnWidth, top + drawnHeight),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        document.finishPage(page)
        FileOutputStream(pdfFile).use(document::writeTo)
        return pdfFile
    } catch (failure: Exception) {
        pdfFile.delete()
        throw failure
    } finally {
        document.close()
        source.recycle()
    }
}

// Reads CameraX's EXIF flag and rotates or flips the pixels before they enter the PDF.
private fun decodeUprightDocumentPhoto(file: File): Bitmap? {
    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
    val orientation = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                setRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
    if (matrix.isIdentity) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
        if (it !== bitmap) bitmap.recycle()
    }
}
