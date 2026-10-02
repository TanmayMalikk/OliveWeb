package com.olive.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

// Lightweight food illustrations keep the sample menu looking polished without random web photos.
// UI sizing/color: this fallback art scales from the supplied canvas size; edit the palette below to change food illustration colors.
@Composable fun FoodArt(kind: String, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val c = center
        val bg = when(kind) { "pizza" -> Color(0xFFEAD9C4); "burger" -> Color(0xFFF0DFC4); "sushi" -> Color(0xFFD9E4DF); else -> Color(0xFFDEE5D0) }
        drawRect(bg)
        drawCircle(Color.White.copy(alpha = .25f), s * .65f, Offset(size.width * .1f, 0f))
        drawCircle(Color(0xFF253322).copy(alpha = .10f), s * .42f, c + Offset(4f, 9f))
        drawCircle(Color(0xFFFFFDF4), s * .42f, c)
        drawCircle(Color(0xFFE6E3D6), s * .36f, c, style = Stroke(s * .007f))
        when(kind) {
            "pizza" -> {
                drawCircle(Color(0xFFDCA252), s * .33f, c)
                drawCircle(Color(0xFFB95035), s * .285f, c)
                for (i in 0..7) {
                    val a = i * .91f
                    val p = c + Offset(cos(a) * s * .18f, sin(a) * s * .18f)
                    drawCircle(Color(0xFFFFE7AA), s * .07f, p)
                    drawOval(Color(0xFF547640), p + Offset(s * .02f, 0f), Size(s * .09f, s * .035f))
                }
            }
            "burger" -> {
                val left = c.x - s * .28f
                fun layer(y: Float, h: Float, color: Color) = drawRoundRect(color, Offset(left, c.y + s*y), Size(s*.56f,s*h), androidx.compose.ui.geometry.CornerRadius(s*.06f))
                layer(.13f, .12f, Color(0xFFDFA555))
                layer(.06f, .10f, Color(0xFF6D4029))
                layer(.01f, .065f, Color(0xFFF3C653))
                layer(-.055f, .075f, Color(0xFFB95D40))
                layer(-.105f, .065f, Color(0xFF6D9045))
                drawArc(Color(0xFFE7B363), 180f, 180f, true, Offset(left, c.y-s*.34f), Size(s*.56f,s*.48f))
                for(i in 0..7) drawOval(Color(0xFFFFE8BD), Offset(left+s*(.09f+i*.05f),c.y-s*(.16f+(i%3)*.035f)), Size(s*.025f,s*.012f))
            }
            "sushi" -> {
                for(row in 0..1) for(col in 0..2) {
                    val p = c + Offset((col-1)*s*.20f,(row-.5f)*s*.23f)
                    drawCircle(Color(0xFF344D3D),s*.093f,p)
                    drawCircle(Color(0xFFF3F0DE),s*.077f,p)
                    drawCircle(Color(0xFFDD9474),s*.045f,p)
                    drawCircle(Color(0xFF86A65F),s*.025f,p+Offset(s*.025f,0f))
                }
            }
            else -> {
                drawCircle(Color(0xFF8EA664),s*.32f,c)
                for(i in 0..29) {
                    val a = i * 2.399f
                    val r = s * (.04f + (i%6)*.045f)
                    val p = c + Offset(cos(a)*r,sin(a)*r)
                    drawCircle(listOf(Color(0xFF527547),Color(0xFFC9D88C),Color(0xFFE4C893),Color(0xFFB96546))[i%4],s*.043f,p)
                }
                for(i in 0..4) drawArc(Color(0xFFD6DFA0),110f,140f,false,c-Offset(s*.20f-i*s*.023f,s*.19f),Size(s*.27f,s*.38f), style=Stroke(s*.026f))
            }
        }
    }
}
