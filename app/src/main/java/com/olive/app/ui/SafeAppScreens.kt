package com.olive.app.ui


import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*

// Route User pages to focused screen files; Safe Contacts use their separate connection-focused shell.
// SafeHomeScreen owns the feature tiles; individual screens own their layout and accessibility semantics.
@Composable internal fun SafeArea(session: SessionViewModel, exit: ()->Unit) {
    val profile=session.profile ?: return
    LaunchedEffect(session.coverAccountId) { session.ensureMessagingObservation() }
    AnimatedContent(targetState=session.page,label="safe page",
        transitionSpec={
            (fadeIn(tween(180))+slideInHorizontally(tween(180)) { it/12 })
                .togetherWith(fadeOut(tween(120))+slideOutHorizontally(tween(120)) { -it/16 })
        }) { targetPage -> when(targetPage) {
        SafePage.Home -> SafeHomeScreen(session,profile)
        SafePage.Profile -> SafeProfileScreen(session,profile)
        SafePage.Notes -> PrivateNotes(session)
        SafePage.Messages -> MessagesScreen(session,exit)
        SafePage.Settings -> SettingsEditor(session,profile)
        SafePage.Contacts -> ContactEditor(session,profile)
        SafePage.Activity -> ActivityHistory(session)
    } }
}
