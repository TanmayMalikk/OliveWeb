package com.olive.app.ui.shared

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.shared.onboarding.EmailConsentSection
import com.olive.app.ui.users.shared.onboarding.ProfileSetup
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class AnimationAndSharedFieldsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun oliveAnimationViewAndComposeBranchTransitionCoverAllStages() {
        val view = OliveAnimationView(context)
        view.layout(0, 0, 400, 800)
        val bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
        val androidCanvas = Canvas(bitmap)
        var trackedPos = false
        view.onOlivePositionChanged = { _, _ -> trackedPos = true }
        listOf(0f, 0.18f, 0.42f, 0.65f, 0.91f, 1f).forEach { p ->
            view.progress = p
            view.draw(androidCanvas)
        }
        assertTrue(trackedPos)

        compose.mainClock.autoAdvance = false
        var progressState by mutableFloatStateOf(0.15f)
        var ended = false
        compose.setContent {
            OliveUiTestHost {
                OliveBranchAnimationContent(progress = progressState, onOliveDropPosition = { _, _, _ -> })
                PinSafeOliveTransition(backgroundColor = Color.White, durationMs = 150) { ended = true }
            }
        }
        listOf(0.15f, 0.42f, 0.65f, 0.90f, 1.0f).forEach { p ->
            progressState = p
            compose.mainClock.advanceTimeBy(60)
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertTrue(ended)
    }

    @Test
    fun addressFieldsAndBirthdayPickerDialogWalkThroughAllSteps() {
        var address by mutableStateOf(DeliveryAddress(line1 = "100 Main St", city = "Edmonton", province = "AB", postalCode = "T5J 1N3", country = "Canada"))
        var dob by mutableStateOf("")

        compose.setContent {
            OliveUiTestHost {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    AddressFields(value = address, cityOnlyRequired = false, cityError = "City error", shakeTrigger = 1) { address = it }
                    BirthdayPicker(value = dob, tag = "dob", enabled = true, showErrors = true, required = true) { dob = it }
                }
            }
        }
        compose.waitForIdle()
        val year = LocalDate.now().year
        compose.onNodeWithTag("dob_open").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("dob_range_$year").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("dob_year_$year").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("dob_month_1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("dob_day_1").performClick()
        compose.waitForIdle()
        assertTrue(dob.startsWith("$year-01-01"))
    }

    @Test
    fun profileSetupStepsEmailConsentAndAffectedPeopleRenderAndAdvance() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.ProfileSetup, page = SafePage.Home, localMode = true).use { handle ->
            var stage by mutableIntStateOf(0)
            var affected by mutableStateOf("")
            var email by mutableStateOf("")
            var consent by mutableStateOf(false)

            compose.setContent {
                OliveUiTestHost {
                    if (stage == 0) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            EmailConsentSection(
                                email = email,
                                consent = consent,
                                enabled = true,
                                showErrors = true,
                                requireConsentWhenEntered = true,
                                emailTag = "sec_email",
                                consentTag = "sec_consent",
                                onEmailChange = { email = it },
                                onConsentChange = { consent = it },
                            )
                        }
                    } else {
                        ProfileSetup(handle.session)
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("sec_email").performTextInput("alex@example.com")
            compose.waitForIdle()
            compose.onNodeWithTag("sec_consent").performClick()
            assertTrue(consent)

            stage = 1
            compose.waitForIdle()
            compose.onNodeWithTag("full_name").performTextInput("Alex Rivera")
            val year = LocalDate.now().year
            compose.onNodeWithTag("dob_open").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("dob_range_$year").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("dob_year_$year").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("dob_month_1").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("dob_day_1").performClick()
            compose.waitForIdle()

            compose.onNodeWithTag("onboarding_next").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("address_city").performScrollTo().performTextInput("Edmonton")
            compose.onNodeWithTag("onboarding_next").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("trigger").performScrollTo().performTextInput("sunflower")
            compose.onNodeWithTag("setup_contact_name").performScrollTo().performTextInput("Sam")
            compose.onNodeWithTag("setup_contact_relationship").performScrollTo().performTextInput("Sister")
            compose.onNodeWithTag("setup_contact_details").performScrollTo().performTextInput("555-0199")
            compose.onNodeWithTag("add_onboarding_contact").performScrollTo().performClick()
            compose.onNodeWithTag("onboarding_next").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("onboarding_next").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("profile_submit").performScrollTo().performClick()
            compose.waitForIdle()
        }
    }
}
