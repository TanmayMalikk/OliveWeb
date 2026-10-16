package com.olive.app.ui.covers.calculator
import com.olive.app.ui.*

import android.graphics.Paint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.compose.ui.graphics.drawscope.DrawScope

internal fun DrawScope.drawGraphPlot(graphs:List<GraphExpression>,zoom:Float,panOffset:Offset) {
    // The keyboard can briefly collapse this area; wait for usable space instead of drawing into invalid bounds.
    if(size.width<48.dp.toPx() || size.height<48.dp.toPx()) return
    val unit=32f*zoom
    val centre=Offset(size.width/2f+panOffset.x,size.height/2f+panOffset.y)
    drawGraphGrid(unit,centre,zoom)
    drawGraphCurves(graphs,unit,centre)
}

private fun DrawScope.drawGraphGrid(unit:Float,centre:Offset,zoom:Float) {
    val gridColor=SoftGray
    // Grid labels step from 5 to 10 to 25 as the user zooms out, keeping markings readable.
    val majorStep=when {
        zoom>=0.85f -> 5f
        zoom>=0.4f -> 10f
        else -> 25f
    }
    val minorPixels=unit*(majorStep/5f)
    val majorPixels=unit*majorStep
    var x=((centre.x%minorPixels)+minorPixels)%minorPixels
    while(x<=size.width) { drawLine(gridColor,Offset(x,0f),Offset(x,size.height),1f);x+=minorPixels }
    var y=((centre.y%minorPixels)+minorPixels)%minorPixels
    while(y<=size.height) { drawLine(gridColor,Offset(0f,y),Offset(size.width,y),1f);y+=minorPixels }

    val labelPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=android.graphics.Color.rgb(69,97,60)
        textSize=11.sp.toPx()
        textAlign=Paint.Align.CENTER
    }
    val firstX=kotlin.math.floor((0f-centre.x)/majorPixels).toInt()
    val lastX=kotlin.math.ceil((size.width-centre.x)/majorPixels).toInt()
    val xLabelY=(centre.y+16.dp.toPx()).coerceIn(14.dp.toPx(),size.height-4.dp.toPx())
    for(index in firstX..lastX) {
        val lineX=centre.x+index*majorPixels
        drawLine(OliveGreen.copy(alpha=.28f),Offset(lineX,0f),Offset(lineX,size.height),1.5f)
        drawIntoCanvas { it.nativeCanvas.drawText((index*majorStep).toInt().toString(),lineX,xLabelY,labelPaint) }
    }
    val firstY=kotlin.math.ceil((centre.y-size.height)/majorPixels).toInt()
    val lastY=kotlin.math.floor(centre.y/majorPixels).toInt()
    labelPaint.textAlign=Paint.Align.LEFT
    val yLabelX=(centre.x+6.dp.toPx()).coerceIn(4.dp.toPx(),size.width-34.dp.toPx())
    for(index in firstY..lastY) {
        val lineY=centre.y-index*majorPixels
        drawLine(OliveGreen.copy(alpha=.28f),Offset(0f,lineY),Offset(size.width,lineY),1.5f)
        if(index!=0) drawIntoCanvas {
            it.nativeCanvas.drawText((index*majorStep).toInt().toString(),yLabelX,lineY-3.dp.toPx(),labelPaint)
        }
    }
    drawLine(Muted,Offset(0f,centre.y),Offset(size.width,centre.y),2f)
    drawLine(Muted,Offset(centre.x,0f),Offset(centre.x,size.height),2f)
}

private fun DrawScope.drawGraphCurves(graphs:List<GraphExpression>,unit:Float,centre:Offset) {
    val plotColors=listOf(OliveGreen,DarkOlive,MossGreen,OliveBrown,Muted,Ink)
    graphs.forEachIndexed { graphIndex,graph ->
        var previous: Offset?=null
        var pixel=0f
        while(pixel<=size.width) {
            val input=(pixel-centre.x)/unit
            val output=runCatching { graph.evaluate(input.toDouble()) }.getOrDefault(Double.NaN)
            val point=Offset(pixel,centre.y-output.toFloat()*unit)
            val visible=point.x.isFinite() && point.y.isFinite() && point.x in (-size.width*3f)..(size.width*4f) && point.y in (-size.height*3f)..(size.height*4f)
            val prior=previous
            if(visible && prior!=null && kotlin.math.abs(point.y-prior.y)<size.height*1.5f)
                drawLine(plotColors[graphIndex%plotColors.size],prior,point,3.5f,cap=StrokeCap.Round)
            previous=if(visible) point else null
            pixel+=2f
        }
    }
}
