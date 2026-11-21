package com.olive.app.ui.covers.fitness

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak

internal val TextStyle.tabularDigits: TextStyle
    get() = copy(fontFeatureSettings = "tnum")

internal val TextStyle.balancedHeadline: TextStyle
    get() = copy(lineBreak = LineBreak.Heading)

// Two cubic segments per corner keep curvature flat where each corner joins a straight edge.
internal val FitnessContinuousCorners = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    val r = minOf(w, h) * 0.19f
    val middle = r * 0.16f
    val near = r * 0.32f
    val far = r * 0.64f
    moveTo(r, 0f)
    lineTo(w - r, 0f)
    cubicTo(w - far, 0f, w - near, 0f, w - middle, middle)
    cubicTo(w, near, w, far, w, r)
    lineTo(w, h - r)
    cubicTo(w, h - far, w, h - near, w - middle, h - middle)
    cubicTo(w - near, h, w - far, h, w - r, h)
    lineTo(r, h)
    cubicTo(far, h, near, h, middle, h - middle)
    cubicTo(0f, h - near, 0f, h - far, 0f, h - r)
    lineTo(0f, r)
    cubicTo(0f, far, 0f, near, middle, middle)
    cubicTo(near, 0f, far, 0f, r, 0f)
    close()
}
