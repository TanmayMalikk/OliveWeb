package com.olive.app.ui.covers.fitness

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveOutlinedCard as OutlinedCard
import com.olive.app.ui.shared.OliveTextButton as TextButton
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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

/**
 * Renders the fitness coach page UI.
 *
 * @param accountId Account ID supplied to [FitnessCoachPage].
 * @param cloudAvailable Whether to apply cloud available.
 * @param store Store supplied to [FitnessCoachPage].
 * @param dataLoading Whether to apply data loading.
 * @param onGoalsSaved Callback invoked when goals saved changes or occurs.
 */
@Composable
internal fun FitnessCoachPage(
    accountId: String,
    cloudAvailable: Boolean,
    store: FitnessStore,
    dataLoading: Boolean,
    onGoalsSaved: (FitnessSnapshot) -> Unit,
) {
    val coachClient = remember(accountId) { FitnessCoachClient(accountId) }
    FitnessCoachPage(accountId, cloudAvailable, store, dataLoading, coachClient, onGoalsSaved)
}

/**
 * Renders the fitness coach page UI.
 *
 * @param accountId Account ID supplied to [FitnessCoachPage].
 * @param cloudAvailable Whether to apply cloud available.
 * @param store Store supplied to [FitnessCoachPage].
 * @param dataLoading Whether to apply data loading.
 * @param client Client supplied to [FitnessCoachPage].
 * @param onGoalsSaved Callback invoked when goals saved changes or occurs.
 */
@Composable
internal fun FitnessCoachPage(
    accountId: String,
    cloudAvailable: Boolean,
    store: FitnessStore,
    dataLoading: Boolean,
    client: FitnessCoachService,
    onGoalsSaved: (FitnessSnapshot) -> Unit,
) {
    val context = LocalContext.current
    val coachTitle = stringResource(R.string.fitness_coach_title)
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

    /**
     * Executes billing.
     *
     * @param action Action supplied to [runBilling].
     * @param verifyPayment Whether to apply verify payment.
     */
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
                        PaymentSheet.Configuration.Builder(coachTitle).build(),
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

    /**
     * Saves goals.
     *
     * @param goals Key-value mapping of goals entries.
     * @param automaticDate Optional automatic date supplied to [saveGoals].
     * @return Map containing the resulting save goals entries.
     */
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

    /**
     * Sends the target.
     */
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
                val history = withContext(Dispatchers.IO) { store.coachHistory() }
                val hint = when {
                    isWorkoutOrMealAdvice(message, "") ->
                        "\n(Format with each day/meal section separated by blank lines and each exercise/food as a bulleted item starting with • on its own line. Provide specific exercises, sets, and reps or food ideas, not step count suggestions.)"
                    isCasualOrGreeting(message) ->
                        "\n(Respond warmly, naturally, and conversationally like a real human coach. Do not pitch workouts, steps, or meals unless asked.)"
                    else -> ""
                }
                val outgoingMessage = if (hint.isNotEmpty() && message.length + hint.length <= 1200) {
                    "$message$hint"
                } else {
                    message
                }
                val response = client.send(outgoingMessage, previousTurns, before, history)
                val replyText = formatCoachReply(response.text)
                withContext(Dispatchers.IO + NonCancellable) { chatHistory.append(message, replyText) }
                draft = ""
                turns = previousTurns + CoachTurn("user", message) + CoachTurn("model", replyText)
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
        FitnessCoachFrame(onSettings = { showSettings = true }) {
            FitnessCoachChat(
                turns = savedTurns,
                pendingMessage = pendingMessage,
                draft = if (pendingMessage == null) draft else "",
                onDraftChange = { draft = it.take(1200) },
                onSend = ::send,
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
        }
        if (showSettings) {
            FitnessCoachSettings(
                autoGoals = autoGoals,
                onAutoGoalsChange = { enabled ->
                    autoGoals = enabled
                    store.preferences.edit { putBoolean("coach_auto_goals", enabled) }
                },
                onClearHistory = {
                    scope.launch {
                        withContext(Dispatchers.IO) { chatHistory.clear() }
                        turns = emptyList()
                    }
                    showSettings = false
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

    FitnessCoachFrame {
        Column(
            Modifier.fillMaxSize().navigationBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
}

/**
 * Renders the coach goals card composable.
 *
 * @param title Primary display title text.
 * @param goals Key-value mapping of goals entries.
 * @param onApply Callback invoked when apply changes or occurs.
 * @param enabled Whether this feature or control is currently enabled.
 */
@Composable
internal fun CoachGoalsCard(title: Int, goals: Map<String, Int>, onApply: (() -> Unit)? = null, enabled: Boolean = true) {
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

private fun isWorkoutOrMealAdvice(query: String, reply: String): Boolean {
    val keywords = listOf(
        "workout", "exercise", "routine", "gym", "training", "squat", "push-up", "lifting", "cardio",
        "meal", "diet", "recipe", "breakfast", "lunch", "dinner", "snack", "nutrition plan", "eating plan", "food plan",
    )
    val lowerQuery = query.lowercase()
    val lowerReply = reply.lowercase()
    return keywords.any { it in lowerQuery } || keywords.any { it in lowerReply }
}

private fun isCasualOrGreeting(query: String): Boolean {
    val trimmed = query.trim().lowercase().removeSuffix("?").removeSuffix("!").removeSuffix(".")
    val greetings = setOf(
        "hi", "hello", "hey", "how are you", "how are you doing", "how's it going",
        "what's up", "good morning", "good afternoon", "good evening", "thanks", "thank you",
    )
    return trimmed in greetings || trimmed.startsWith("hi ") || trimmed.startsWith("hello ") || trimmed.startsWith("hey ")
}

/**
 * Composes the fitness coach settings UI element.
 *
 * @param autoGoals Whether to apply auto goals.
 * @param onAutoGoalsChange Callback invoked when auto goals change changes or occurs.
 * @param onClearHistory Callback invoked when clear history is requested.
 * @param onManageBilling Callback invoked when manage billing changes or occurs.
 * @param onDismiss Callback invoked when the dialog or sheet is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FitnessCoachSettings(
    autoGoals: Boolean,
    onAutoGoalsChange: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onManageBilling: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        modifier = Modifier.testTag("fitness_coach_settings_sheet"),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.fitness_coach_settings),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(autoGoals, role = Role.Switch, onValueChange = onAutoGoalsChange)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.fitness_coach_auto_goals),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        stringResource(R.string.fitness_coach_auto_goals_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Switch(checked = autoGoals, onCheckedChange = null)
            }
            HorizontalDivider()
            OutlinedButton(
                onClick = onClearHistory,
                modifier = Modifier.fillMaxWidth().testTag("fitness_coach_clear_history"),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.fitness_coach_clear_history))
            }
            if (onManageBilling != null) {
                OutlinedButton(
                    onClick = onManageBilling,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Outlined.Settings, contentDescription = null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.fitness_coach_manage))
                }
            }
            Text(
                stringResource(R.string.fitness_coach_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * Composes the fitness coach locked UI element.
 *
 * @param message Chat message or remote payload.
 * @param actions Action or callback executed for actions.
 */
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
