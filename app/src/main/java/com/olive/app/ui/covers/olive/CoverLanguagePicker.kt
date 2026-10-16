package com.olive.app.ui.covers.olive

import com.olive.app.ui.*

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag

// Add a cover language here only after its text is added in Localization and the safe picker.
// UI sizing/text: this picker fills the account card so translated language names have room.
@Composable internal fun CoverLanguagePicker(value: String, enabled: Boolean = true, onChange: (String)->Unit) {
    var open by remember { mutableStateOf(false) }
    val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    val supportedLanguages = listOf("English", "French", "Spanish", "Arabic", "Tagalog", "Chinese", "Cree")
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick={keyboard?.hide();focus.clearFocus(force=true);open=true},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("cover_language")) {
            LocalizedText("App language")
            Spacer(Modifier.weight(1f))
            LocalizedText(getDisplayLanguage(value))
        }
        DropdownMenu(expanded=open,onDismissRequest={open=false}) {
            supportedLanguages.forEach { choice ->
                DropdownMenuItem(text={LocalizedText(getDisplayLanguage(choice))},onClick={onChange(choice);open=false})
            }
        }
    }
}
