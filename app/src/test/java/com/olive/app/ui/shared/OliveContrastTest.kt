package com.olive.app.ui.shared

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
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
    }

    @Test
    fun featureTilesAndControlsOnMediaRemainLegible() {
        assertContrast(Ink, PaleOlive, 7f)
        assertContrast(Cream, DarkSageSurface, 7f)
        assertContrast(DarkOlive, SageGreen, 6f)
        assertContrast(Paper, CameraControlScrim.compositeOver(Paper), 7f)
        assertContrast(Paper, CallRed, 4.5f)
    }

    private fun assertContrast(foreground: Color, background: Color, minimum: Float) {
        val foregroundLuminance = foreground.luminance()
        val backgroundLuminance = background.luminance()
        val ratio = (maxOf(foregroundLuminance, backgroundLuminance) + .05f) /
            (minOf(foregroundLuminance, backgroundLuminance) + .05f)
        assertTrue("Contrast for $foreground on $background was $ratio, expected at least $minimum", ratio >= minimum)
    }
}
