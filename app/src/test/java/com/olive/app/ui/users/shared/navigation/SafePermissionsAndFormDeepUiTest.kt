package com.olive.app.ui.users.shared.navigation

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.olive.app.R
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.support.OliveUiTestHost
import com.olive.app.ui.shared.AddressFields
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class SafePermissionsAndFormDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun safePermissionsSettingsAndFullAddressFieldsCoverAllControls() {
        var address by mutableStateOf(DeliveryAddress(country = "Canada"))
        var launchedCount = 0

        compose.setContent {
            OliveUiTestHost(locationGranted = false) {
                CompositionLocalProvider(LocalSafeSystemLauncher provides { launchedCount++; false }) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SafePermissionsSettings()
                        AddressFields(
                            address = address,
                            cityOnlyRequired = false,
                            onChange = { address = it },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        // Click "Open settings" buttons on SafePermissionsSettings
        compose.onAllNodesWithText(context.getString(R.string.app_permissions_open_settings))[0]
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        // Edit full AddressFields
        compose.onNodeWithTag("address_line1").performScrollTo().performTextReplacement("123 Jasper Ave")
        compose.onNodeWithTag("address_city").performScrollTo().performTextReplacement("Edmonton")
        compose.waitForIdle()
        assertTrue(address.city == "Edmonton" && address.line1 == "123 Jasper Ave")
    }
}
