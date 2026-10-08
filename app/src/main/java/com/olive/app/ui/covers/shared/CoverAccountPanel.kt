package com.olive.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
// Cover account settings expose ordinary profile details and language, like Olive's account tab.
// UI sizing/text: account controls use 10dp vertical gaps and full-width Material fields/buttons for translated labels.
@Composable internal fun CoverAccountPanel(session: SessionViewModel) {
    var fullName by remember(session.coverFullName) { mutableStateOf(session.coverFullName) }
    var language by remember(session.language) { mutableStateOf(session.language) }
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        LocalizedText("@${session.coverUsername}")
        OutlinedTextField(fullName,{fullName=it.take(120)},label={LocalizedText("Full name")},singleLine=true,
            modifier=Modifier.fillMaxWidth().onFocusChanged { if(it.isFocused) session.recordSettingsInteraction("Daily Digest","Focused full name") })
        Row(verticalAlignment=Alignment.CenterVertically) {
            LocalizedText("App language",modifier=Modifier.weight(1f))
            TextButton(onClick={
                session.recordSettingsInteraction("Daily Digest","Selected language")
                val langs = listOf("English", "French", "Spanish", "Arabic", "Tagalog", "Chinese")
                val nextIdx = (langs.indexOf(language).takeIf { it >= 0 } ?: 0) + 1
                language = langs[nextIdx % langs.size]
            }) {
                LocalizedText(getDisplayLanguage(language))
            }
        }
        Button(onClick={session.saveCoverSettings(fullName,language)},enabled=!session.busy && fullName.isNotBlank(),modifier=Modifier.fillMaxWidth()) {
            LocalizedText("Save account settings")
        }
        session.message?.let { LocalizedText(it) }
    }
}
