package com.olive.app.ui.shared

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.support.OliveUiTestHost
import com.olive.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SharedUiComponentsUnitTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun oliveButtonsCardsUndoAndFooterRenderAndClick() {
        var clicks by mutableIntStateOf(0)
        var exitClicks by mutableIntStateOf(0)
        var sheetOpen by mutableStateOf(true)
        var dialogOpen by mutableStateOf(true)
        val targets = WalkthroughTargetRegistry()
        val directive = WalkthroughDirective(stepId = "s1", targetId = "undo_target")

        compose.setContent {
            OliveUiTestHost(
                walkthroughTargets = targets,
                walkthroughDirective = directive,
            ) {
                val undo = LocalOliveUndo.current
                val text = rememberResourceText()
                assertTrue(AccountRole.USER.localizedTitle(text).isNotBlank())
                assertTrue(AccountRole.USER.localizedDescription(text).isNotBlank())
                assertTrue(CoverType.FITNESS.localizedTitle(text).isNotBlank())
                assertTrue(text.failure(IllegalStateException("boom"), R.string.app_name).isNotBlank())

                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OliveButton(onClick = { clicks++ }) { Text("Primary") }
                    OliveFilledTonalButton(onClick = { clicks++ }) { Text("Tonal") }
                    OliveOutlinedButton(onClick = { clicks++ }) { Text("Outlined") }
                    OliveTextButton(onClick = { clicks++ }) { Text("Text") }
                    OliveIconButton(onClick = { clicks++ }) { LocalizedIcon(Icons.Outlined.Add, "IconBtn") }
                    OliveFilledIconButton(onClick = { clicks++ }) { LocalizedIcon(Icons.Outlined.Add, "FilledIconBtn") }
                    OliveFilledTonalIconButton(onClick = { clicks++ }) { LocalizedIcon(Icons.Outlined.Add, "TonalIconBtn") }
                    OliveOutlinedIconButton(onClick = { clicks++ }) { LocalizedIcon(Icons.Outlined.Add, "OutlinedIconBtn") }
                    OliveFloatingActionButton(onClick = { clicks++ }) { Text("Fab") }
                    OliveSmallFloatingActionButton(onClick = { clicks++ }) { Text("SmallFab") }
                    OliveCard { Text("StaticCard") }
                    OliveCard(onClick = { clicks++ }) { Text("ClickableCard") }
                    OliveOutlinedCard(shape = oliveCorners(12.dp)) { Text("StaticOutlined") }
                    OliveOutlinedCard(onClick = { clicks++ }) { Text("ClickableOutlined") }
                    OliveActionSurface(
                        onClick = { undo("Deleted item") { clicks++ } },
                        modifier = Modifier.walkthroughTarget("undo_target"),
                    ) { Text("ActionTile") }
                    FloatingQuickExit(onClick = { exitClicks++ }, compact = false)
                    FloatingQuickExit(onClick = { exitClicks++ }, compact = true)
                    AppVersionFooter()
                    systemLocale()
                }
                if (sheetOpen) {
                    WalkthroughInWindowBottomSheet("sheet_target", onDismiss = { sheetOpen = false }) {
                        OliveButton(onClick = { sheetOpen = false }) { Text("BottomSheetClose") }
                    }
                }
                if (dialogOpen) {
                    WalkthroughInWindowDialog(
                        dialogTargetId = "dialog_target",
                        title = { Text("DialogTitle") },
                        onDismiss = { dialogOpen = false },
                        confirmButton = { OliveButton(onClick = { dialogOpen = false }) { Text("DialogOk") } },
                        dismissButton = { OliveTextButton(onClick = { dialogOpen = false }) { Text("DialogCancel") } },
                    ) { Text("DialogBody") }
                }
            }
        }

        compose.onNodeWithText("DialogOk").performClick()
        compose.onNodeWithText("BottomSheetClose").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Primary").performScrollTo().performClick()
        compose.onNodeWithText("Tonal").performScrollTo().performClick()
        compose.onNodeWithText("Outlined").performScrollTo().performClick()
        compose.onNodeWithText("Text").performScrollTo().performClick()
        compose.onNodeWithContentDescription("IconBtn").performScrollTo().performClick()
        compose.onNodeWithContentDescription("FilledIconBtn").performScrollTo().performClick()
        compose.onNodeWithContentDescription("TonalIconBtn").performScrollTo().performClick()
        compose.onNodeWithContentDescription("OutlinedIconBtn").performScrollTo().performClick()
        compose.onNodeWithText("Fab").performScrollTo().performClick()
        compose.onNodeWithText("SmallFab").performScrollTo().performClick()
        compose.onNodeWithText("ClickableCard").performScrollTo().performClick()
        compose.onNodeWithText("ClickableOutlined").performScrollTo().performClick()
        compose.onNodeWithText("ActionTile").performScrollTo().performClick()
        assertEquals(12, clicks)
    }

    @Test
    fun allSkeletonLayoutsAndOliveBranchAnimationStatesRender() {
        compose.mainClock.autoAdvance = false
        var ended = false
        compose.setContent {
            OliveUiTestHost {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SkeletonLayout.entries.forEach { layout ->
                        LoadingSkeleton(layout = layout, rows = 2)
                    }
                    SecureFileOpeningIndicator()
                    for (p in listOf(0.0f, 0.20f, 0.45f, 0.65f, 0.90f, 1.0f)) {
                        OliveBranchAnimationContent(progress = p)
                        assertTrue(pinSafeBackgroundAlpha(p) in 0f..1f)
                    }
                    PinSafeOliveTransition(
                        backgroundColor = Color.White,
                        durationMs = 100,
                        onAnimationEnd = { ended = true },
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertTrue(ended)
        assertTrue(compose.onAllNodesWithTag("loading_skeleton").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun birthdayPickerAndAddressFieldsRenderAndUpdate() {
        var birthday by mutableStateOf("")
        var address by mutableStateOf(DeliveryAddress())
        var focusedField = ""
        compose.setContent {
            OliveUiTestHost {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    BirthdayPicker(
                        value = birthday,
                        tag = "dob",
                        enabled = true,
                        showErrors = true,
                        required = true,
                        onChange = { birthday = it },
                    )
                    BirthdayPicker(
                        value = "2000-05-10",
                        tag = "dob_opt",
                        enabled = true,
                        showErrors = true,
                        required = false,
                        onChange = { birthday = it },
                    )
                    AddressFields(
                        value = address,
                        cityError = "Required",
                        onFocus = { focusedField = it },
                        change = { address = it },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("dob_open").performScrollTo().performClick()
        compose.onAllNodesWithTag("dob_range_2026")[0].performScrollTo().performClick()
        compose.onAllNodesWithTag("dob_year_2020")[0].performScrollTo().performClick()
        compose.onAllNodesWithTag("dob_month_5")[0].performScrollTo().performClick()
        compose.onAllNodesWithTag("dob_day_12")[0].performScrollTo().performClick()
        assertEquals("2020-05-12", birthday)

        compose.onNodeWithTag("address_line2").performScrollTo().performTextInput("Unit 4B")
        compose.onNodeWithTag("address_province").performScrollTo().performTextInput("AB")
        compose.onNodeWithTag("address_postal").performScrollTo().performTextInput("T5J 1N3")
        compose.onNodeWithTag("address_country").performScrollTo().performTextClearance()
        compose.onNodeWithTag("address_country").performScrollTo().performTextInput("Canada")
        assertEquals("Unit 4B", address.line2)
        assertEquals("AB", address.province)
        assertEquals("T5J 1N3", address.postalCode)
        assertEquals("Canada", address.country)
    }
}
