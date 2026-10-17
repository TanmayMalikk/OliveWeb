package com.olive.app.ui

import android.app.KeyguardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.security.SafeEntryMethod

// The safety answer changes the recommendation, never silently chooses an unlock method.
@Composable internal fun SafeEntrySetup(session: SessionViewModel) {
    var knowsPin by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var method by rememberSaveable { mutableStateOf<SafeEntryMethod?>(null) }
    if(method==SafeEntryMethod.APP_PIN) {
        BackHandler { if(!session.busy) method=null }
        AppPinSetup(session) { method=null }
        return
    }
    val context=LocalContext.current
    val secure=context.getSystemService(KeyguardManager::class.java).isDeviceSecure
    FormColumn {
        Heading(stringResource(R.string.entry_setup_title),stringResource(R.string.entry_setup_description))
        Text(stringResource(R.string.entry_safety_question),style=MaterialTheme.typography.titleLarge)
        // Use the same full-width outlined controls as the other onboarding choices.
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            listOf(true,false).forEach { answer ->
                val chosen=knowsPin==answer
                OutlinedButton(onClick={knowsPin=answer},enabled=!session.busy,
                    modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)
                        .semantics { selected=chosen }
                        .testTag(if(answer) "entry_knows_pin_yes" else "entry_knows_pin_no"),
                    contentPadding=PaddingValues(horizontal=16.dp,vertical=12.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected=chosen,onClick=null,enabled=!session.busy)
                        Text(stringResource(if(answer) R.string.entry_yes else R.string.entry_no),
                            style=MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        knowsPin?.let { answer ->
            val recommended=SafeEntryMethod.recommended(answer)
            Text(stringResource(R.string.entry_choose_method),style=MaterialTheme.typography.titleMedium)
            Text(stringResource(if(answer) R.string.entry_recommend_pin else R.string.entry_recommend_device),color=Muted)
            SafeEntryMethod.entries.forEach { choice ->
                val label=stringResource(if(choice==SafeEntryMethod.APP_PIN) R.string.entry_new_pin else R.string.entry_device_lock)
                val buttonLabel=if(choice==recommended) stringResource(R.string.entry_recommended,label) else label
                OutlinedButton(onClick={
                    if(choice==SafeEntryMethod.APP_PIN) method=choice else session.authenticateDeviceLock(enroll=true)
                },enabled=!session.busy && (choice==SafeEntryMethod.APP_PIN || secure),
                    modifier=Modifier.fillMaxWidth().testTag(if(choice==SafeEntryMethod.APP_PIN) "entry_choose_pin" else "entry_choose_device")) {
                    Text(buttonLabel)
                }
            }
            Text(stringResource(R.string.entry_device_description),style=MaterialTheme.typography.bodySmall,color=Muted)
            if(!secure) Text(stringResource(R.string.entry_device_unavailable),color=MaterialTheme.colorScheme.error)
        }
        Status(session)
        OutlinedButton(onClick=session::back,enabled=!session.busy,modifier=Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.back))
        }
    }
}

@Composable internal fun DeviceLockPrompt(session: SessionViewModel) {
    LaunchedEffect(Unit) { session.authenticateDeviceLock() }
    FormColumn {
        Heading(stringResource(R.string.entry_device_lock),stringResource(R.string.entry_unlock_description))
        Text(stringResource(R.string.entry_device_description),color=Muted)
        Status(session)
        Button(onClick={session.authenticateDeviceLock()},enabled=!session.busy,
            modifier=Modifier.fillMaxWidth().testTag("device_lock_unlock")) {
            Text(stringResource(R.string.entry_unlock))
        }
    }
}
