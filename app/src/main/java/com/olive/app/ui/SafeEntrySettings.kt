package com.olive.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import com.olive.app.R
import com.olive.app.security.SafeEntryMethod

// Both methods may be configured; only the preferred method appears on a normal trigger.
@Composable internal fun SafeEntrySettings(session:SessionViewModel) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val text=rememberResourceText()
    Text(stringResource(R.string.entry_choose_method),style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics { heading() })
    val currentLabel=stringResource(if(session.preferredEntryMethod==SafeEntryMethod.DEVICE_LOCK) R.string.entry_device_lock else R.string.entry_new_pin)
    Text(stringResource(R.string.entry_current_method,currentLabel),modifier=Modifier.testTag("entry_current_method").semantics { liveRegion=LiveRegionMode.Polite })
    Text(stringResource(R.string.entry_backup_description))
    if(session.canUseAppPin) Field(text(R.string.current_device_pin),currentPin,"current_device_pin",secret=true,numeric=true,
        showSecretToggle=true,enabled=!session.busy) { currentPin=it.filter(Char::isDigit).take(6) }
    Button(onClick={session.setSafeEntryMethod(SafeEntryMethod.DEVICE_LOCK,currentPin);currentPin=""},
        enabled=!session.busy && session.phoneHasLock,modifier=Modifier.fillMaxWidth().testTag("entry_use_device")) {
        Text(stringResource(R.string.entry_use_device))
    }
    if(!session.phoneHasLock) Text(stringResource(R.string.entry_device_unavailable),modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
    if(session.hasAppPin && session.preferredEntryMethod!=SafeEntryMethod.APP_PIN) {
        OutlinedButton(onClick={session.setSafeEntryMethod(SafeEntryMethod.APP_PIN,currentPin);currentPin=""},enabled=!session.busy,
            modifier=Modifier.fillMaxWidth().testTag("entry_use_pin")) { Text(stringResource(R.string.entry_use_pin)) }
    }
    Text(stringResource(R.string.entry_pin_setup),style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics { heading() })
    Field(text(R.string.new_device_pin),newPin,"new_device_pin",secret=true,numeric=true,showSecretToggle=true,
        enabled=!session.busy) { newPin=it.filter(Char::isDigit).take(6) }
    Field(text(R.string.confirm_new_device_pin),confirmation,"confirm_new_device_pin",secret=true,numeric=true,showSecretToggle=true,
        enabled=!session.busy) { confirmation=it.filter(Char::isDigit).take(6) }
    fun save(usePin:Boolean) {
        session.saveSafeEntryPin(currentPin,newPin,confirmation,usePin)
        currentPin="";newPin="";confirmation=""
    }
    if(session.preferredEntryMethod==SafeEntryMethod.DEVICE_LOCK) {
        OutlinedButton(onClick={save(false)},enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag("entry_save_backup")) {
            Text(stringResource(R.string.entry_save_backup))
        }
    }
    Button(onClick={save(true)},enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag(if(session.preferredEntryMethod==SafeEntryMethod.APP_PIN) "change_device_pin" else "entry_save_use_pin")) {
        Text(stringResource(R.string.entry_save_use_pin))
    }
}
