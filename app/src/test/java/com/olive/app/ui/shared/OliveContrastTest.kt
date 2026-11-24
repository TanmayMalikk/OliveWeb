package com.olive.app.ui.shared

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

class OliveContrastTest {
    @Test
    fun smallTextPairsKeepExtraContrast() {
        assertContrast(Muted, Cream, 7f)
        assertContrast(Muted, NotePaperCream, 7f)
        assertContrast(Muted, PaleOlive, 7f)
        assertContrast(Muted, IncidentPaper, 7f)
        assertContrast(IncidentAccent, IncidentPaper, 7f)
        assertContrast(Muted, ChatIncomingBubble, 7f)
        assertContrast(OliveGreen, PaleOlive, 7f)
        assertContrast(Paper, ChatSentBubble, 7f)
        assertContrast(Paper, FitnessHeroEnd, 7f)
    }

    @Test
    fun controlOutlinesAndErrorsRemainDistinct() {
        assertContrast(OliveOutline, Cream, 3f)
        assertContrast(OliveOutline, PaleOlive, 3f)
        assertContrast(OliveDivider, PaleOlive, 3f)
        assertContrast(OliveDarkDivider, CallBackground, 3f)
        assertContrast(OliveError, OliveErrorContainer, 4.5f)
        assertContrast(OliveErrorContainer, ChatSentBubble, 7f)
    }

    @Test
    fun featureTilesAndControlsOnMediaRemainLegible() {
        assertContrast(Ink, PaleOlive, 7f)
        assertContrast(Ink, SageGreen, 7f)
        assertContrast(Cream, DarkSageSurface, 7f)
        assertContrast(DarkOlive, SageGreen, 6f)
        assertContrast(Paper, CameraControlScrim.compositeOver(Paper), 7f)
        assertContrast(Paper, CallRed, 4.5f)
        assertContrast(Paper, CallGreen, 3f)
        assertContrast(CallGreen, CallBackground, 3f)
        assertContrast(FatPlumLight, FitnessDarkCard, 3f)
    }

    @Test
    fun smallTextMeetsApcaReadabilityThreshold() {
        assertApca(Muted, Cream, 75.0)
        assertApca(Muted, NotePaperCream, 75.0)
        assertApca(Muted, PaleOlive, 75.0)
        assertApca(Muted, IncidentPaper, 75.0)
        assertApca(Muted, ChatIncomingBubble, 75.0)
        assertApca(Muted, WarmWhite, 75.0)
        assertApca(IncidentAccent, IncidentPaper, 75.0)
        assertApca(OliveGreen, PaleOlive, 75.0)
        assertApca(OliveGreen, Paper, 75.0)
        assertApca(Paper, ChatSentBubble, 75.0)
        assertApca(OliveErrorContainer, ChatSentBubble, 75.0)
        assertApca(OliveError, OliveErrorContainer, 75.0)
        assertApca(OliveError, Paper, 75.0)
        assertApca(OliveError, Cream, 75.0)
        assertApca(Ink, SageGreen, 75.0)
        assertApca(Ink, PaleOlive, 75.0)
        assertApca(Ink, WarmWhite, 75.0)
        assertApca(Paper, FitnessHeroEnd, 75.0)
        assertApca(Paper, CallRed, 75.0)
    }

    private fun assertContrast(foreground: Color, background: Color, minimum: Float) {
        val foregroundLuminance = foreground.luminance()
        val backgroundLuminance = background.luminance()
        val ratio = (maxOf(foregroundLuminance, backgroundLuminance) + .05f) /
            (minOf(foregroundLuminance, backgroundLuminance) + .05f)
        assertTrue("Contrast for $foreground on $background was $ratio, expected at least $minimum", ratio >= minimum)
    }

    private fun assertApca(foreground: Color, background: Color, minimumAbsoluteLc: Double) {
        val lc = abs(apcaLc(foreground, background))
        assertTrue(
            "APCA |Lc| for $foreground on $background was $lc, expected at least $minimumAbsoluteLc",
            lc >= minimumAbsoluteLc,
        )
    }

    /** APCA W3 0.0.98G-4g contrast computation for sRGB colors. */
    private fun apcaLc(foreground: Color, background: Color): Double {
        fun srgbToY(color: Color): Double {
            val r = color.red.toDouble().pow(2.4)
            val g = color.green.toDouble().pow(2.4)
            val b = color.blue.toDouble().pow(2.4)
            return 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        }
        val blkThrs = 0.022
        val blkClmp = 1.414
        val resolvedFg = if (foreground.alpha < 1f) foreground.compositeOver(background) else foreground
        var txtY = srgbToY(resolvedFg)
        var bgY = srgbToY(background)
        if (txtY <= blkThrs) txtY += (blkThrs - txtY).pow(blkClmp)
        if (bgY <= blkThrs) bgY += (blkThrs - bgY).pow(blkClmp)
        if (abs(bgY - txtY) < 0.0005) return 0.0
        return if (bgY > txtY) {
            val sapc = (bgY.pow(0.56) - txtY.pow(0.57)) * 1.14
            if (sapc < 0.1) 0.0 else (sapc - 0.027) * 100.0
        } else {
            val sapc = (bgY.pow(0.65) - txtY.pow(0.62)) * 1.14
            if (sapc > -0.1) 0.0 else (sapc + 0.027) * 100.0
        }
    }
}
