// Safe-app users and safe contacts: floating OliveGreen coachmark popup with directional arrows over live app screens.
package com.olive.app.ui.users.shared.walkthrough

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.North
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.South
import androidx.compose.material.icons.outlined.SouthEast
import androidx.compose.material.icons.outlined.SouthWest
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.ui.shared.OliveButton
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.OlivePrivateLayout
import com.olive.app.ui.shared.OliveTextButton

internal val LocalWalkthroughTour = staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * Renders the floating OliveGreen walkthrough coachmark popup above live application screens
 * without covering Quick Exit or blocking real screen interactions.
 *
 * @param role Account role used to filter walkthrough topics.
 * @param activeTopicId Active coachmark topic ID, or `null` when no tour is running.
 * @param showBottomControls Whether floating corner controls (`Quick exit` / `+`) occupy the bottom edge.
 * @param onAdvance Callback invoked with the next topic ID or `null` when finishing the tour.
 * @param onOpenContents Callback returning the user to the Walkthrough Table of Contents.
 * @param onSkip Callback dismissing the coachmark popup in place.
 * @param modifier Modifier applied to the overlay container.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WalkthroughCoachmarkOverlay(
    role: AccountRole,
    activeTopicId: String?,
    showBottomControls: Boolean,
    onAdvance: (String?) -> Unit,
    onOpenContents: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (activeTopicId == null) return
    val topics = remember(role) { walkthroughTopicsForRole(role) }
    val activeIndex = topics.indexOfFirst { it.id == activeTopicId }
    if (activeIndex < 0) return
    val topic = topics[activeIndex]
    val prevId = nextWalkthroughTourTopicId(topics, topic.id, -1)
    val nextId = nextWalkthroughTourTopicId(topics, topic.id, 1)
    val arrowIcon = when (topic.arrow) {
        WalkthroughArrow.SouthEast -> Icons.Outlined.SouthEast
        WalkthroughArrow.SouthWest -> Icons.Outlined.SouthWest
        WalkthroughArrow.NorthEast -> Icons.Outlined.NorthEast
        WalkthroughArrow.North -> Icons.Outlined.North
        WalkthroughArrow.South -> Icons.Outlined.South
    }
    val alignment = when (topic.anchor) {
        WalkthroughAnchor.BottomEnd -> Alignment.BottomEnd
        WalkthroughAnchor.BottomStart -> Alignment.BottomStart
        WalkthroughAnchor.TopEnd -> Alignment.TopEnd
        WalkthroughAnchor.TopCenter -> Alignment.TopCenter
        WalkthroughAnchor.BottomCenter -> Alignment.BottomCenter
    }
    val bottomPadding = if (showBottomControls) OlivePrivateLayout.FloatingActionClearance + 8.dp else 16.dp
    val titleText = stringResource(topic.titleRes)

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 64.dp, bottom = bottomPadding),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = OliveGreen,
            contentColor = Color.White,
            shadowElevation = 14.dp,
            border = BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)),
            modifier = Modifier
                .align(alignment)
                .widthIn(min = 276.dp, max = 356.dp)
                .testTag("walkthrough_active_feature")
                .semantics { paneTitle = titleText },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.walkthrough_tour_step_counter, activeIndex + 1, topics.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                    OliveTextButton(
                        onClick = onSkip,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        modifier = Modifier.testTag("walkthrough_tour_skip"),
                    ) {
                        Text(stringResource(R.string.walkthrough_tour_skip))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        titleText,
                        fontSize = 20.sp,
                        lineHeight = 25.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        arrowIcon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp).testTag("walkthrough_tour_arrow"),
                    )
                }
                Text(
                    stringResource(topic.stepResIds.first()),
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    color = Color.White,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OliveTextButton(
                        onClick = onOpenContents,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        modifier = Modifier.testTag("walkthrough_tour_toc"),
                    ) {
                        Text(stringResource(R.string.walkthrough_tour_contents))
                    }
                    if (prevId != null) {
                        OliveTextButton(
                            onClick = { onAdvance(prevId) },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                            modifier = Modifier.testTag("walkthrough_tour_prev"),
                        ) {
                            Text(stringResource(R.string.walkthrough_tour_back))
                        }
                    }
                    OliveButton(
                        onClick = { onAdvance(nextId) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = OliveGreen,
                        ),
                        modifier = Modifier.testTag("walkthrough_tour_next"),
                    ) {
                        Text(
                            stringResource(
                                if (nextId == null) R.string.walkthrough_tour_done else R.string.walkthrough_tour_next,
                            ),
                        )
                        if (nextId != null) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.padding(start = 4.dp).size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
