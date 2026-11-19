package com.olive.app.ui.covers.fitness

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.google.firebase.functions.FirebaseFunctionsException
import com.olive.app.R
import com.olive.app.ui.shared.LoadingSkeleton
import com.olive.app.ui.shared.SkeletonLayout
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
internal fun FitnessCoachPage(
    accountId: String,
    cloudAvailable: Boolean,
    store: FitnessStore,
    history: List<FitnessHistoryDay>,
    dataLoading: Boolean,
    onGoalsSaved: (FitnessSnapshot) -> Unit,
) {
    val context = LocalContext.current
    val client = remember(accountId) { FitnessCoachClient(accountId) }
    val chatHistory = remember(context, accountId) { FitnessCoachHistory(context, accountId) }
    val scope = rememberCoroutineScope()
    var membership by remember { mutableStateOf<CoachMembership?>(null) }
    var billingBusy by remember { mutableStateOf(false) }
    var paymentOpen by remember { mutableStateOf(false) }
    var paymentResult by remember { mutableStateOf<PaymentSheetResult?>(null) }
    val paymentSheet = remember {
        PaymentSheet.Builder { result -> paymentResult = result }
    }.build()
    var pendingMessage by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var draft by remember { mutableStateOf("") }
    var turns by remember { mutableStateOf<List<CoachTurn>?>(null) }
    var historyFailed by remember { mutableStateOf(false) }
    var historyAttempt by remember { mutableIntStateOf(0) }
    var proposed by remember { mutableStateOf(emptyMap<String, Int>()) }
    var appliedGoals by remember { mutableStateOf(emptyMap<String, Int>()) }
    var autoGoals by remember { mutableStateOf(store.preferences.getBoolean("coach_auto_goals", false)) }
    var showSettings by remember { mutableStateOf(false) }

    fun runBilling(action: String, verifyPayment: Boolean = false) {
        if (!cloudAvailable || billingBusy || paymentOpen) return
        billingBusy = true
        error = null
        scope.launch {
            try {
                if (action == "status") {
                    membership = client.membership()
                    if (verifyPayment) {
                        repeat(3) {
                            if (membership?.active == true) return@repeat
                            delay(1000)
                            membership = client.membership()
                        }
                        if (membership?.active != true) error = R.string.fitness_coach_payment_pending
                    }
                    if (membership?.active != true) {
                        proposed = emptyMap()
                        appliedGoals = emptyMap()
                        showSettings = false
                    }
                } else if (action == "paymentSheet") {
                    val payment = client.paymentSheet()
                    PaymentConfiguration.init(context, payment.publishableKey)
                    paymentOpen = true
                    paymentSheet.presentWithPaymentIntent(
                        payment.clientSecret,
                        PaymentSheet.Configuration.Builder(context.getString(R.string.fitness_coach_title)).build(),
                    )
                } else if (action == "portal") {
                    context.startActivity(Intent(Intent.ACTION_VIEW, client.billingPortal()))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                paymentOpen = false
                if (action == "status") membership = null
                error = R.string.fitness_coach_billing_error
            } finally {
                billingBusy = false
            }
        }
    }
    LaunchedEffect(paymentResult) {
        val result = paymentResult ?: return@LaunchedEffect
        paymentResult = null
        paymentOpen = false
        when (result) {
            is PaymentSheetResult.Completed -> runBilling("status", verifyPayment = true)
            is PaymentSheetResult.Canceled -> Unit
            is PaymentSheetResult.Failed -> error = R.string.fitness_coach_billing_error
        }
    }
    LifecycleResumeEffect(accountId, cloudAvailable) {
        runBilling("status")
        onPauseOrDispose {}
    }
    val active = cloudAvailable && membership?.active == true
    LaunchedEffect(active, historyAttempt) {
        if (!active || turns != null) return@LaunchedEffect
        historyFailed = false
        try {
            turns = withContext(Dispatchers.IO) { chatHistory.load() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            historyFailed = true
        }
    }

    fun saveGoals(goals: Map<String, Int>, automaticDate: LocalDate? = null): Map<String, Int> {
        val before = store.profile()
        val updated = before.withCoachGoals(goals)
        if (before == updated) return emptyMap()
        store.saveProfile(updated, coachUpdateDate = automaticDate)
        val snapshot = store.snapshot()
        onGoalsSaved(snapshot)
        val saved = coachGoals(snapshot.profile)
        val previous = coachGoals(before)
        return goals.filter { (name, value) -> saved[name] == value && previous[name] != value }
    }

    fun send() {
        val previousTurns = turns ?: return
        if (dataLoading || pendingMessage != null || billingBusy || membership?.active != true || draft.isBlank()) return
        val message = draft.trim()
        val before = store.profile()
        val optedInAtSend = autoGoals
        pendingMessage = message
        error = null
        appliedGoals = emptyMap()
        proposed = emptyMap()
        scope.launch {
            try {
                val response = client.send(message, previousTurns, before, history)
                withContext(Dispatchers.IO + NonCancellable) { chatHistory.append(message, response.text) }
                draft = ""
                turns = previousTurns + CoachTurn("user", message) + CoachTurn("model", response.text)
                val now = store.profile()
                val today = LocalDate.now()
                if (optedInAtSend && autoGoals && membership?.active == true) {
                    val lastUpdate = store.preferences.getString("coach_goal_update", null)
                        ?: store.preferences.getString("coach_step_update", "").orEmpty()
                    val updates = automaticCoachGoals(before, now, response.goals, lastUpdate, today)
                    appliedGoals = saveGoals(updates, automaticDate = today.takeIf { updates.keys.any { it != "steps" } })
                }
                val current = store.profile()
                val currentGoals = coachGoals(current)
                proposed = coachGoals(current.withCoachGoals(response.goals))
                    .filter { (name, value) -> currentGoals[name] != value }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: FirebaseFunctionsException) {
                error = when (failure.code) {
                    FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> R.string.fitness_coach_limit
                    FirebaseFunctionsException.Code.PERMISSION_DENIED -> {
                        membership = membership?.copy(active = false)
                        R.string.fitness_coach_subscription_required
                    }
                    else -> R.string.fitness_coach_reply_error
                }
            } catch (_: Exception) {
                error = R.string.fitness_coach_reply_error
            } finally {
                pendingMessage = null
            }
        }
    }

    val savedTurns = turns
    if (active && savedTurns != null && !dataLoading) {
        FitnessCoachChat(
            turns = savedTurns,
            pendingMessage = pendingMessage,
            draft = if (pendingMessage == null) draft else "",
            onDraftChange = { draft = it.take(1200) },
            onSend = ::send,
            onSettings = { showSettings = true },
            billingBusy = billingBusy,
            error = error,
            appliedGoals = appliedGoals,
            proposed = proposed,
            onApplyGoals = {
                if (membership?.active == true && !billingBusy) {
                    appliedGoals = appliedGoals + saveGoals(proposed)
                    proposed = emptyMap()
                }
            },
        )
        if (showSettings) {
            FitnessCoachSettings(
                autoGoals = autoGoals,
                onAutoGoalsChange = { enabled ->
                    autoGoals = enabled
                    store.preferences.edit { putBoolean("coach_auto_goals", enabled) }
                },
                onManageBilling = if (membership?.canManage == true && !billingBusy) {
                    {
                        showSettings = false
                        runBilling("portal")
                    }
                } else null,
                onDismiss = { showSettings = false },
            )
        }
        return
    }

    Column(
        Modifier.fillMaxSize().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.fitness_coach_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        when {
            !cloudAvailable -> FitnessCoachLocked(
                message = stringResource(R.string.fitness_coach_sign_in),
            )
            billingBusy && membership == null -> LoadingSkeleton(SkeletonLayout.Form)
            active && historyFailed -> {
                Text(stringResource(R.string.fitness_coach_history_error), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { historyAttempt++ }) { Text(stringResource(R.string.retry)) }
            }
            active -> LoadingSkeleton(SkeletonLayout.Form)
            else -> FitnessCoachLocked(message = stringResource(R.string.fitness_coach_offer)) {
                if (membership != null) Button(
                    onClick = { runBilling("paymentSheet") },
                    enabled = !billingBusy && !paymentOpen,
                ) {
                    Text(stringResource(R.string.fitness_coach_subscribe))
                }
            }
        }
        if (billingBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (membership?.canManage == true) TextButton(onClick = { runBilling("portal") }, enabled = !billingBusy) {
            Text(stringResource(R.string.fitness_coach_manage))
        }
        if (cloudAvailable && membership?.active != true) {
            TextButton(onClick = { runBilling("status") }, enabled = !billingBusy) {
                Text(stringResource(R.string.fitness_coach_refresh))
            }
        }
        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun FitnessCoachChat(
    turns: List<CoachTurn>,
    pendingMessage: String?,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onSettings: () -> Unit,
    billingBusy: Boolean,
    error: Int?,
    appliedGoals: Map<String, Int>,
    proposed: Map<String, Int>,
    onApplyGoals: () -> Unit,
) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = turns.lastIndex.coerceAtLeast(0))
    val empty = turns.isEmpty() && pendingMessage == null
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(turns, pendingMessage, proposed, appliedGoals) {
        val last = turns.size + (if (pendingMessage != null) 2 else 0) +
            (if (appliedGoals.isNotEmpty()) 1 else 0) + (if (proposed.isNotEmpty()) 1 else 0) - 1
        if (last >= 0) list.animateScrollToItem(last)
    }
    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.fitness_coach_title),
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, stringResource(R.string.fitness_coach_settings))
            }
        }
        if (billingBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (empty) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(shape = RoundedCornerShape(20.dp), color = colors.primaryContainer) {
                        Icon(Icons.Outlined.AutoAwesome, null, Modifier.padding(16.dp), tint = colors.primary)
                    }
                    Text(
                        stringResource(R.string.fitness_coach_question),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(turns) { turn ->
                    CoachBubble(turn.role == "user") { Text(turn.text) }
                }
                if (pendingMessage != null) {
                    item { CoachBubble(fromUser = true) { Text(pendingMessage) } }
                    item {
                        CoachBubble(fromUser = false) {
                            Row(
                                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.fitness_coach_thinking))
                            }
                        }
                    }
                }
                if (appliedGoals.isNotEmpty()) {
                    item { CoachGoalsCard(R.string.fitness_coach_goals_saved, appliedGoals) }
                }
                if (proposed.isNotEmpty()) {
                    item { CoachGoalsCard(R.string.fitness_coach_review, proposed, onApplyGoals, enabled = !billingBusy) }
                }
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
        Surface(color = colors.surfaceContainerLowest) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                error?.let {
                    Text(
                        stringResource(it),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        color = colors.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.message)) },
                        shape = RoundedCornerShape(24.dp),
                        readOnly = pendingMessage != null,
                        maxLines = 4,
                    )
                    FilledIconButton(
                        onClick = onSend,
                        enabled = pendingMessage == null && !billingBusy && draft.isNotBlank(),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.fitness_coach_send))
                    }
                }
            }
        }
    }
}

@Composable
private fun CoachBubble(fromUser: Boolean, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(if (fromUser) 0.82f else 0.94f),
            shape = RoundedCornerShape(
                topStart = 20.dp, topEnd = 20.dp,
                bottomStart = if (fromUser) 20.dp else 6.dp,
                bottomEnd = if (fromUser) 6.dp else 20.dp,
            ),
            color = if (fromUser) colors.primary else colors.surfaceContainerHigh,
            contentColor = if (fromUser) colors.onPrimary else colors.onSurface,
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    stringResource(if (fromUser) R.string.fitness_coach_you else R.string.fitness_coach_title),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                ProvideTextStyle(MaterialTheme.typography.bodyMedium, content)
            }
        }
    }
}

@Composable
private fun CoachGoalsCard(title: Int, goals: Map<String, Int>, onApply: (() -> Unit)? = null, enabled: Boolean = true) {
    OutlinedCard(Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            coachGoalLabels.forEach { (name, label) ->
                goals[name]?.let { Text(stringResource(label, it), style = MaterialTheme.typography.bodyMedium) }
            }
            if (onApply != null) {
                OutlinedButton(onClick = onApply, enabled = enabled) { Text(stringResource(R.string.fitness_coach_apply)) }
            }
        }
    }
}

@Composable
private fun FitnessCoachSettings(
    autoGoals: Boolean,
    onAutoGoalsChange: (Boolean) -> Unit,
    onManageBilling: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fitness_coach_settings)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().toggleable(autoGoals, role = Role.Switch, onValueChange = onAutoGoalsChange)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.fitness_coach_auto_goals))
                        Text(
                            stringResource(R.string.fitness_coach_auto_goals_detail),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = autoGoals, onCheckedChange = null)
                }
                Text(stringResource(R.string.fitness_coach_privacy), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = {
            if (onManageBilling != null) {
                TextButton(onClick = onManageBilling) { Text(stringResource(R.string.fitness_coach_manage)) }
            }
        },
    )
}

@Composable
private fun FitnessCoachLocked(message: String, actions: @Composable ColumnScope.() -> Unit = {}) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("fitness_coach_locked")) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.fitness_coach_locked), style = MaterialTheme.typography.titleMedium)
            Text(message)
            actions()
        }
    }
}

private val coachGoalLabels = mapOf(
    "steps" to R.string.fitness_coach_goal_steps,
    "calories" to R.string.fitness_coach_goal_calories,
    "protein" to R.string.fitness_coach_goal_protein,
    "carbohydrates" to R.string.fitness_coach_goal_carbs,
    "fat" to R.string.fitness_coach_goal_fat,
    "waterMl" to R.string.fitness_coach_goal_water,
)
