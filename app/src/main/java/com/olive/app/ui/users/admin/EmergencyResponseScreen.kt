package com.olive.app.ui.users.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import com.olive.app.R
import com.olive.app.data.location.AdminLocationRepository
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.ui.shared.AccessibleMessage
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import com.olive.app.ui.users.user.documents.SafetyDocumentViewer
import com.olive.app.ui.users.user.location.InteractiveLocationMap
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions

internal data class EmergencyAlert(
    val id: String, val ownerUid: String, val ownerName: String,
    val createdAt: Long, val mediaId: String, val resolvedAt: Long = 0L,
)

@Composable
internal fun observeEmergencyAlerts(session: SessionViewModel): Pair<List<EmergencyAlert>?, String?> {
    var alerts by remember(session.coverAccountId, session.localMode) {
        mutableStateOf<List<EmergencyAlert>?>(if (session.localMode) emptyList() else null)
    }
    var error by remember(session.coverAccountId) { mutableStateOf<String?>(null) }
    DisposableEffect(session.coverAccountId, session.localMode) {
        val listener = if (session.localMode) null else FirebaseFirestore.getInstance()
            .collection("emergencyReports").addSnapshotListener(com.google.firebase.firestore.MetadataChanges.INCLUDE) { snapshot, failure ->
                if (failure != null) error = failure.localizedMessage
                if (snapshot != null && !snapshot.metadata.isFromCache) {
                    error = null
                    alerts = snapshot.documents.mapNotNull { doc ->
                        val owner = doc.getString("ownerUid") ?: return@mapNotNull null
                        EmergencyAlert(
                            doc.id, owner, doc.getString("ownerName") ?: owner,
                            doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L, doc.getString("mediaId").orEmpty(),
                            doc.getTimestamp("resolvedAt")?.toDate()?.time ?: 0L,
                        )
                    }.sortedByDescending { it.createdAt }
                }
            }
        onDispose { listener?.remove() }
    }
    val unavailable = stringResource(R.string.emergency_alerts_load_failed)
    LaunchedEffect(session.coverAccountId, alerts) {
        if (alerts == null) {
            kotlinx.coroutines.delay(12_000)
            if (error == null) error = unavailable
        }
    }
    return alerts to error
}

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
    var resolveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val visibleAlerts = alerts.orEmpty().filter { (it.resolvedAt > 0L) == showResolved }
    opened?.let { (document, file) ->
        SafetyDocumentViewer(document, file) { opened = null }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                Text(stringResource(R.string.emergency_response_title), style = MaterialTheme.typography.headlineSmall)
            }
        }
        error?.let { item { AccessibleMessage(it) } }
        resolveError?.let { item { AccessibleMessage(it) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showResolved, onClick = { showResolved = false }, label = { Text(stringResource(R.string.emergency_response_active)) })
                FilterChip(selected = showResolved, onClick = { showResolved = true }, label = { Text(stringResource(R.string.emergency_response_resolved)) })
            }
        }
        if (alerts == null && error == null) item { com.olive.app.ui.shared.LoadingSkeleton() }
        if (alerts != null && visibleAlerts.isEmpty() && error == null) item { Text(stringResource(R.string.emergency_response_empty)) }
        items(visibleAlerts, key = { it.id }) { alert ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(alert.ownerName, style = MaterialTheme.typography.titleMedium)
                    if (alert.createdAt > 0) Text(
                        DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm:ss").withZone(ZoneId.systemDefault())
                            .format(Instant.ofEpochMilli(alert.createdAt)),
                    )
                    OutlinedButton(onClick = { onTrack(alert.ownerUid) }) { Text(stringResource(R.string.emergency_response_track)) }
                    if (alert.mediaId.isBlank()) Text(stringResource(R.string.emergency_response_waiting_pdf))
                    else Button(
                        enabled = alert.mediaId !in session.documents.state.openingDocumentIds,
                        onClick = {
                            session.coverAccountId?.let { uid ->
                                session.documents.openShared(uid, alert.mediaId) { document, file ->
                                    opened = document to file
                                }
                            }
                        }
                    ) { Text(stringResource(R.string.emergency_response_pdf)) }
                    session.documents.state.attachmentErrors[alert.mediaId]?.let { AccessibleMessage(it) }
                    FilledTonalButton(
                        modifier = Modifier.align(Alignment.End),
                        enabled = alert.id !in resolving,
                        onClick = {
                            resolving = resolving + alert.id
                            resolveError = null
                            scope.launch {
                                try {
                                    val uid = session.coverAccountId
                                    val user = FirebaseAuth.getInstance().currentUser
                                    check(uid != null && user?.uid == uid) { "Sign in again to resolve this request." }
                                    user.getIdToken(false).await()
                                    try {
                                        FirebaseAppCheck.getInstance().getAppCheckToken(false).await()
                                    } catch (failure: Exception) {
                                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                                        throw IllegalStateException(context.getString(R.string.emergency_report_device_verification_failed), failure)
                                    }
                                    FirebaseFunctions.getInstance("northamerica-northeast1")
                                        .getHttpsCallable(if (alert.resolvedAt == 0L) "emergencyResolve" else "emergencyReopen")
                                        .call(mapOf("id" to alert.id)).await()
                                } catch (failure: Exception) {
                                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                                    resolveError = failure.localizedMessage
                                } finally {
                                    resolving = resolving - alert.id
                                }
                            }
                        },
                    ) {
                        Icon(if (alert.resolvedAt == 0L) Icons.Outlined.CheckCircle else Icons.Outlined.Replay, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (alert.resolvedAt == 0L) R.string.emergency_response_resolve else R.string.emergency_response_reopen))
                    }
                }
            }
        }
    }
}

@Composable
internal fun EmergencyLocationPreview(ownerUid: String) {
    var points by remember(ownerUid) { mutableStateOf(emptyList<MapLocationPoint>()) }
    var error by remember(ownerUid) { mutableStateOf<String?>(null) }
    DisposableEffect(ownerUid) {
        val subscription = AdminLocationRepository().observe(
            onUpdate = { values -> points = values.filter { it.uid == ownerUid } },
            onError = { error = it },
        )
        onDispose { subscription.close() }
    }
    Column {
        if (points.isEmpty()) Text(error ?: stringResource(R.string.emergency_response_no_location))
        else InteractiveLocationMap(
            points = points, ownPointUid = null, initialFollowUid = ownerUid,
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
    }
}
