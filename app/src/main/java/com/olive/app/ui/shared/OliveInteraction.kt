package com.olive.app.ui.shared

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.olive.app.R

/** Tracks the current destination only. Canceling the gesture leaves its navigation state intact. */
@Composable
internal fun rememberOliveBack(
    enabled: Boolean = true,
    preview: Boolean = true,
    onBack: () -> Unit,
): State<Float> {
    val progress = remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { if (preview) progress.floatValue = it.progress }
            onBack()
        } finally {
            progress.floatValue = 0f
        }
    }
    return progress
}

/** Provides movement without exposing the content behind this destination. */
internal fun Modifier.oliveBackPreview(progress: State<Float>): Modifier = graphicsLayer {
    val scale = 1f - progress.value * 0.045f
    scaleX = scale
    scaleY = scale
}

/** Animates the arriving destination without retaining a second copy of outgoing content. */
@Composable
internal fun OliveRouteEntrance(route: Any, back: State<Float>, content: @Composable () -> Unit) {
    val opacity = remember(route) { Animatable(0f) }
    LaunchedEffect(opacity) { opacity.animateTo(1f, tween(160)) }
    Box(Modifier.fillMaxSize().oliveBackPreview(back).graphicsLayer { alpha = opacity.value }) {
        content()
    }
}

internal val LocalOliveUndo = staticCompositionLocalOf<(String, () -> Unit) -> Unit> { { _, _ -> } }

/** Offers one undo action for the current account and destination. */
@Composable
internal fun OliveUndoOverlay(
    scopeKey: Any?,
    enabled: Boolean,
    bottomClearance: Dp = 12.dp,
    content: @Composable () -> Unit,
) {
    val host = remember(scopeKey) { SnackbarHostState() }
    val actionLabel = stringResource(R.string.undo_action)
    var pending by remember(scopeKey) { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    LaunchedEffect(scopeKey, pending, enabled) {
        val action = pending ?: return@LaunchedEffect
        if (enabled && host.showSnackbar(
                action.first, actionLabel, withDismissAction = true, duration = SnackbarDuration.Long,
            ) == SnackbarResult.ActionPerformed
        ) action.second()
        pending = null
    }
    CompositionLocalProvider(LocalOliveUndo provides { message, action ->
        if (enabled) pending = message to action
    }) {
        Box(Modifier.fillMaxSize()) {
            content()
            if (enabled) SnackbarHost(
                host,
                Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 12.dp, end = 12.dp, bottom = bottomClearance),
            )
        }
    }
}
