package com.olive.app.ui.shared

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OliveAnimationViewTest {
    @Test
    fun branchAndOliveRenderDuringTransitionAndTheCompletedFrameIsClear() {
        val view=OliveAnimationView(RuntimeEnvironment.getApplication())
        view.layout(0,0,320,640)
        val bitmap=Bitmap.createBitmap(320,640,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        val positions=mutableListOf<Pair<Float,Float>>()
        view.onOlivePositionChanged={x,y -> positions += x to y}
        fun countPainted():Int { val pixels=IntArray(320*640);bitmap.getPixels(pixels,0,320,0,0,320,640);return pixels.count { it ushr 24 != 0 } }
        view.progress=0f;view.draw(canvas)
        assertEquals(0,countPainted())
        listOf(.1f,.3f,.4f,.5f,.6f,.7f,.8f,.9f).forEach { progress ->
            bitmap.eraseColor(0);view.progress=progress;view.draw(canvas)
            if(progress in .1f.. .7f) assertTrue("Transition should paint at $progress",countPainted()>0)
        }
        assertTrue(positions.size>=4)
        assertTrue(positions.all { it.first.isFinite() && it.second.isFinite() })
        assertTrue(positions.last().second>positions.first().second)
        bitmap.eraseColor(0);view.progress=1f;view.draw(canvas)
        assertEquals(0,countPainted())
        bitmap.recycle()
    }
    @Test
    fun progressIsClampedAndTheViewCanRebuildItsPathAfterResizing() {
        val view=OliveAnimationView(RuntimeEnvironment.getApplication())
        view.progress=-2f;assertEquals(0f,view.progress)
        view.progress=2f;assertEquals(1f,view.progress)
        view.layout(0,0,600,300)
        var position:Pair<Float,Float>?=null
        view.onOlivePositionChanged={x,y -> position=x to y}
        view.progress=.5f
        val bitmap=Bitmap.createBitmap(600,300,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        assertNotNull(position)
        assertTrue(position!!.first in 0f..600f)
        assertTrue(position!!.second in 0f..300f)
        bitmap.recycle()
    }
}
