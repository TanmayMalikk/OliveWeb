// Contains the cover setup code for this part of Olive.
package com.olive.app.ui.covers.shared

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.olive.app.data.AccountRole
import com.olive.app.data.CoverType
import com.olive.app.R
import com.olive.app.security.InputPolicy
import com.olive.app.ui.users.shared.onboarding.BirthdayPicker
import com.olive.app.ui.covers.olive.AddressFields

@Composable
internal fun CoverAccountPanel(session: SessionViewModel) {
    var fullName by remember(session.coverFullName) { mutableStateOf(session.coverFullName) }
    var dob by remember(session.coverDob) { mutableStateOf(session.coverDob) }
    var address by remember(session.deliveryAddress) { mutableStateOf(session.deliveryAddress) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("@${session.coverUsername}")
        OutlinedTextField(
            fullName,
            { fullName = it.take(120) },
            label = { Text(stringResource(R.string.full_name)) },
            singleLine = true,
            enabled = !session.busy,
            modifier = Modifier.fillMaxWidth().testTag("cover_full_name").onFocusChanged {
                if (it.isFocused) session.recordSettingsInteraction(
                    session.coverType.title,
                    "Focused full name"
                )
            }.keepVisibleWhileTyping()
        )
        BirthdayPicker(dob, "cover_dob", !session.busy) { dob = it }
        Text(stringResource(R.string.location), style = MaterialTheme.typography.titleMedium)
        AddressFields(address, cityOnlyRequired = true) { address = it }
        Button(
            onClick = { session.saveCoverSettings(fullName, dob, address) },
            enabled = !session.busy && fullName.isNotBlank() && InputPolicy.validDob(dob) && address.locationValid,
            modifier = Modifier.fillMaxWidth().testTag("cover_save_settings")
        ) {
            Text(stringResource(R.string.save_account_settings))
        }
        session.message?.let { AccessibleMessage(it) }
    }
}

// Android reads launcher names/icons from aliases; switch them here when the saved cover changes.
internal object CoverLauncher {
    // Keep Android Studio's Run target alive without touching the user's visible cover icon.
    fun ensureStudioLauncher(context: Context) {
        val manager = context.packageManager
        val component = ComponentName(context.packageName, "${context.packageName}.OliveLauncher")
        if (manager.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            manager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
    }

    fun apply(context: Context, selected: CoverType, role: AccountRole) {
        val manager = context.packageManager
        val aliases = listOf(
            "FoodLauncher",
            "NewsLauncher",
            "CalculatorLauncher",
            "BranchLauncher",
            "TreeLauncher"
        )
        val target = when (role) {
            AccountRole.SAFE_CONTACT -> "BranchLauncher"
            AccountRole.STAFF, AccountRole.ADMIN -> "TreeLauncher"
            AccountRole.USER -> when (selected) {
                CoverType.NEWS -> "NewsLauncher"
                CoverType.CALCULATOR -> "CalculatorLauncher"
                else -> "FoodLauncher"
            }
        }
        val changes = aliases.map { name ->
            val component = ComponentName(context.packageName, "${context.packageName}.$name")
            val state =
                if (name == target) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            component to state
        }.filter { (component, state) -> manager.getComponentEnabledSetting(component) != state }
        if (changes.isEmpty()) return
        // On Pixel's Android versions, change the aliases together so a launcher always stays enabled.
        if (Build.VERSION.SDK_INT >= 33) {
            manager.setComponentEnabledSettings(changes.map { (component, state) ->
                PackageManager.ComponentEnabledSetting(
                    component,
                    state,
                    PackageManager.DONT_KILL_APP
                )
            })
        } else {
            changes.sortedByDescending { it.second == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
                .forEach { (component, state) ->
                    manager.setComponentEnabledSetting(
                        component,
                        state,
                        PackageManager.DONT_KILL_APP
                    )
                }
        }
    }
}
