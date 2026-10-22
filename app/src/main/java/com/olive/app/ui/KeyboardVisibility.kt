package com.olive.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity

// Recheck after focus changes and throughout keyboard resizing. Scrolling reveals the field
// without requesting focus, so it cannot steal input or TalkBack focus from another control.
internal fun Modifier.keepVisibleWhileTyping(): Modifier = composed {
    val requester=remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val keyboardBottom=WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(focused,keyboardBottom) {
        if(focused) {
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    this.bringIntoViewRequester(requester).onFocusChanged { focused=it.isFocused }
}
