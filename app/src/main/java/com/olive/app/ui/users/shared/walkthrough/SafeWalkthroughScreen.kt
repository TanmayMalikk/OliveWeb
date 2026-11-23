// Safe-app users and safe contacts: interactive feature walkthrough with table of contents and practice sandbox.
package com.olive.app.ui.users.shared.walkthrough

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.ui.shared.CardTintEnabled
import com.olive.app.ui.shared.Cream
import com.olive.app.ui.shared.DarkOlive
import com.olive.app.ui.shared.Ink
import com.olive.app.ui.shared.LightSage
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveOutlinedCard as OutlinedCard
import com.olive.app.ui.shared.OliveTextButton as TextButton
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.PaleOlive
import com.olive.app.ui.shared.SageGreen
import com.olive.app.ui.users.shared.navigation.FormColumn
import com.olive.app.ui.users.shared.navigation.Heading
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionViewModel

internal data class WalkthroughTopic(
    val id: String,
    val titleRes: Int,
    val summaryRes: Int,
    val stepResIds: List<Int>,
    val demoPromptRes: Int,
    val targetPage: SafePage,
    val targetLabelRes: Int,
    val icon: ImageVector,
    val userOnly: Boolean = false,
)

private val allWalkthroughTopics = listOf(
    WalkthroughTopic(
        id = "cover_entry",
        titleRes = R.string.walkthrough_topic_cover_title,
        summaryRes = R.string.walkthrough_topic_cover_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_cover_step_1,
            R.string.walkthrough_topic_cover_step_2,
            R.string.walkthrough_topic_cover_step_3,
        ),
        demoPromptRes = R.string.walkthrough_topic_cover_demo,
        targetPage = SafePage.Settings,
        targetLabelRes = R.string.settings,
        icon = Icons.Outlined.Lock,
        userOnly = true,
    ),
    WalkthroughTopic(
        id = "quick_exit",
        titleRes = R.string.walkthrough_topic_exit_title,
        summaryRes = R.string.walkthrough_topic_exit_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_exit_step_1,
            R.string.walkthrough_topic_exit_step_2,
            R.string.walkthrough_topic_exit_step_3,
        ),
        demoPromptRes = R.string.walkthrough_topic_exit_demo,
        targetPage = SafePage.Settings,
        targetLabelRes = R.string.settings,
        icon = Icons.AutoMirrored.Outlined.ExitToApp,
        userOnly = true,
    ),
    WalkthroughTopic(
        id = "safety_plan",
        titleRes = R.string.walkthrough_topic_plan_title,
        summaryRes = R.string.walkthrough_topic_plan_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_plan_step_1,
            R.string.walkthrough_topic_plan_step_2,
            R.string.walkthrough_topic_plan_step_3,
        ),
        demoPromptRes = R.string.walkthrough_topic_plan_demo,
        targetPage = SafePage.SafetyPlan,
        targetLabelRes = R.string.support_safety_plan,
        icon = Icons.Outlined.HealthAndSafety,
    ),
    WalkthroughTopic(
        id = "notes_incidents",
        titleRes = R.string.walkthrough_topic_notes_title,
        summaryRes = R.string.walkthrough_topic_notes_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_notes_step_1,
            R.string.walkthrough_topic_notes_step_2,
            R.string.walkthrough_topic_notes_step_3,
        ),
        demoPromptRes = R.string.walkthrough_topic_notes_demo,
        targetPage = SafePage.Notes,
        targetLabelRes = R.string.notes_incidents,
        icon = Icons.Outlined.Description,
    ),
    WalkthroughTopic(
        id = "documents_ocr",
        titleRes = R.string.walkthrough_topic_docs_title,
        summaryRes = R.string.walkthrough_topic_docs_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_docs_step_1,
            R.string.walkthrough_topic_docs_step_2,
            R.string.walkthrough_topic_docs_step_3,
        ),
        demoPromptRes = R.string.walkthrough_topic_docs_demo,
        targetPage = SafePage.Documents,
        targetLabelRes = R.string.support_documents,
        icon = Icons.Outlined.FolderOpen,
    ),
    WalkthroughTopic(
        id = "messaging_calls",
        titleRes = R.string.walkthrough_topic_chat_title,
        summaryRes = R.string.walkthrough_topic_chat_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_chat_step_1,
            R.string.walkthrough_topic_chat_step_2,
            R.string.walkthrough_topic_chat_step_3,
        ),
        demoPromptRes = R.string.walkthrough_topic_chat_demo,
        targetPage = SafePage.Messages,
        targetLabelRes = R.string.messages,
        icon = Icons.Outlined.ChatBubbleOutline,
    ),
    WalkthroughTopic(
        id = "location_map",
        titleRes = R.string.walkthrough_topic_location_title,
        summaryRes = R.string.walkthrough_topic_location_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_location_step_1,
            R.string.walkthrough_topic_location_step_2,
            R.string.walkthrough_topic_location_step_3,
        ),
        demoPromptRes = R.string.walkthrough_topic_location_demo,
        targetPage = SafePage.Location,
        targetLabelRes = R.string.location_map,
        icon = Icons.Outlined.LocationOn,
    ),
    WalkthroughTopic(
        id = "staff_help",
        titleRes = R.string.walkthrough_topic_staff_title,
        summaryRes = R.string.walkthrough_topic_staff_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_staff_step_1,
            R.string.walkthrough_topic_staff_step_2,
            R.string.walkthrough_topic_staff_step_3,
        ),
        demoPromptRes = R.string.walkthrough_topic_staff_demo,
        targetPage = SafePage.StaffHelp,
        targetLabelRes = R.string.support_staff_help,
        icon = Icons.Outlined.SupportAgent,
        userOnly = true,
    ),
    WalkthroughTopic(
        id = "emergency_report",
        titleRes = R.string.walkthrough_topic_emergency_title,
        summaryRes = R.string.walkthrough_topic_emergency_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_emergency_step_1,
            R.string.walkthrough_topic_emergency_step_2,
            R.string.walkthrough_topic_emergency_step_3,
        ),
        demoPromptRes = R.string.walkthrough_topic_emergency_demo,
        targetPage = SafePage.Documents,
        targetLabelRes = R.string.support_documents,
        icon = Icons.Outlined.WarningAmber,
        userOnly = true,
    ),
    WalkthroughTopic(
        id = "hotlines_contacts",
        titleRes = R.string.walkthrough_topic_hotlines_title,
        summaryRes = R.string.walkthrough_topic_hotlines_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_hotlines_step_1,
            R.string.walkthrough_topic_hotlines_step_2,
            R.string.walkthrough_topic_hotlines_step_3,
        ),
        demoPromptRes = R.string.walkthrough_topic_hotlines_demo,
        targetPage = SafePage.Hotlines,
        targetLabelRes = R.string.support_hotlines,
        icon = Icons.Outlined.PhoneInTalk,
        userOnly = true,
    ),
    WalkthroughTopic(
        id = "activity_settings",
        titleRes = R.string.walkthrough_topic_activity_title,
        summaryRes = R.string.walkthrough_topic_activity_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_activity_step_1,
            R.string.walkthrough_topic_activity_step_2,
            R.string.walkthrough_topic_activity_step_3,
        ),
        demoPromptRes = R.string.walkthrough_topic_activity_demo,
        targetPage = SafePage.Activity,
        targetLabelRes = R.string.account_activity,
        icon = Icons.Outlined.History,
        userOnly = true,
    ),
)

/**
 * Filters the walkthrough table of contents to the features supported by the given [role].
 *
 * @param role Account role viewing the walkthrough.
 * @return Ordered list of [WalkthroughTopic] items for that role.
 */
internal fun walkthroughTopicsForRole(role: AccountRole): List<WalkthroughTopic> =
    if (role == AccountRole.SAFE_CONTACT) allWalkthroughTopics.filterNot(WalkthroughTopic::userOnly)
    else allWalkthroughTopics

/**
 * Clamps a requested step index into the valid step range `[0, totalSteps - 1]`.
 *
 * @param stepIndex Zero-based step index requested by the user.
 * @param totalSteps Total number of steps in the topic.
 * @return Valid zero-based step index.
 */
internal fun clampWalkthroughStep(stepIndex: Int, totalSteps: Int): Int =
    if (totalSteps <= 1) 0 else stepIndex.coerceIn(0, totalSteps - 1)

/**
 * Computes the next topic index when moving forward or backward through the table of contents.
 *
 * @param currentIndex Current zero-based topic index.
 * @param totalTopics Total number of available topics.
 * @param delta Step direction (positive for next, negative for previous).
 * @return Clamped zero-based topic index.
 */
internal fun nextWalkthroughTopicIndex(currentIndex: Int, totalTopics: Int, delta: Int): Int =
    if (totalTopics <= 1) 0 else (currentIndex + delta).coerceIn(0, totalTopics - 1)

/**
 * Renders the interactive Walkthrough screen with a Table of Contents, step-by-step instructions,
 * hands-on practice sandbox card, and direct jump links to each live feature screen.
 *
 * @param session Shared session view model owning application state and navigation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SafeWalkthroughScreen(session: SessionViewModel) {
    val topics = remember(session.accountRole) { walkthroughTopicsForRole(session.accountRole) }
    var selectedTopicId by rememberSaveable(session.accountRole) {
        mutableStateOfSafeTopicId(topics.first().id)
    }
    val selectedIndex = topics.indexOfFirst { it.id == selectedTopicId }.takeIf { it >= 0 } ?: 0
    val activeTopic = topics[selectedIndex]
    var currentStep by rememberSaveable(activeTopic.id) { mutableIntStateOf(0) }
    val clampedStep = clampWalkthroughStep(currentStep, activeTopic.stepResIds.size)
    val completedDemos = remember { mutableStateMapOf<String, Boolean>() }
    val demoCompleted = completedDemos[activeTopic.id] == true

    FormColumn(scrollResetKey = activeTopic.id) {
        Row(
            Modifier.fillMaxWidth().testTag("walkthrough_screen"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = session.navigation::back,
                modifier = Modifier.testTag("walkthrough_back"),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_previous_safe_page))
            }
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = OliveGreen)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.support_walkthrough),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(
            stringResource(R.string.walkthrough_subtitle),
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
        )

        // Active Feature Interactive Demo & Step-by-Step Guide Card
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("walkthrough_active_feature"),
            shape = RoundedCornerShape(22.dp),
            color = CardTintEnabled,
            border = BorderStroke(1.5.dp, OliveGreen),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = SageGreen,
                        contentColor = DarkOlive,
                    ) {
                        Icon(
                            activeTopic.icon,
                            contentDescription = null,
                            modifier = Modifier.padding(10.dp).size(24.dp),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(activeTopic.titleRes),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            stringResource(activeTopic.summaryRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                        )
                    }
                }

                HorizontalDivider()

                // Step progress header and numbered step pills
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(
                            R.string.walkthrough_step_progress,
                            clampedStep + 1,
                            activeTopic.stepResIds.size,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = OliveGreen,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("walkthrough_step_indicator")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        activeTopic.stepResIds.indices.forEach { idx ->
                            val isCurrent = idx == clampedStep
                            OutlinedButton(
                                onClick = { currentStep = idx },
                                modifier = Modifier.testTag("walkthrough_step_pill_${idx + 1}")
                                    .semantics { selected = isCurrent },
                            ) {
                                Text(
                                    stringResource(
                                        R.string.walkthrough_step_progress,
                                        idx + 1,
                                        activeTopic.stepResIds.size,
                                    )
                                )
                            }
                        }
                    }
                }

                // Current step instruction box
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("walkthrough_step_body"),
                    shape = RoundedCornerShape(16.dp),
                    color = PaleOlive,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(
                        stringResource(activeTopic.stepResIds[clampedStep]),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Ink,
                        modifier = Modifier.padding(16.dp),
                    )
                }

                // Step navigation buttons
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { currentStep = clampWalkthroughStep(clampedStep - 1, activeTopic.stepResIds.size) },
                        enabled = clampedStep > 0,
                        modifier = Modifier.weight(1f).testTag("walkthrough_prev_step"),
                    ) {
                        Text(stringResource(R.string.walkthrough_previous_step))
                    }
                    Button(
                        onClick = { currentStep = clampWalkthroughStep(clampedStep + 1, activeTopic.stepResIds.size) },
                        enabled = clampedStep < activeTopic.stepResIds.size - 1,
                        modifier = Modifier.weight(1f).testTag("walkthrough_next_step"),
                    ) {
                        Text(stringResource(R.string.walkthrough_next_step))
                    }
                }

                // Interactive Practice Sandbox Card
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("walkthrough_demo_card"),
                    shape = RoundedCornerShape(16.dp),
                    color = Cream,
                    border = BorderStroke(1.dp, OliveGreen),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                if (demoCompleted) Icons.Outlined.CheckCircle else Icons.Outlined.PlayArrow,
                                contentDescription = null,
                                tint = OliveGreen,
                            )
                            Text(
                                stringResource(R.string.walkthrough_demo_heading),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            stringResource(R.string.walkthrough_demo_badge),
                            style = MaterialTheme.typography.labelMedium,
                            color = OliveGreen,
                        )
                        Text(
                            stringResource(activeTopic.demoPromptRes),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (demoCompleted) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().testTag("walkthrough_demo_feedback"),
                                shape = RoundedCornerShape(12.dp),
                                color = LightSage,
                            ) {
                                Text(
                                    stringResource(
                                        R.string.walkthrough_demo_done,
                                        stringResource(activeTopic.targetLabelRes),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = DarkOlive,
                                    modifier = Modifier.padding(12.dp)
                                        .semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = { completedDemos[activeTopic.id] = true },
                                enabled = !demoCompleted,
                                modifier = Modifier.weight(1f).testTag("walkthrough_demo_action"),
                            ) {
                                Text(stringResource(R.string.walkthrough_demo_action))
                            }
                            if (demoCompleted) {
                                OutlinedButton(
                                    onClick = { completedDemos[activeTopic.id] = false },
                                    modifier = Modifier.testTag("walkthrough_demo_reset"),
                                ) {
                                    Icon(Icons.Outlined.Refresh, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.walkthrough_demo_reset))
                                }
                            }
                        }
                    }
                }

                // Direct "Open <Feature>" action button
                val targetName = stringResource(activeTopic.targetLabelRes)
                Button(
                    onClick = { session.navigation.openSafePage(activeTopic.targetPage) },
                    modifier = Modifier.fillMaxWidth().testTag("walkthrough_open_feature"),
                ) {
                    Text(stringResource(R.string.walkthrough_open_feature, targetName))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null)
                }

                // Topic-to-topic sequential navigation
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        onClick = {
                            val prevIdx = nextWalkthroughTopicIndex(selectedIndex, topics.size, -1)
                            selectedTopicId = topics[prevIdx].id
                            currentStep = 0
                        },
                        enabled = selectedIndex > 0,
                        modifier = Modifier.weight(1f).testTag("walkthrough_prev_topic"),
                    ) {
                        Text(stringResource(R.string.walkthrough_previous_topic))
                    }
                    TextButton(
                        onClick = {
                            val nextIdx = nextWalkthroughTopicIndex(selectedIndex, topics.size, 1)
                            selectedTopicId = topics[nextIdx].id
                            currentStep = 0
                        },
                        enabled = selectedIndex < topics.lastIndex,
                        modifier = Modifier.weight(1f).testTag("walkthrough_next_topic"),
                    ) {
                        Text(stringResource(R.string.walkthrough_next_topic))
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Table of Contents section
        Heading(
            stringResource(R.string.walkthrough_toc_title),
            stringResource(R.string.walkthrough_toc_subtitle, topics.size),
        )
        Column(
            modifier = Modifier.fillMaxWidth().testTag("walkthrough_toc"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            topics.forEachIndexed { index, topic ->
                val isSelected = topic.id == activeTopic.id
                val isDone = completedDemos[topic.id] == true
                WalkthroughTocCard(
                    index = index + 1,
                    topic = topic,
                    isSelected = isSelected,
                    isDone = isDone,
                    onSelect = {
                        selectedTopicId = topic.id
                        currentStep = 0
                    },
                )
            }
        }
    }
}

/**
 * Creates a mutable state holder for the selected walkthrough topic ID.
 *
 * @param initial Initial topic ID.
 * @return Mutable state containing the active topic ID.
 */
private fun mutableStateOfSafeTopicId(initial: String) =
    androidx.compose.runtime.mutableStateOf(initial)

/**
 * Renders an individual selectable topic row in the Walkthrough Table of Contents.
 *
 * @param index One-based topic number in the table of contents.
 * @param topic Walkthrough topic metadata.
 * @param isSelected Whether this topic is currently active in the viewer.
 * @param isDone Whether the user has completed the practice demo for this topic.
 * @param onSelect Callback invoked when the user selects this topic.
 */
@Composable
private fun WalkthroughTocCard(
    index: Int,
    topic: WalkthroughTopic,
    isSelected: Boolean,
    isDone: Boolean,
    onSelect: () -> Unit,
) {
    OutlinedCard(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth()
            .testTag("walkthrough_toc_${topic.id}")
            .semantics { selected = isSelected },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) OliveGreen else SageGreen,
                contentColor = if (isSelected) Cream else DarkOlive,
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
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isSelected) OliveGreen else Ink,
                )
                Text(
                    stringResource(topic.summaryRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
            if (isSelected) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = PaleOlive,
                    border = BorderStroke(1.dp, OliveGreen),
                ) {
                    Text(
                        stringResource(R.string.walkthrough_topic_selected),
                        style = MaterialTheme.typography.labelSmall,
                        color = OliveGreen,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
