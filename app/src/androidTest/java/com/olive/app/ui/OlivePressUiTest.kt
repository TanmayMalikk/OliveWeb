package com.olive.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.olive.app.ui.shared.olivePress
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OlivePressUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun quickTapInScrollingContentShowsPressWithoutDelayingClick() {
        var clicks = 0
        compose.setContent {
            Column(Modifier.height(150.dp).verticalScroll(rememberScrollState())) {
                val source = remember { MutableInteractionSource() }
                Box(
                    Modifier.size(160.dp, 56.dp).background(Color.White).testTag("press_target")
                        .olivePress(source)
                        .clickable(interactionSource = source, indication = null) { clicks++ }
                        .background(Color.Black),
                )
                Spacer(Modifier.height(300.dp))
            }
        }
        val button = compose.onNodeWithTag("press_target")
        fun edgeColor(): Color {
            val pixels = button.captureToImage().toPixelMap()
            return pixels[1, pixels.height / 2]
        }

        assertEquals(Color.Black, edgeColor())
        compose.mainClock.autoAdvance = false
        try {
            button.performTouchInput {
                down(center)
                advanceEventTime(16)
                up()
            }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { assertEquals(1, clicks) }
            assertEquals(Color.White, edgeColor())
            compose.mainClock.advanceTimeBy(240)
            assertEquals(Color.Black, edgeColor())
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
