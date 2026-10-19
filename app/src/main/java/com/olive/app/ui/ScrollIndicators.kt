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
import androidx.compose.ui.graphics.drawscope.DrawScope
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

// Fixed tables use this matching bottom scrollbar for sideways column movement.
fun Modifier.oliveHorizontalScrollbar(state: ScrollState): Modifier = drawWithContent {
    drawContent()
    if(state.maxValue<=0) return@drawWithContent

    val height=4.dp.toPx()
    val edge=3.dp.toPx()
    val trackWidth=size.width-edge*2
    val viewportShare=size.width/(size.width+state.maxValue)
    val thumbWidth=max(28.dp.toPx(),trackWidth*viewportShare).coerceAtMost(trackWidth)
    val progress=state.value.toFloat()/state.maxValue.toFloat()
    val thumbLeft=edge+(trackWidth-thumbWidth)*progress
    val y=size.height-height-edge

    drawRoundRect(ScrollTrack,Offset(edge,y),Size(trackWidth,height),CornerRadius(height))
    drawRoundRect(ScrollThumb,Offset(thumbLeft,y),Size(thumbWidth,height),CornerRadius(height))
}

// Include partial rows so the thumb follows each scroll pixel instead of jumping between item indices.
fun Modifier.oliveScrollbar(state: LazyListState): Modifier = drawWithContent {
    drawContent()
    val info=state.layoutInfo
    val first=info.visibleItemsInfo.firstOrNull() ?: return@drawWithContent
    val last=info.visibleItemsInfo.lastOrNull() ?: return@drawWithContent
    val start=first.index+(info.viewportStartOffset-first.offset).toFloat()
        .div((first.size+info.mainAxisItemSpacing).coerceAtLeast(1)).coerceIn(0f,1f)
    val end=last.index+(info.viewportEndOffset-last.offset).toFloat()
        .div((last.size+info.mainAxisItemSpacing).coerceAtLeast(1)).coerceIn(0f,1f)
    drawLazyScrollbar(start,end,info.totalItemsCount,state.canScrollBackward,state.canScrollForward)
}

// Measure partial grid rows together; full-width headings and incomplete rows retain their actual item counts.
fun Modifier.oliveScrollbar(state: LazyGridState): Modifier = drawWithContent {
    drawContent()
    val info=state.layoutInfo
    val rows=info.visibleItemsInfo.filter { it.row>=0 }.groupBy { it.row }.toSortedMap()
    val first=rows.entries.firstOrNull() ?: return@drawWithContent
    val last=rows.entries.lastOrNull() ?: return@drawWithContent
    val start=first.value.minOf { it.index }+first.value.size*(info.viewportStartOffset-first.value.minOf { it.offset.y }).toFloat()
        .div((first.value.maxOf { it.size.height }+info.mainAxisItemSpacing).coerceAtLeast(1)).coerceIn(0f,1f)
    val end=last.value.minOf { it.index }+last.value.size*(info.viewportEndOffset-last.value.minOf { it.offset.y }).toFloat()
        .div((last.value.maxOf { it.size.height }+info.mainAxisItemSpacing).coerceAtLeast(1)).coerceIn(0f,1f)
    drawLazyScrollbar(start,end,info.totalItemsCount,state.canScrollBackward,state.canScrollForward)
}

private fun DrawScope.drawLazyScrollbar(start:Float,end:Float,total:Int,canBack:Boolean,canForward:Boolean) {
    if(total<=0 || (!canBack && !canForward)) return
    val width=4.dp.toPx()
    val edge=3.dp.toPx()
    val trackHeight=size.height-edge*2
    if(trackHeight<=0f) return
    val visible=(end-start).coerceIn(0f,total.toFloat())
    val thumbHeight=max(28.dp.toPx(),trackHeight*visible/total).coerceAtMost(trackHeight)
    val range=(total-visible).coerceAtLeast(.001f)
    val progress=when { !canBack -> 0f; !canForward -> 1f; else -> (start/range).coerceIn(0f,1f) }
    val x=size.width-width-edge
    drawRoundRect(ScrollTrack,Offset(x,edge),Size(width,trackHeight),CornerRadius(width))
    drawRoundRect(ScrollThumb,Offset(x,edge+(trackHeight-thumbHeight)*progress),Size(width,thumbHeight),CornerRadius(width))
}
