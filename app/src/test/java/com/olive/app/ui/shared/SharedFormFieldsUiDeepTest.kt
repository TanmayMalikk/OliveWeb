package com.olive.app.ui.shared

import android.app.Application
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.support.OliveUiTestHost
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class SharedFormFieldsUiDeepTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun addressFieldsScrollbarsAndAccessibilityHelpersExerciseAllBranches() {
        compose.mainClock.autoAdvance = false
        var address by mutableStateOf(DeliveryAddress())
        var focusedFields = mutableListOf<String>()
        var cityError by mutableStateOf<String?>("City is required")
        var shakeTrigger by mutableIntStateOf(0)

        compose.setContent {
            OliveUiTestHost(privateDesign = false) {
                val colScroll = rememberScrollState()
                val listState = rememberLazyListState()
                val gridState = rememberLazyGridState()
                val entryFocus = rememberEntryFocus("form-key", ready = true)
                LaunchedEffect(Unit) {
                    colScroll.scrollTo(50)
                    listState.scrollToItem(3)
                    gridState.scrollToItem(4)
                }
                Column(Modifier.fillMaxWidth()) {
                    AddressFields(
                        value = address,
                        cityOnlyRequired = false,
                        cityError = cityError,
                        shakeTrigger = shakeTrigger,
                        onFocus = { focusedFields.add(it) },
                        change = { address = it },
                    )
                    LocalizedIcon(
                        imageVector = Icons.Outlined.Home,
                        contentDescription = "Home icon",
                        modifier = Modifier.focusRequester(entryFocus),
                    )
                    Box(Modifier.size(160.dp, 80.dp)) {
                        Column(Modifier.verticalScroll(colScroll).oliveScrollbar(colScroll)) {
                            repeat(20) { idx -> Text("Row $idx", Modifier.height(24.dp)) }
                        }
                    }
                    Box(Modifier.size(160.dp, 80.dp)) {
                        LazyColumn(state = listState, modifier = Modifier.oliveScrollbar(listState)) {
                            items(20) { idx -> Text("List $idx", Modifier.height(24.dp)) }
                        }
                    }
                    Box(Modifier.size(160.dp, 80.dp)) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            state = gridState,
                            modifier = Modifier.oliveScrollbar(gridState),
                        ) {
                            items(20) { idx -> Text("Grid $idx", Modifier.height(24.dp)) }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(300)

        compose.onNodeWithTag("address_line1").performClick()
        compose.onNodeWithTag("address_line1").performTextInput("101 Main St")
        compose.onNodeWithTag("address_line2").performClick()
        compose.onNodeWithTag("address_line2").performTextInput("Unit 4")
        compose.onNodeWithTag("address_city").performClick()
        compose.onNodeWithTag("address_city").performTextInput("Edmonton")
        compose.onNodeWithTag("address_province").performClick()
        compose.onNodeWithTag("address_province").performTextInput("AB")
        compose.onNodeWithTag("address_postal").performClick()
        compose.onNodeWithTag("address_postal").performTextInput("T5J 1N8")
        compose.onNodeWithTag("address_country").performClick()
        compose.onNodeWithTag("address_country").performTextInput(" Canada")
        compose.mainClock.advanceTimeBy(250)

        cityError = null
        shakeTrigger += 1
        compose.mainClock.advanceTimeBy(150)

        assertTrue(address.line1.contains("101 Main St"))
        assertTrue(address.city.contains("Edmonton"))
        assertTrue(focusedFields.isNotEmpty())
    }

    @Test
    fun accessibilityInteractionFiltersAndDelegateTriggerOnUserActions() {
        assertTrue(isAccessibilityUserInteraction(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED))
        assertTrue(isAccessibilityUserInteraction(AccessibilityEvent.TYPE_VIEW_CLICKED))
        assertTrue(isAccessibilityUserInteraction(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED))
        assertTrue(isAccessibilityUserInteraction(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED))
        assertTrue(isAccessibilityUserInteraction(AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY))
        assertFalse(isAccessibilityUserInteraction(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))

        val context = RuntimeEnvironment.getApplication()
        val host = FrameLayout(context)
        val child = FrameLayout(context).also { host.addView(it) }
        var interactions = 0
        observeAccessibilityInteractions(host) { interactions++ }
        val delegate = ViewCompat.getAccessibilityDelegate(host)!!
        @Suppress("DEPRECATION")
        delegate.onRequestSendAccessibilityEvent(
            host,
            child,
            AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED),
        )
        @Suppress("DEPRECATION")
        delegate.onRequestSendAccessibilityEvent(
            host,
            child,
            AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED),
        )
        assertTrue(interactions == 1)
    }
}
