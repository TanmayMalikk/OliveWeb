package com.olive.app.support

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(PdfDocument::class)
class TestPdfDocumentShadow {
    private val pages = mutableListOf<PdfDocument.Page>()

    @Implementation
    protected fun __constructor__() {
    }

    @Implementation
    protected fun startPage(pageInfo: PdfDocument.PageInfo?): PdfDocument.Page {
        val info = pageInfo ?: PdfDocument.PageInfo.Builder(595, 842, pages.size + 1).create()
        val width = info.pageWidth.coerceAtLeast(1)
        val height = info.pageHeight.coerceAtLeast(1)
        val canvas = Canvas(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))
        val ctor = PdfDocument.Page::class.java.declaredConstructors.minByOrNull { it.parameterCount }
            ?: error("PdfDocument.Page constructor not found")
        ctor.isAccessible = true
        val args = ctor.parameterTypes.map { param ->
            when {
                Canvas::class.java.isAssignableFrom(param) -> canvas
                PdfDocument.PageInfo::class.java.isAssignableFrom(param) -> info
                else -> null
            }
        }.toTypedArray()
        val page = ctor.newInstance(*args) as PdfDocument.Page
        PdfDocument.Page::class.java.declaredFields.forEach { field ->
            field.isAccessible = true
            when {
                Canvas::class.java.isAssignableFrom(field.type) && field.get(page) == null -> field.set(page, canvas)
                PdfDocument.PageInfo::class.java.isAssignableFrom(field.type) && field.get(page) == null -> field.set(page, info)
            }
        }
        pages += page
        return page
    }

    @Implementation
    protected fun finishPage(page: PdfDocument.Page?) {
    }

    @Implementation
    protected fun writeTo(out: OutputStream) {
        out.write("%PDF-1.4\n%%EOF\n".toByteArray())
        out.flush()
    }

    @Implementation
    protected fun close() {
    }
}
