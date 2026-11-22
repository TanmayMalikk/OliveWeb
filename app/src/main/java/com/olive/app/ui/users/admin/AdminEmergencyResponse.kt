// Admins: displays active and resolved emergency alerts, alert popup previews, and report tracking actions.
package com.olive.app.ui.users.admin

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveFilledTonalButton as FilledTonalButton
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.LocalizedIcon as Icon
import kotlin.time.Duration.Companion.milliseconds

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.olive.app.R
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.ui.session.EmergencyAlert
import com.olive.app.ui.shared.*
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import com.olive.app.ui.users.user.documents.SafetyDocumentViewer
import com.olive.app.ui.users.user.location.InteractiveLocationMap
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * Composes the observe emergency alerts UI element.
 *
 * @param session Shared session view model owning application state and actions.
 * @return The resulting [Pair] for observe emergency alerts.
 */
@Composable
internal fun observeEmergencyAlerts(session: SessionViewModel): Pair<List<EmergencyAlert>?, String?> {
    var alerts by remember(session.coverAccountId, session.localMode) {
        mutableStateOf<List<EmergencyAlert>?>(if (session.localMode) emptyList() else null)
    }
    var error by remember(session.coverAccountId) { mutableStateOf<String?>(null) }
    DisposableEffect(session.coverAccountId, session.localMode) {
        val listener = if (session.localMode) null else session.emergencyResponses.observeAlerts(
            onUpdate = { alerts = it; error = null },
            onError = { error = it },
        )
        onDispose { listener?.close() }
    }
    val unavailable = stringResource(R.string.emergency_alerts_load_failed)
    LaunchedEffect(session.coverAccountId, alerts) {
        if (alerts == null) {
            kotlinx.coroutines.delay(12_000.milliseconds)
            if (error == null) error = unavailable
        }
    }
    return alerts to error
}

/**
 * Renders the emergency response screen UI.
 *
 * @param session Shared session view model owning application state and actions.
 * @param alerts Collection of alerts values to process.
 * @param error Throwable or error message describing a failure, or null when not provided.
 * @param onBack Callback invoked when the user navigates back.
 * @param onTrack Callback invoked when track changes or occurs.
 */
@Composable
internal fun EmergencyResponseScreen(
    session: SessionViewModel,
    alerts: List<EmergencyAlert>?,
    error: String?,
    onBack: () -> Unit,
    onTrack: (String) -> Unit,
) {
    var opened by remember { mutableStateOf<Pair<SafetyDocument, File>?>(null) }
    var showResolved by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(emptySet<String>()) }
    var pendingResolutions by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    var resolveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var lastShowResolved by remember { mutableStateOf(showResolved) }
    LaunchedEffect(showResolved) {
        if (showResolved != lastShowResolved) {
            lastShowResolved = showResolved
            listState.scrollToItem(0)
        }
    }
    val visibleAlerts = alerts.orEmpty().filter {
        (pendingResolutions[it.id] ?: (it.resolvedAt > 0L)) == showResolved
    }
    val activeCount = alerts.orEmpty().count { !(pendingResolutions[it.id] ?: (it.resolvedAt > 0L)) }
    val resolvedCount = alerts.orEmpty().count { pendingResolutions[it.id] ?: (it.resolvedAt > 0L) }
    LaunchedEffect(alerts, resolving) {
        val syncing = pendingResolutions.filter { (id, resolved) ->
            id !in resolving && alerts?.any { it.id == id && (it.resolvedAt > 0L) != resolved } == true
        }
        pendingResolutions = pendingResolutions.filterKeys { it in resolving || it in syncing }
        if (syncing.isNotEmpty()) {
            kotlinx.coroutines.delay(10_000.milliseconds)
            pendingResolutions = pendingResolutions - syncing.keys
        }
    }
    opened?.let { (document, file) ->
        SafetyDocumentViewer(document, file) { opened = null }
        return
    }
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.emergency_response_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        stringResource(R.string.admin_emergency_response_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
            }
        }
        error?.let { item { AccessibleMessage(it) } }
        resolveError?.let { item { AccessibleMessage(it) } }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = LightSage,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    EmergencyFilterTab(
                        label = stringResource(R.string.emergency_response_active),
                        count = alerts?.let { activeCount },
                        selected = !showResolved,
                        urgentCount = !showResolved,
                        onClick = { showResolved = false },
                    )
                    EmergencyFilterTab(
                        label = stringResource(R.string.emergency_response_resolved),
                        count = alerts?.let { resolvedCount },
                        selected = showResolved,
                        urgentCount = false,
                        onClick = { showResolved = true },
                    )
                }
            }
        }
        if (alerts == null && error == null) item { LoadingSkeleton(SkeletonLayout.EmergencyAlerts) }
        if (alerts != null && visibleAlerts.isEmpty() && error == null) item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = SoftCardSurface,
                border = BorderStroke(1.dp, colors.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier.size(52.dp).clip(CircleShape).background(IconBadgeEnabled),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(26.dp), tint = OliveGreen)
                    }
                    Text(
                        stringResource(R.string.emergency_response_empty),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink,
                    )
                }
            }
        }
        items(visibleAlerts, key = { it.id }) { alert ->
            val completed = pendingResolutions[alert.id] ?: (alert.resolvedAt > 0L)
            EmergencyAlertCard(
                alert = alert,
                completed = completed,
                openingPdf = alert.mediaId in session.documents.state.openingDocumentIds,
                attachmentError = session.documents.state.attachmentErrors[alert.mediaId],
                resolveEnabled = alert.id !in pendingResolutions,
                onTrack = { onTrack(alert.ownerUid) },
                onOpenPdf = {
                    session.coverAccountId?.let { uid ->
                        session.documents.openShared(uid, alert.mediaId) { document, file ->
                            opened = document to file
                        }
                    }
                },
                onToggleResolved = {
                    pendingResolutions = pendingResolutions + (alert.id to !completed)
                    resolving = resolving + alert.id
                    resolveError = null
                    scope.launch {
                        try {
                            session.emergencyResponses.setResolved(
                                session.coverAccountId, alert.id, !completed,
                            )
                        } catch (failure: Exception) {
                            if (failure is kotlinx.coroutines.CancellationException) throw failure
                            pendingResolutions = pendingResolutions - alert.id
                            resolveError = failure.localizedMessage
                        } finally {
                            resolving = resolving - alert.id
                        }
                    }
                },
            )
        }
    }
}

/**
 * Renders one segmented filter tab for active or resolved emergency requests.
 */
@Composable
private fun RowScope.EmergencyFilterTab(
    label: String,
    count: Int?,
    selected: Boolean,
    urgentCount: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Paper else Color.Transparent,
        shadowElevation = if (selected) 1.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) Ink else Muted,
                maxLines = 1,
            )
            if (count != null) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (urgentCount && count > 0) CallRed else MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        "$count",
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (urgentCount && count > 0) Paper else Muted,
                    )
                }
            }
        }
    }
}

/**
 * Displays one emergency alert card with status header, timestamp, PDF action, live tracking, and resolution control.
 */
@Composable
private fun EmergencyAlertCard(
    alert: EmergencyAlert,
    completed: Boolean,
    openingPdf: Boolean,
    attachmentError: String?,
    resolveEnabled: Boolean,
    onTrack: () -> Unit,
    onOpenPdf: () -> Unit,
    onToggleResolved: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Paper,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, if (!completed) CallRed.copy(alpha = 0.28f) else colors.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (!completed) CallRed.copy(alpha = 0.06f) else SoftCardSurface)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(if (!completed) CallRed.copy(alpha = 0.14f) else IconBadgeEnabled),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (!completed) Icons.Outlined.WarningAmber else Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = if (!completed) CallRed else OliveGreen,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        alert.ownerName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                    if (alert.createdAt > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.Outlined.Schedule, null, Modifier.size(14.dp), tint = Muted)
                            Text(
                                DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm:ss")
                                    .withZone(ZoneId.systemDefault())
                                    .format(Instant.ofEpochMilli(alert.createdAt)),
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                            )
                        }
                    }
                }
                if (!completed) {
                    Surface(shape = RoundedCornerShape(50), color = CallRed) {
                        Text(
                            stringResource(R.string.support_emergency),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Paper,
                        )
                    }
                }
            }
            HorizontalDivider(
                color = if (!completed) CallRed.copy(alpha = 0.14f) else colors.outlineVariant,
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (alert.mediaId.isBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = WarmBannerYellow,
                        border = BorderStroke(1.dp, colors.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Description,
                                null,
                                Modifier.size(18.dp),
                                tint = IncidentAccent,
                            )
                            Text(
                                stringResource(R.string.emergency_response_waiting_pdf),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = IncidentAccent,
                            )
                        }
                    }
                } else {
                    Button(
                        enabled = !openingPdf,
                        onClick = onOpenPdf,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                    ) {
                        if (openingPdf) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = colors.onPrimary,
                            )
                        } else {
                            Icon(Icons.Outlined.Description, null, Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.emergency_response_pdf))
                    }
                }
                OutlinedButton(
                    onClick = onTrack,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                ) {
                    Icon(Icons.Outlined.MyLocation, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.emergency_response_track))
                }
                attachmentError?.let { AccessibleMessage(it) }
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.7f))
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                    enabled = resolveEnabled,
                    onClick = onToggleResolved,
                ) {
                    Icon(
                        if (!completed) Icons.Outlined.CheckCircle else Icons.Outlined.Replay,
                        null,
                        Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (!completed) R.string.emergency_response_resolve
                            else R.string.emergency_response_reopen,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Renders the urgent emergency alert popup with a live location preview, quick actions, and a close button.
 *
 * @param session Shared session view model owning application state and actions.
 * @param alert Active unacknowledged emergency alert to display.
 * @param onDismiss Dismisses and acknowledges the alert.
 * @param onTrack Opens the live map focused on the requester.
 * @param onOpenResponse Opens the Emergency response workspace.
 */
@Composable
internal fun EmergencyAlertDialog(
    session: SessionViewModel,
    alert: EmergencyAlert,
    onDismiss: () -> Unit,
    onTrack: () -> Unit,
    onOpenResponse: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .testTag("emergency_alert_dialog"),
            shape = RoundedCornerShape(28.dp),
            color = colors.surface,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CallRed.copy(alpha = 0.09f))
                        .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(CallRed.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = CallRed,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Surface(shape = RoundedCornerShape(50), color = CallRed) {
                            Text(
                                stringResource(R.string.support_emergency),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Paper,
                            )
                        }
                        Text(
                            stringResource(R.string.emergency_response_alert),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("emergency_alert_close"),
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = colors.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = CallRed.copy(alpha = 0.16f))
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = SoftCardSurface,
                        border = BorderStroke(1.dp, colors.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(IconBadgeEnabled),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.PersonOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = OliveGreen,
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    alert.ownerName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSurface,
                                )
                                if (alert.createdAt > 0) {
                                    Text(
                                        DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm")
                                            .withZone(ZoneId.systemDefault())
                                            .format(Instant.ofEpochMilli(alert.createdAt)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Muted,
                                    )
                                }
                            }
                        }
                    }
                    EmergencyLocationPreview(session, alert.ownerUid)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = onTrack,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Outlined.MyLocation, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.emergency_response_track))
                        }
                        OutlinedButton(
                            onClick = onOpenResponse,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Outlined.Description, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.emergency_response_title))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Composes the emergency location preview UI element.
 *
 * @param session Shared session view model owning application state and actions.
 * @param ownerUid Firebase UID of the resource owner.
 */
@Composable
internal fun EmergencyLocationPreview(session: SessionViewModel, ownerUid: String) {
    var points by remember(session.coverAccountId, ownerUid) { mutableStateOf(emptyList<MapLocationPoint>()) }
    var error by remember(session.coverAccountId, ownerUid) { mutableStateOf<String?>(null) }
    var loading by remember(session.coverAccountId, ownerUid) { mutableStateOf(true) }
    DisposableEffect(session.coverAccountId, ownerUid) {
        val subscription = session.emergencyResponses.observeLocations(
            onUpdate = { values ->
                points = values.filter { it.uid == ownerUid }
                error = null
                loading = false
            },
            onError = { error = it; loading = false },
        )
        onDispose { subscription.close() }
    }
    val shape = RoundedCornerShape(18.dp)
    if (!loading && points.isEmpty()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = SoftCardSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Outlined.LocationSearching,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = Muted,
                )
                Text(
                    error ?: stringResource(R.string.emergency_response_no_location),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        ) {
            InteractiveLocationMap(
                points = points,
                ownPointUid = null,
                initialFollowUid = ownerUid,
                compassEnabled = false,
                showControls = false,
                modifier = Modifier.fillMaxSize(),
            )
            points.firstOrNull()?.let { point ->
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                    shape = RoundedCornerShape(50),
                    color = Paper.copy(alpha = 0.94f),
                    shadowElevation = 2.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (point.live && !point.lastKnown) CallRed else Muted),
                        )
                        Text(
                            stringResource(if (point.live && !point.lastKnown) R.string.organization_map_live else R.string.location_last_known),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Ink,
                        )
                    }
                }
            }
        }
    }
}
