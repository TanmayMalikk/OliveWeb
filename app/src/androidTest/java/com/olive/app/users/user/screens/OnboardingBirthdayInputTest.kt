package com.olive.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.olive.app.ui.shared.BirthdayPicker
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OnboardingBirthdayInputTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun jumpToBirthYearAndChooseOnlyValidDays() {
        val birthday = mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                BirthdayPicker(birthday.value, "dob", enabled = true, showErrors = true) {
                    birthday.value = it
                }
            }
        }

        compose.onNodeWithText("Birthday required").assertIsDisplayed()
        choose("open")
        compose.onNodeWithContentDescription("Years 2 0 0 7 through 2 0 1 6").assertExists()
        compose.onNodeWithTag("dob_range_1926").assertDoesNotExist()
        val today = LocalDate.now()
        choose("range_${today.year}")
        compose.onNodeWithTag("dob_year_${today.year + 1}").assertDoesNotExist()
        choose("year_${today.year}")
        compose.onNodeWithTag("dob_month_${today.monthValue + 1}").assertDoesNotExist()
        choose("month_${today.monthValue}")
        compose.onNodeWithTag("dob_day_${today.dayOfMonth + 1}").assertDoesNotExist()
        choose("day_${today.dayOfMonth}")
        compose.runOnIdle { assertEquals(today.toString(), birthday.value) }
        choose("open")
        choose("range_1936")
        (1927..1936).forEach { compose.onNodeWithTag("dob_year_$it").assertExists() }
        compose.onNodeWithTag("dob_year_1926").assertDoesNotExist()
        compose.onNodeWithTag("dob_year_1937").assertDoesNotExist()
        choose("picker_back")
        choose("range_2006")
        (1997..2006).forEach { compose.onNodeWithTag("dob_year_$it").assertExists() }
        compose.onNodeWithTag("dob_year_1996").assertDoesNotExist()
        compose.onNodeWithTag("dob_year_2007").assertDoesNotExist()
        choose("year_2001")
        choose("month_2")
        compose.onNodeWithTag("dob_day_29").assertDoesNotExist()
        choose("picker_back")
        choose("picker_back")
        choose("year_2000")
        choose("month_2")
        choose("day_29")
        compose.runOnIdle { assertEquals("2000-02-29", birthday.value) }
        compose.onNodeWithText("Birthday required").assertDoesNotExist()
    }

    private fun choose(suffix: String) {
        val option = compose.onNodeWithTag("dob_$suffix")
        if (suffix != "open" && suffix != "picker_back") option.performScrollTo()
        option.performClick()
    }
}
