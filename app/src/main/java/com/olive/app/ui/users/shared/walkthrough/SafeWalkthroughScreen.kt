// Safe-app users and safe contacts: Walkthrough Table of Contents launching live guided in-app tours.
package com.olive.app.ui.users.shared.walkthrough

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.ui.shared.DarkOlive
import com.olive.app.ui.shared.Ink
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveButton
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.OliveIconButton
import com.olive.app.ui.shared.OliveOutlinedCard
import com.olive.app.ui.shared.PaleOlive
import com.olive.app.ui.shared.SageGreen
import com.olive.app.ui.users.shared.navigation.FormColumn
import com.olive.app.ui.users.shared.navigation.Heading
import com.olive.app.ui.users.shared.navigation.SessionViewModel

/**
 * Renders the Walkthrough Table of Contents screen. Selecting a feature or tapping Start full
 * walkthrough opens that live feature screen with its multi-step floating [WalkthroughCoachmarkOverlay].
 *
 * @param session Shared session view model owning application state and navigation.
 * @param initialTopicId Unused legacy parameter retained for signature compatibility.
 */
@Composable
internal fun SafeWalkthroughScreen(
    session: SessionViewModel,
    @Suppress("UNUSED_PARAMETER") initialTopicId: String? = null,
) {
    val topics = remember(session.accountRole) { walkthroughTopicsForRole(session.accountRole) }
    val steps = remember(session.accountRole) { walkthroughTourStepsForRole(session.accountRole) }
    val groups = remember(topics) { walkthroughTopicGroups(topics) }
    val startTour = LocalWalkthroughTour.current

    FormColumn(scrollResetKey = "walkthrough_toc") {
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

        Heading(
            stringResource(R.string.walkthrough_toc_title),
            stringResource(R.string.walkthrough_subtitle),
        )

        OliveOutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.walkthrough_toc_subtitle, groups.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = Ink,
                )
                OliveButton(
                    onClick = { steps.firstOrNull()?.let { startTour?.invoke(it.id) } },
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
                            stepCount = walkthroughStepCountForTopic(session.accountRole, topic.id),
                            onSelect = { startTour?.invoke(topic.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Renders an individual clickable feature card in the Walkthrough Table of Contents.
 *
 * @param index One-based display order index.
 * @param topic Feature metadata.
 * @param stepCount Number of live coachmark popups in [topic].
 * @param onSelect Callback launching the live guided walkthrough at [topic].
 */
@Composable
private fun WalkthroughTocCard(
    index: Int,
    topic: WalkthroughTopic,
    stepCount: Int,
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
                color = SageGreen,
                contentColor = DarkOlive,
            ) {
                Icon(
                    topic.icon,
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
                if (stepCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = PaleOlive,
                        border = BorderStroke(1.dp, SageGreen),
                    ) {
                        Text(
                            stringResource(R.string.walkthrough_toc_step_count, stepCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = DarkOlive,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = OliveGreen,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
