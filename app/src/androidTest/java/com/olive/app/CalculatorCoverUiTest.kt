package com.olive.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.olive.app.ui.CalculatorCover
import com.olive.app.ui.OliveTheme
import com.olive.app.ui.SessionViewModel
import org.junit.Before
import org.junit.Rule
import org.junit.Test

// This catches calculator-only crashes without needing to complete account onboarding first.
class CalculatorCoverUiTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var session:SessionViewModel

    @Before fun showCalculator() {
        session=SessionViewModel(ApplicationProvider.getApplicationContext())
        compose.setContent { OliveTheme { CalculatorCover(session) } }
    }

    @Test fun yEditorCanOpenFocusAndEditWithoutCrashing() {
        compose.onNodeWithTag("graph_editor").performClick()
        compose.onNodeWithTag("graph_equation").assertExists().performClick().performTextReplacement("sin(x)\nx^2 - 4")
        compose.onNodeWithTag("graph_equation").assertTextContains("sin(x)\nx^2 - 4")
        compose.onNodeWithTag("plot_equation").performClick()
        compose.onNodeWithTag("graph_plot").assertExists()
    }

    @Test fun toolInputStartsEmpty() {
        compose.onNodeWithTag("math_tools").performClick()
        compose.onNodeWithTag("advanced_tool_input").assert(hasSetTextAction())
    }

    @Test fun graphTableUsesRangeInputsAndCreatesScrollableRows() {
        compose.onNodeWithTag("graph_editor").performClick()
        compose.onNodeWithTag("graph_table_analysis").performClick()
        compose.onNodeWithTag("table_start_x").performTextReplacement("-2")
        compose.onNodeWithTag("table_end_x").performTextReplacement("2")
        compose.onNodeWithTag("table_step_x").performTextReplacement("1")
        compose.onNodeWithTag("make_graph_table").performClick()
        compose.onNodeWithTag("graph_table_rows").assertExists()
        compose.onNodeWithText("Y1").assertExists()
        compose.onNodeWithText("Y2").assertExists()
    }

    @Test fun scientificOperatorsExpandWithoutHidingTheMainKeypad() {
        compose.onNodeWithTag("calc_key_sin").assertDoesNotExist()
        compose.onNodeWithTag("scientific_toggle").performClick()
        compose.onNodeWithTag("calc_key_sin").assertExists()
        compose.onNodeWithTag("calc_key_7").assertExists()
        compose.onNodeWithTag("calc_key_equals").assertExists()
    }
}
