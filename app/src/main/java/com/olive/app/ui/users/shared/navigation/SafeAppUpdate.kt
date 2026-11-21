package com.olive.app.ui.users.shared.navigation

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveTextButton as TextButton
import kotlin.time.Duration.Companion.milliseconds

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.olive.app.R
import com.olive.app.data.update.AppUpdateClient
import com.olive.app.data.update.AppUpdateInstallerActivity
import com.olive.app.data.update.AppUpdateOffer
import com.olive.app.data.update.AppUpdateStore
import com.olive.app.ui.shared.AccessibleMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

internal val LocalSafeUpdateReminder = compositionLocalOf<(() -> Unit)?> { null }

internal data class SafeAppUpdateUi(
    val offer: AppUpdateOffer?,
    val notice: Int?,
    val onUpdate: () -> Unit,
    val onLater: (() -> Unit)?,
    val onDownload: (() -> Unit)?,
    val onDismissNotice: () -> Unit,
)

/**
 * Composes the remember safe app update UI element.
 *
 * @param enabled Whether this feature or control is currently enabled.
 * @param cameraInHeader Whether to apply camera in header.
 * @param launch Function used to transform or provide launch.
 * @return The resulting [SafeAppUpdateUi] for remember safe app update.
 */
@Composable
internal fun rememberSafeAppUpdate(
    enabled: Boolean,
    cameraInHeader: Boolean,
    launch: (Intent) -> Boolean,
): SafeAppUpdateUi {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val store = remember(context) { AppUpdateStore(context) }
    var offer by remember(store, enabled) { mutableStateOf(if (enabled) store.offer() else null) }
    var notice by remember(enabled, offer?.release?.versionCode) { mutableStateOf<Int?>(null) }
    DisposableEffect(store, enabled) {
        val subscription = if (enabled) store.observe { offer = it } else null
        onDispose { subscription?.close() }
    }
    LaunchedEffect(enabled, store, lifecycle) {
        if (!enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val client = AppUpdateClient(context)
            while (true) {
                withContext(Dispatchers.IO) { runCatching { store.refresh(client) } }
                delay(60_000.milliseconds)
            }
        }
    }
    val current = offer
    val updateNow: () -> Unit = {
        current?.let {
            notice = if (launch(Intent(context, AppUpdateInstallerActivity::class.java)
                    .putExtra(AppUpdateInstallerActivity.EXTRA_RELEASE_JSON, it.release.toJson()))) {
                null
            } else R.string.app_update_failed
        }
    }
    return SafeAppUpdateUi(
        offer = current?.takeUnless { it.dismissed },
        notice = notice,
        onUpdate = updateNow,
        onLater = current?.let {
            {
                store.dismiss(it)
                notice = if (cameraInHeader) R.string.app_update_later_camera_hint else R.string.app_update_later_hint
            }
        },
        onDownload = updateNow.takeIf { current?.dismissed == true },
        onDismissNotice = { notice = null },
    )
}

/**
 * Displays the safe app update banner overlay.
 *
 * @param state Current state instance to read or render.
 * @param modifier Compose modifier applied to the root layout.
 */
@Composable
internal fun SafeAppUpdateBanner(state: SafeAppUpdateUi, modifier: Modifier = Modifier) {
    val dismissNotice by rememberUpdatedState(state.onDismissNotice)
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(3_000.milliseconds)
            dismissNotice()
        }
    }
    Box(modifier.windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(visible = state.notice != null && state.offer == null, enter = fadeIn(), exit = fadeOut()) {
            state.notice?.let { notice ->
                Surface(modifier = Modifier.testTag("safe_update_notice").swipeAway(dismissNotice),
                    shape = MaterialTheme.shapes.large, shadowElevation = 6.dp,
                    color = MaterialTheme.colorScheme.secondaryContainer) {
                    AccessibleMessage(stringResource(notice), Modifier.padding(12.dp))
                }
            }
        }
        UpdateOfferCard(state)
    }
}

/**
 * Renders the update offer card composable.
 *
 * @param state Current state instance to read or render.
 */
@Composable
private fun UpdateOfferCard(state: SafeAppUpdateUi) {
    var recentOffer by remember { mutableStateOf(state.offer) }
    SideEffect { state.offer?.let { recentOffer = it } }
    AnimatedVisibility(
        visible = state.offer != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        val offer = state.offer ?: recentOffer ?: return@AnimatedVisibility
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                .testTag("safe_update_banner_surface")
                .swipeAway(state.onLater.takeIf { state.offer != null }),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AccessibleMessage(
                    stringResource(if (offer.required) R.string.app_update_required else R.string.ui_a_new_update_is_available),
                    modifier = Modifier.testTag("safe_update_banner"),
                )
                Text(stringResource(R.string.app_update_version, offer.release.versionName),
                    style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = state.onUpdate, enabled = state.offer != null) {
                        Text(stringResource(R.string.app_update_now))
                    }
                    state.onLater?.let { later ->
                        TextButton(onClick = later, enabled = state.offer != null) {
                            Text(stringResource(if (offer.required) R.string.app_update_minimize else R.string.app_update_later))
                        }
                    }
                }
                if (state.offer != null) state.notice?.let { AccessibleMessage(stringResource(it)) }
            }
        }
    }
}

/**
 * Composes the swipe away UI element.
 *
 * @param onDismiss Callback invoked when the dialog or sheet is dismissed, or null when not provided.
 * @return The resulting [Modifier] for swipe away.
 */
@Composable
private fun Modifier.swipeAway(onDismiss: (() -> Unit)?): Modifier {
    if (onDismiss == null) return this
    val dismiss by rememberUpdatedState(onDismiss)
    var drag by remember { mutableStateOf(Offset.Zero) }
    return offset {
        if (drag.isHorizontalSwipe()) IntOffset(drag.x.roundToInt(), 0)
        else IntOffset(0, drag.y.coerceAtMost(0f).roundToInt())
    }
        .pointerInput(Unit) {
            detectDragGestures(
                onDragEnd = {
                    val qualifies = if (drag.isHorizontalSwipe()) abs(drag.x) >= 72.dp.toPx()
                    else -drag.y >= minOf(48.dp.toPx(), size.height / 3f)
                    if (qualifies) dismiss()
                    drag = Offset.Zero
                },
                onDragCancel = { drag = Offset.Zero },
            ) { change, amount ->
                change.consume()
                drag += amount
            }
        }
}

/**
 * Checks whether horizontal swipe for offset.
 *
 * @return `true` if is horizontal swipe, or `false` otherwise.
 */
private fun Offset.isHorizontalSwipe() = abs(x) > abs(y)

/**
 * Composes the safe app update reminder UI element.
 */
@Composable
internal fun SafeAppUpdateReminder() {
    val download = LocalSafeUpdateReminder.current
    AnimatedVisibility(visible = download != null, enter = scaleIn(initialScale = .4f) + fadeIn(),
        exit = scaleOut(targetScale = .4f) + fadeOut()) {
        IconButton(onClick = { download?.invoke() }, modifier = Modifier.testTag("safe_update_reminder")) {
            Icon(Icons.Outlined.FileDownload, stringResource(R.string.app_update_now))
        }
    }
}
