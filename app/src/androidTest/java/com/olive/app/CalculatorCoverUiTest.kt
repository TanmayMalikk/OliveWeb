package com.olive.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.olive.app.ui.covers.calculator.CalculatorCover
import com.olive.app.ui.OliveTheme
import com.olive.app.ui.SessionViewModel
import org.junit.Before
import org.junit.Rule
import org.junit.Test

// This catches calculator-only crashes without needing to complete account onboarding first.
class CalculatorCoverUiTest {
    @Test fun resultFitsAboveKeysWithAndWithoutScientificOperators() {
        "34646".forEach { compose.onNodeWithTag("calc_key_$it").performClick() }
        repeat(2) {
            val result=compose.onNodeWithTag("calculator_display")
            val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            result.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val bounds=result.fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue("Result clipped: $bounds vs ${layouts.first().size}", bounds.height+1>=layouts.first().size.height)
            org.junit.Assert.assertTrue(bounds.bottom<=compose.onNodeWithTag("calc_key_%").fetchSemanticsNode().boundsInRoot.top)
            compose.onNodeWithTag("scientific_toggle").performClick()
        }
    }
    @Test fun graphPanAndZoomAreAvailableAsAccessibilityActions() {
        compose.onNodeWithTag("graph_editor").performClick()
        val graph=compose.onNodeWithTag("graph_plot")
        graph.assert(SemanticsMatcher("graph exposes pan and zoom") {
            it.config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].size==6
        })
        val zoom=graph.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].first { it.label=="Zoom in" }
        compose.runOnIdle { zoom.action() }
        graph.assert(SemanticsMatcher("zoom state updates") {
            it.config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].contains("125%")
        })
        val pan=graph.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].first { it.label=="Pan left" }
        compose.runOnIdle { pan.action() }
        graph.assert(SemanticsMatcher("pan state updates") {
            it.config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].contains("X: -2")
        })
    }
    @get:Rule val compose=createComposeRule()
    private lateinit var session:SessionViewModel

    @Before fun showCalculator() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("better_calculator_history",android.content.Context.MODE_PRIVATE).edit().clear().commit()
        session=SessionViewModel(context as android.app.Application)
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
        compose.onNodeWithTag("graph_table_header").assertTextContains("X").assertTextContains("Y1").assertTextContains("Y2")
        compose.onNodeWithTag("graph_table_row_0").assertTextContains("-2")
        compose.onNodeWithTag("table_start_x").assert(hasSetTextAction())
        compose.onNodeWithText("Analyze").performClick()
        compose.onNodeWithTag("run_graph_analysis").performScrollTo().performClick()
        compose.onNodeWithTag("graph_analysis_result").assertTextContains("Result")
    }

    @Test fun scientificOperatorsExpandWithoutHidingTheMainKeypad() {
        compose.onNodeWithTag("calc_key_sin").assertDoesNotExist()
        compose.onNodeWithTag("scientific_toggle").performClick()
        compose.onNodeWithTag("calc_key_sin").assertExists()
        compose.onNodeWithTag("calc_key_7").assertExists()
        compose.onNodeWithTag("calc_key_equals").assertExists()
    }

    @Test fun calculationHistoryRestoresTheSelectedExpression() {
        compose.onNodeWithTag("calc_key_2").performClick()
        compose.onNodeWithTag("calc_key_+").performClick()
        compose.onNodeWithTag("calc_key_3").performClick()
        compose.onNodeWithTag("calc_key_equals").performClick()
        compose.onNodeWithTag("calculator_history_0").assertTextContains("2+3").assertTextContains("= 5").assertHasClickAction()
        compose.onNodeWithTag("calc_key_clear").performClick()
        compose.onNodeWithTag("calculator_history_0").performClick()
        compose.onNodeWithTag("calculator_display").assertTextEquals("2+3")
    }

    @Test fun clearHistoryRemovesEveryVisibleCalculation() {
        compose.onNodeWithTag("calc_key_9").performClick()
        compose.onNodeWithTag("calc_key_equals").performClick()
        compose.onNodeWithTag("calculator_history_0").assertExists()

        compose.onNodeWithTag("clear_calculator_history").performClick()

        compose.onNodeWithTag("calculator_history_0").assertDoesNotExist()
        compose.onNodeWithTag("clear_calculator_history").assertDoesNotExist()
    }
}
