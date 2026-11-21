package com.olive.app.ui.shared

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/** Rounds card corners with a smooth join between the curve and each straight edge. */
internal fun oliveCorners(radius: Dp): CornerBasedShape {
    val corner = CornerSize(radius)
    return OliveCorners(corner, corner, corner, corner)
}

internal val OlivePill: CornerBasedShape = CornerSize(50).let { OliveCorners(it, it, it, it) }

private class OliveCorners(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {
    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): CornerBasedShape = OliveCorners(topStart, topEnd, bottomEnd, bottomStart)

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        if (topStart + topEnd + bottomEnd + bottomStart == 0f) {
            return Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
        }
        val limit = minOf(size.width, size.height) / 2f
        val ltr = layoutDirection == LayoutDirection.Ltr
        val tl = (if (ltr) topStart else topEnd).coerceAtMost(limit)
        val tr = (if (ltr) topEnd else topStart).coerceAtMost(limit)
        val br = (if (ltr) bottomEnd else bottomStart).coerceAtMost(limit)
        val bl = (if (ltr) bottomStart else bottomEnd).coerceAtMost(limit)
        val w = size.width
        val h = size.height
        return Outline.Generic(Path().apply {
            moveTo(tl, 0f)
            lineTo(w - tr, 0f)
            cubicTo(w - .64f * tr, 0f, w - .32f * tr, 0f, w - .16f * tr, .16f * tr)
            cubicTo(w, .32f * tr, w, .64f * tr, w, tr)
            lineTo(w, h - br)
            cubicTo(w, h - .64f * br, w, h - .32f * br, w - .16f * br, h - .16f * br)
            cubicTo(w - .32f * br, h, w - .64f * br, h, w - br, h)
            lineTo(bl, h)
            cubicTo(.64f * bl, h, .32f * bl, h, .16f * bl, h - .16f * bl)
            cubicTo(0f, h - .32f * bl, 0f, h - .64f * bl, 0f, h - bl)
            lineTo(0f, tl)
            cubicTo(0f, .64f * tl, 0f, .32f * tl, .16f * tl, .16f * tl)
            cubicTo(.32f * tl, 0f, .64f * tl, 0f, tl, 0f)
            close()
        })
    }
}
