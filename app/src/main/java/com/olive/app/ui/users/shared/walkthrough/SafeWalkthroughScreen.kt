// Safe-app users and safe contacts: two-view Table of Contents hub and focused interactive feature walkthrough.
package com.olive.app.ui.users.shared.walkthrough

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.ui.shared.Cream
import com.olive.app.ui.shared.DarkOlive
import com.olive.app.ui.shared.Ink
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveButton
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.OliveIconButton
import com.olive.app.ui.shared.OliveOutlinedButton
import com.olive.app.ui.shared.OliveOutlinedCard
import com.olive.app.ui.shared.OliveTextButton
import com.olive.app.ui.shared.PaleOlive
import com.olive.app.ui.shared.SageGreen
import com.olive.app.ui.users.shared.navigation.FormColumn
import com.olive.app.ui.users.shared.navigation.Heading
import com.olive.app.ui.users.shared.navigation.SessionViewModel

/**
 * Renders the Walkthrough experience: a categorized Table of Contents Hub by default,
 * or a focused interactive Feature Walkthrough when a topic or [initialTopicId] is selected.
 *
 * @param session Shared session view model owning application state and navigation.
 * @param initialTopicId Optional topic deep link passed from a first-visit contextual card.
 */
@Composable
internal fun SafeWalkthroughScreen(session: SessionViewModel, initialTopicId: String? = null) {
    val topics = remember(session.accountRole) { walkthroughTopicsForRole(session.accountRole) }
    val groups = remember(topics) { walkthroughTopicGroups(topics) }
    val initialSelected = remember(topics, initialTopicId) {
        initialTopicId?.takeIf { id -> topics.any { it.id == id } }
    }
    var selectedTopicId by rememberSaveable(session.accountRole, initialTopicId) {
        mutableStateOf(initialSelected)
    }
    val completedDemos = remember { mutableStateMapOf<String, Boolean>() }
    val activeTopic = topics.firstOrNull { it.id == selectedTopicId }

    BackHandler(enabled = activeTopic != null && initialTopicId == null) {
        selectedTopicId = null
    }

    FormColumn(scrollResetKey = activeTopic?.id ?: "toc") {
        Row(
            Modifier.fillMaxWidth().testTag("walkthrough_screen"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OliveIconButton(
                onClick = session.navigation::back,
                modifier = Modifier.testTag("walkthrough_back"),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_previous_safe_page))
            }
            Text(
                stringResource(R.string.support_walkthrough),
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }

        if (activeTopic == null) {
            WalkthroughTocHub(
                topics = topics,
                groups = groups,
                completedDemos = completedDemos,
                onSelectTopic = { selectedTopicId = it.id },
            )
        } else {
            val activeIndex = topics.indexOfFirst { it.id == activeTopic.id }.coerceAtLeast(0)
            WalkthroughFeatureDetail(
                topic = activeTopic,
                topicIndex = activeIndex,
                totalTopics = topics.size,
                demoCompleted = completedDemos[activeTopic.id] == true,
                onMarkCompleted = { completedDemos[activeTopic.id] = true },
                onBackToToc = { selectedTopicId = null },
                onPreviousTopic = {
                    val prev = nextWalkthroughTopicIndex(activeIndex, topics.size, -1)
                    selectedTopicId = topics[prev].id
                },
                onNextTopic = {
                    val next = nextWalkthroughTopicIndex(activeIndex, topics.size, 1)
                    selectedTopicId = topics[next].id
                },
                onOpenFeature = { session.navigation.openSafePage(activeTopic.targetPage) },
            )
        }
    }
}

/**
 * Renders the Walkthrough Table of Contents Hub with overall exploration progress,
 * a Start Full Walkthrough action, and categorized feature selector cards.
 *
 * @param topics Flat ordered list of topics available to the current user role.
 * @param groups Topics grouped by [WalkthroughCategory].
 * @param completedDemos Map recording which topics the user has practiced in this session.
 * @param onSelectTopic Callback opening a selected topic's interactive guide.
 */
@Composable
private fun WalkthroughTocHub(
    topics: List<WalkthroughTopic>,
    groups: List<WalkthroughTopicGroup>,
    completedDemos: Map<String, Boolean>,
    onSelectTopic: (WalkthroughTopic) -> Unit,
) {
    val exploredCount = topics.count { completedDemos[it.id] == true }

    Heading(
        stringResource(R.string.walkthrough_toc_title),
        stringResource(R.string.walkthrough_subtitle),
    )

    OliveOutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.walkthrough_toc_subtitle, topics.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = Ink,
                    modifier = Modifier.weight(1f),
                )
                DemoStatusChip(
                    stringResource(R.string.walkthrough_progress_count, exploredCount, topics.size),
                    highlighted = exploredCount > 0,
                )
            }
            LinearProgressIndicator(
                progress = { if (topics.isEmpty()) 0f else exploredCount / topics.size.toFloat() },
                modifier = Modifier.fillMaxWidth(),
                color = OliveGreen,
                trackColor = SageGreen,
            )
            OliveButton(
                onClick = { topics.firstOrNull()?.let(onSelectTopic) },
                modifier = Modifier.fillMaxWidth().testTag("walkthrough_start_tour"),
            ) {
                Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.walkthrough_start_tour), modifier = Modifier.padding(start = 6.dp))
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().testTag("walkthrough_toc"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        var displayNumber = 0
        groups.forEach { group ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(group.category.labelRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = DarkOlive,
                    modifier = Modifier.semantics { heading() },
                )
                group.topics.forEach { topic ->
                    displayNumber += 1
                    WalkthroughTocCard(
                        index = displayNumber,
                        topic = topic,
                        isDone = completedDemos[topic.id] == true,
                        onSelect = { onSelectTopic(topic) },
                    )
                }
            }
        }
    }
}

/**
 * Renders an individual clickable feature card inside the Table of Contents Hub.
 *
 * @param index One-based display order index.
 * @param topic Feature metadata.
 * @param isDone Whether the user has completed interactive practice for [topic].
 * @param onSelect Callback opening [topic].
 */
@Composable
private fun WalkthroughTocCard(
    index: Int,
    topic: WalkthroughTopic,
    isDone: Boolean,
    onSelect: () -> Unit,
) {
    OliveOutlinedCard(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth().testTag("walkthrough_toc_${topic.id}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isDone) OliveGreen else SageGreen,
                contentColor = if (isDone) Cream else DarkOlive,
            ) {
                Icon(
                    if (isDone) Icons.Outlined.CheckCircle else topic.icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "$index. ${stringResource(topic.titleRes)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                )
                Text(
                    stringResource(topic.summaryRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
            if (isDone) {
                DemoStatusChip(stringResource(R.string.walkthrough_explored), highlighted = true)
            } else {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = OliveGreen, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * Renders a focused feature walkthrough with its live interactive sandbox, numbered steps,
 * direct shortcut to the live screen, and previous/TOC/next topic navigation.
 *
 * @param topic Active topic metadata.
 * @param topicIndex Zero-based index of [topic].
 * @param totalTopics Total number of role-visible topics.
 * @param demoCompleted Whether the user has practiced [topic]'s sandbox.
 * @param onMarkCompleted Marks [topic] as practiced when the user interacts with its demo.
 * @param onBackToToc Returns to the Table of Contents Hub.
 * @param onPreviousTopic Moves to the previous feature guide.
 * @param onNextTopic Moves to the next feature guide.
 * @param onOpenFeature Jumps to the real screen for [topic].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WalkthroughFeatureDetail(
    topic: WalkthroughTopic,
    topicIndex: Int,
    totalTopics: Int,
    demoCompleted: Boolean,
    onMarkCompleted: () -> Unit,
    onBackToToc: () -> Unit,
    onPreviousTopic: () -> Unit,
    onNextTopic: () -> Unit,
    onOpenFeature: () -> Unit,
) {
    val targetName = stringResource(topic.targetLabelRes)

    Column(
        modifier = Modifier.fillMaxWidth().testTag("walkthrough_active_feature"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OliveTextButton(
                onClick = onBackToToc,
                modifier = Modifier.testTag("walkthrough_back_to_toc"),
            ) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.walkthrough_back_to_toc), modifier = Modifier.padding(start = 6.dp))
            }
            DemoStatusChip(
                stringResource(R.string.walkthrough_feature_counter, topicIndex + 1, totalTopics),
                highlighted = false,
            )
        }

        Heading(
            stringResource(topic.titleRes),
            stringResource(topic.summaryRes),
        )

        OliveOutlinedCard(
            modifier = Modifier.fillMaxWidth().testTag("walkthrough_demo_card"),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.walkthrough_demo_heading),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                )
                Text(
                    stringResource(R.string.walkthrough_demo_badge),
                    style = MaterialTheme.typography.labelLarge,
                    color = DarkOlive,
                )
                WalkthroughInteractiveDemo(
                    topicId = topic.id,
                    onPracticeCompleted = onMarkCompleted,
                )
                if (demoCompleted) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = PaleOlive,
                        border = BorderStroke(1.dp, OliveGreen),
                        modifier = Modifier.fillMaxWidth().testTag("walkthrough_demo_feedback"),
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Outlined.CheckCircle, null, tint = OliveGreen, modifier = Modifier.size(18.dp))
                            Text(
                                stringResource(R.string.walkthrough_demo_done, targetName),
                                style = MaterialTheme.typography.bodySmall,
                                color = DarkOlive,
                            )
                        }
                    }
                }
            }
        }

        OliveOutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.walkthrough_how_it_works),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                )
                topic.stepResIds.forEachIndexed { idx, stepRes ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        DemoStatusChip(
                            stringResource(R.string.walkthrough_step_number, idx + 1),
                            highlighted = true,
                        )
                        Text(
                            stringResource(stepRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        OliveButton(
            onClick = onOpenFeature,
            modifier = Modifier.fillMaxWidth().testTag("walkthrough_open_feature"),
        ) {
            Text(stringResource(R.string.walkthrough_open_feature, targetName))
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, modifier = Modifier.padding(start = 6.dp).size(18.dp))
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OliveOutlinedButton(
                onClick = onPreviousTopic,
                enabled = topicIndex > 0,
                modifier = Modifier.testTag("walkthrough_prev_topic"),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.walkthrough_previous_topic), modifier = Modifier.padding(start = 6.dp))
            }
            OliveOutlinedButton(
                onClick = onBackToToc,
                modifier = Modifier.testTag("walkthrough_toc_button"),
            ) {
                Text(stringResource(R.string.walkthrough_back_to_toc))
            }
            OliveButton(
                onClick = onNextTopic,
                enabled = topicIndex < totalTopics - 1,
                modifier = Modifier.testTag("walkthrough_next_topic"),
            ) {
                Text(stringResource(R.string.walkthrough_next_topic))
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, modifier = Modifier.padding(start = 6.dp).size(16.dp))
            }
        }
    }
}
