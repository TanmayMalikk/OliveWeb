package com.olive.app.ui.users.user.notes

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.olive.app.support.OliveUiTestHost
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class IncidentPickersAndTrashDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun incidentDateTimeFieldsCoverDateAndTimePickers() {
        var date by mutableStateOf("not-a-date")
        var time by mutableStateOf("09:30")

        compose.setContent {
            OliveUiTestHost {
                Column {
                    IncidentDateTimeFields(
                        date = date,
                        time = time,
                        enabled = true,
                        onDateChange = { date = it },
                        onTimeChange = { time = it },
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("incident_date").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("incident_date_apply").performClick()
        compose.waitForIdle()
        assertTrue(date.isNotBlank() && date != "not-a-date")

        compose.onNodeWithTag("incident_time").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("incident_time_apply").performClick()
        compose.waitForIdle()
    }

    @Test
    fun emptyTrashComposesAndClosesCleanly() {
        compose.mainClock.autoAdvance = false
        var closed = false
        compose.setContent {
            OliveUiTestHost {
                Trash(
                    entries = emptyList(),
                    formatter = DateTimeFormatter.ISO_INSTANT,
                    restore = {},
                    close = { closed = true },
                )
            }
        }
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithTag("close_scratchpad").performClick()
        compose.mainClock.advanceTimeBy(150)
        assertTrue(closed)
    }
}
