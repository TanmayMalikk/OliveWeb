package com.olive.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.max

// UI color: the first alpha controls the faint track and the second controls the darker moving thumb.
private val ScrollTrack = Color(0x2445613C)
private val ScrollThumb = Color(0xB345613C)

// UI sizing: every scrollbar is 4dp wide, sits 3dp from the edge, and keeps at least a 28dp thumb.
// Shared Olive scrollbar for forms and other regular vertically scrolling content.
fun Modifier.oliveScrollbar(state: ScrollState): Modifier = drawWithContent {
    drawContent()
    if (state.maxValue <= 0) return@drawWithContent

    val width = 4.dp.toPx()
    val edge = 3.dp.toPx()
    val trackHeight = size.height - edge * 2
    val viewportShare = size.height / (size.height + state.maxValue)
    val thumbHeight = max(28.dp.toPx(), trackHeight * viewportShare).coerceAtMost(trackHeight)
    val progress = state.value.toFloat() / state.maxValue.toFloat()
    val thumbTop = edge + (trackHeight - thumbHeight) * progress
    val x = size.width - width - edge

    drawRoundRect(ScrollTrack, Offset(x, edge), Size(width, trackHeight), CornerRadius(width))
    drawRoundRect(ScrollThumb, Offset(x, thumbTop), Size(width, thumbHeight), CornerRadius(width))
}

// Lazy lists report visible rows instead of pixel scroll range, so this estimates the thumb from those rows.
fun Modifier.oliveScrollbar(state: LazyListState): Modifier = drawWithContent {
    drawContent()
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo
    if (total <= 0 || visible.isEmpty() || visible.size >= total) return@drawWithContent

    val width = 4.dp.toPx()
    val edge = 3.dp.toPx()
    val trackHeight = size.height - edge * 2
    val visibleShare = (visible.size.toFloat() / total).coerceIn(0.08f, 1f)
    val thumbHeight = max(28.dp.toPx(), trackHeight * visibleShare).coerceAtMost(trackHeight)
    val lastStart = (total - visible.size).coerceAtLeast(1)
    val progress = (state.firstVisibleItemIndex.toFloat() / lastStart).coerceIn(0f, 1f)
    val thumbTop = edge + (trackHeight - thumbHeight) * progress
    val x = size.width - width - edge

    drawRoundRect(ScrollTrack, Offset(x, edge), Size(width, trackHeight), CornerRadius(width))
    drawRoundRect(ScrollThumb, Offset(x, thumbTop), Size(width, thumbHeight), CornerRadius(width))
}

// Notes use a grid, so they get the same indicator with grid-aware scroll progress.
fun Modifier.oliveScrollbar(state: LazyGridState): Modifier = drawWithContent {
    drawContent()
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo
    if (total <= 0 || visible.isEmpty() || visible.size >= total) return@drawWithContent

    val width = 4.dp.toPx()
    val edge = 3.dp.toPx()
    val trackHeight = size.height - edge * 2
    val visibleShare = (visible.size.toFloat() / total).coerceIn(0.08f, 1f)
    val thumbHeight = max(28.dp.toPx(), trackHeight * visibleShare).coerceAtMost(trackHeight)
    val lastStart = (total - visible.size).coerceAtLeast(1)
    val progress = (state.firstVisibleItemIndex.toFloat() / lastStart).coerceIn(0f, 1f)
    val thumbTop = edge + (trackHeight - thumbHeight) * progress
    val x = size.width - width - edge

    drawRoundRect(ScrollTrack, Offset(x, edge), Size(width, trackHeight), CornerRadius(width))
    drawRoundRect(ScrollThumb, Offset(x, thumbTop), Size(width, thumbHeight), CornerRadius(width))
}
