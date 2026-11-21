package com.olive.app.ui.covers.fitness

import com.olive.app.ui.shared.OliveFilledIconButton as FilledIconButton
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.R
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * Draws the gradient and heading within the selected fitness tab.
 *
 * @param onSettings Opens coach settings when available.
 * @param content The conversation, loading state, or subscription controls.
 */
@Composable
internal fun FitnessCoachFrame(
    onSettings: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().testTag("fitness_coach_screen"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.fitness_coach_title),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge.balancedHeadline,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                if (onSettings != null) {
                    IconButton(onClick = onSettings) {
                        Icon(
                            Icons.Outlined.Settings,
                            stringResource(R.string.fitness_coach_settings),
                            tint = colors.onSurface,
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) { content() }
        }
    }
}

/**
 * Shows the conversation, suggested goals, and message composer.
 *
 * @param turns Saved messages in display order.
 * @param pendingMessage User message awaiting a reply.
 * @param draft Current message draft.
 * @param onDraftChange Updates or pre-fills the draft.
 * @param onSend Sends the current draft.
 * @param billingBusy Whether subscription work is in progress.
 * @param error Resource ID for an error, if present.
 * @param appliedGoals Goals saved by the app.
 * @param proposed Goals awaiting approval.
 * @param onApplyGoals Saves the proposed goals.
 */
@Composable
internal fun FitnessCoachChat(
    turns: List<CoachTurn>,
    pendingMessage: String?,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    billingBusy: Boolean,
    error: Int?,
    appliedGoals: Map<String, Int>,
    proposed: Map<String, Int>,
    onApplyGoals: () -> Unit,
) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = turns.lastIndex.coerceAtLeast(0))
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val itemCount = turns.size + (if (pendingMessage != null) 2 else 0) +
        (if (appliedGoals.isNotEmpty()) 1 else 0) + (if (proposed.isNotEmpty()) 1 else 0)

    LaunchedEffect(turns.size, pendingMessage, proposed, appliedGoals) {
        if (itemCount > 0) list.animateScrollToItem(itemCount - 1)
        if (pendingMessage == null) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && itemCount > 0) {
            delay(120.milliseconds)
            list.animateScrollToItem(itemCount - 1)
        }
    }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().imePadding()) {
        if (billingBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.fitness_coach_disclaimer),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = colors.error.copy(alpha = 0.8f),
            maxLines = 1,
        )
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
        ) {
            if (turns.isEmpty() && pendingMessage == null) {
                item { CoachConversationStarters(onDraftChange, enabled = !billingBusy && draft.isBlank()) }
            }
            items(turns) { turn ->
                CoachBubble(turn.role == "user") {
                    Text(if (turn.role == "user") turn.text else formatCoachReply(turn.text))
                }
            }
            if (pendingMessage != null) {
                item { CoachBubble(fromUser = true) { Text(pendingMessage) } }
                item { CoachBubble(fromUser = false) { CoachTypingIndicator() } }
            }
            if (appliedGoals.isNotEmpty()) {
                item { CoachGoalsCard(R.string.fitness_coach_goals_saved, appliedGoals) }
            }
            if (proposed.isNotEmpty()) {
                item { CoachGoalsCard(R.string.fitness_coach_review, proposed, onApplyGoals, enabled = !billingBusy) }
            }
        }
        CoachComposer(draft, onDraftChange, onSend, pendingMessage != null, billingBusy, error)
    }
}

/**
 * Offers example questions that fill the composer for review before sending.
 *
 * @param onDraftChange Copies the selected prompt into the composer.
 * @param enabled Whether the composer is available and empty.
 */
@Composable
private fun CoachConversationStarters(onDraftChange: (String) -> Unit, enabled: Boolean) {
    CoachBubble(fromUser = false) {
        Text(stringResource(R.string.fitness_coach_question))
        for (promptId in listOf(R.string.fitness_coach_prompt_workout, R.string.fitness_coach_prompt_meals)) {
            val prompt = stringResource(promptId)
            OutlinedButton(onClick = { onDraftChange(prompt) }, enabled = enabled) {
                Text(prompt)
            }
        }
    }
}

/**
 * Draws a message bubble with an AI label on responses.
 *
 * @param fromUser Whether the message was sent by the user.
 * @param content Message text or reply state.
 */
@Composable
private fun CoachBubble(fromUser: Boolean, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = if (fromUser) 36.dp else 0.dp, end = if (fromUser) 0.dp else 24.dp),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.weight(1f, fill = false).widthIn(max = 470.dp)
                .semantics(mergeDescendants = true) { },
            shape = RoundedCornerShape(
                topStart = 20.dp, topEnd = 20.dp,
                bottomStart = if (fromUser) 20.dp else 5.dp,
                bottomEnd = if (fromUser) 5.dp else 20.dp,
            ),
            color = if (fromUser) colors.primary else colors.surface,
            contentColor = if (fromUser) colors.onPrimary else colors.onSurface,
            border = if (fromUser) null else BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Column(
                Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!fromUser) {
                    Row(
                        Modifier.background(colors.primaryContainer, RoundedCornerShape(8.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(14.dp), tint = colors.onPrimaryContainer)
                        Text(
                            stringResource(R.string.fitness_coach_tab),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onPrimaryContainer,
                        )
                    }
                }
                ProvideTextStyle(MaterialTheme.typography.bodyMedium, content)
            }
        }
    }
}

/** Displays the waiting state and announces it to accessibility services. */
@Composable
private fun CoachTypingIndicator() {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { index ->
                Spacer(Modifier.size(5.dp).background(colors.primary.copy(alpha = 0.5f + index * 0.25f), CircleShape))
            }
        }
        Text(stringResource(R.string.fitness_coach_thinking), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Draws the message input, send action, and AI warning.
 *
 * @param draft Current message draft.
 * @param onDraftChange Updates the current draft.
 * @param onSend Sends the current draft.
 * @param awaitingReply Whether the coach is preparing a response.
 * @param billingBusy Whether subscription work is in progress.
 * @param error Resource ID for an error, if present.
 */
@Composable
private fun CoachComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    awaitingReply: Boolean,
    billingBusy: Boolean,
    error: Int?,
) {
    val colors = MaterialTheme.colorScheme
    val messageLabel = stringResource(R.string.message)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(awaitingReply) {
        if (awaitingReply) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    Surface(color = colors.surfaceContainerLow, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            error?.let {
                Text(
                    stringResource(it),
                    modifier = Modifier.padding(horizontal = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
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
                    modifier = Modifier.weight(1f).semantics { contentDescription = messageLabel },
                    placeholder = { Text(messageLabel) },
                    shape = RoundedCornerShape(26.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surfaceContainerLowest,
                        unfocusedContainerColor = colors.surfaceContainerLowest,
                    ),
                    readOnly = awaitingReply,
                    maxLines = 4,
                )
                FilledIconButton(
                    onClick = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        onSend()
                    },
                    enabled = !awaitingReply && !billingBusy && draft.isNotBlank(),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.fitness_coach_send))
                }
            }
        }
    }
}

/**
 * Formats coach replies with clean paragraph breaks and bullet points for workouts and meals.
 */
internal fun formatCoachReply(rawText: String): String {
    var text = rawText.trim()
    val disclaimerRegex = Regex("(?:Fitness Coach is AI, can make mistakes, and isn[’']t a fitness or nutrition expert\\.?|AI can make mistakes[.\\s·\\-]+(?:Not fitness or nutrition advice|Always stay within your body[’']s limits and consult a professional before starting something new|Stay within your body[’']s limits)\\.?)\\s*", RegexOption.IGNORE_CASE)
    text = disclaimerRegex.replace(text, "").trim()

    val dayWords = "(?:Day\\s+\\d+|Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)"

    val missingColonRegex = Regex("(?:^|([.!?\\n]))\\s*($dayWords)(?!\\s*[:·\\-])\\s+", RegexOption.IGNORE_CASE)
    text = missingColonRegex.replace(text) { match ->
        val prefix = match.groupValues[1]
        "$prefix\n\n${match.groupValues[2]}: "
    }

    val restPattern = "(?:Rest\\s+[\\d\\-]+[a-z]*|Focus on\\s+[^\\n]+|Repeat this cycle|Take rest days|Remember to rest|Stay hydrated|Drink plenty of water[^\\n]*)"
    val restRegex = Regex("(?<!\\n)\\s*($restPattern)", RegexOption.IGNORE_CASE)
    text = restRegex.replace(text) { "\n\n${it.groupValues[1]}" }

    val dayHeaderRegex = Regex("(?<!\\n)\\s*($dayWords\\s*[:·\\-])", RegexOption.IGNORE_CASE)
    text = dayHeaderRegex.replace(text) { "\n\n${it.groupValues[1]}" }

    val dayBlockRegex = Regex("($dayWords\\s*[:·\\-])\\s*([^\\n]+?)(?=(?:\\n\\n$dayWords|\\n\\n|$))", RegexOption.IGNORE_CASE)
    text = dayBlockRegex.replace(text) { match ->
        val header = match.groupValues[1].trim().let { if (it.endsWith(":")) it else "$it:" }
        val exercises = match.groupValues[2].trim()
        if ('•' in exercises || '\n' in exercises) {
            match.value
        } else {
            val items = exercises.split(Regex("[,;]\\s*(?=[A-Za-z0-9])"))
                .map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }
            if (items.size > 1) {
                val bullets = items.joinToString("\n") { "• $it" }
                "$header\n$bullets"
            } else {
                match.value
            }
        }
    }

    val mealRegex = Regex("(?<!\\n)\\s*((?:Breakfast|Lunch|Dinner|Snack(?:s)?)\\s*[:·\\-])", RegexOption.IGNORE_CASE)
    text = mealRegex.replace(text) { "\n\n• ${it.groupValues[1]}" }

    return text.replace(Regex("\\n{3,}"), "\n\n").trim()
}
