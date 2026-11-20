package com.olive.app.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.olive.app.R

internal enum class SkeletonLayout {
    Conversation, ChatList, TicketList, Contacts, Documents, Notes, Checklist, SharedPlans,
    History, Accounts, StaffWorkload, DashboardCounts, EmergencyAlerts, HotlineDirectory,
    SupportAssignment, NearbySupport, Directions, NewsFeed, NewsArticle, FitnessSummary,
    FitnessActivity, FitnessVitals, FitnessNutrition, FoodResults, Form, Action, Image, Video,
    DocumentPage, TextDocument,
}

/** Draws the shape of the incoming content without showing sample data or controls. */
@Composable
internal fun LoadingSkeleton(
    layout: SkeletonLayout,
    modifier: Modifier = Modifier.fillMaxWidth(),
    rows: Int = 2,
) {
    val label = stringResource(R.string.ui_loading)
    Column(
        modifier.testTag("loading_skeleton").semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (layout) {
            SkeletonLayout.Conversation -> repeat(rows) { index ->
                Box(Modifier.fillMaxWidth()) {
                    val bubble = Modifier.fillMaxWidth(if (index % 2 == 0) .64f else .46f)
                        .align(if (index % 2 == 0) Alignment.CenterStart else Alignment.CenterEnd)
                    SkeletonCard(bubble) { SkeletonLines(.84f, .57f) }
                }
            }
            SkeletonLayout.ChatList, SkeletonLayout.TicketList, SkeletonLayout.Contacts -> repeat(rows) {
                SkeletonCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val contact = layout == SkeletonLayout.Contacts
                        SkeletonBlock(Modifier.size(if (contact) 24.dp else 40.dp),
                            if (layout == SkeletonLayout.ChatList) CircleShape else MaterialTheme.shapes.small)
                        SkeletonLines(.58f, .78f, modifier = Modifier.weight(1f))
                        if (!contact) SkeletonBlock(Modifier.width(28.dp).height(10.dp))
                    }
                }
            }
            SkeletonLayout.Documents -> repeat(rows) {
                SkeletonCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        SkeletonBlock(Modifier.width(34.dp).height(44.dp))
                        SkeletonLines(.68f, .42f, modifier = Modifier.weight(1f))
                        SkeletonBlock(Modifier.size(24.dp), CircleShape)
                    }
                }
            }
            SkeletonLayout.Notes -> repeat(rows) {
                SkeletonCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SkeletonBlock(Modifier.width(46.dp).height(10.dp))
                        SkeletonBlock(Modifier.size(16.dp))
                    }
                    SkeletonLines(.52f, .91f, .70f)
                }
            }
            SkeletonLayout.Checklist -> SkeletonCard {
                SkeletonLines(.40f, height = 14.dp)
                SkeletonBlock(Modifier.fillMaxWidth().height(8.dp), CircleShape)
                repeat(3) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SkeletonBlock(Modifier.size(19.dp))
                        SkeletonLines(.75f, height = 10.dp)
                    }
                }
            }
            SkeletonLayout.SharedPlans -> repeat(rows) {
                SkeletonCard {
                    SkeletonLines(.52f, .76f)
                    SkeletonBlock(Modifier.width(100.dp).height(27.dp), CircleShape)
                }
            }
            SkeletonLayout.History -> repeat(rows) { index ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SkeletonBlock(Modifier.size(12.dp), CircleShape)
                        if (index < rows - 1) SkeletonBlock(Modifier.width(2.dp).height(37.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkeletonLines(.29f, height = 9.dp)
                        SkeletonLines(.66f, .84f)
                    }
                }
            }
            SkeletonLayout.Accounts, SkeletonLayout.StaffWorkload -> repeat(rows) {
                SkeletonCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SkeletonBlock(Modifier.size(32.dp), CircleShape)
                        SkeletonLines(.55f, .73f, modifier = Modifier.weight(1f))
                        SkeletonBlock(Modifier.width(47.dp).height(19.dp), CircleShape)
                    }
                    if (layout == SkeletonLayout.StaffWorkload) MetricRow(2)
                }
            }
            SkeletonLayout.DashboardCounts -> {
                repeat(2) { MetricRow(2, compact = true) }
                MetricRow(2)
            }
            SkeletonLayout.EmergencyAlerts -> repeat(rows) {
                SkeletonCard {
                    SkeletonLines(.52f, .45f)
                    SkeletonActions(2)
                    SkeletonBlock(Modifier.align(Alignment.End).width(88.dp).height(28.dp), CircleShape)
                }
            }
            SkeletonLayout.HotlineDirectory -> repeat(rows) {
                SkeletonCard {
                    SkeletonLines(.66f, .94f)
                    SkeletonBlock(Modifier.width(92.dp).height(22.dp), CircleShape)
                    SkeletonActions(2)
                }
            }
            SkeletonLayout.SupportAssignment -> SkeletonCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SkeletonBlock(Modifier.size(46.dp), CircleShape)
                    SkeletonLines(.75f, .53f, modifier = Modifier.weight(1f))
                }
            }
            SkeletonLayout.NearbySupport -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SkeletonBlock(Modifier.size(14.dp), CircleShape)
                SkeletonBlock(Modifier.width(112.dp).height(24.dp), CircleShape)
            }
            SkeletonLayout.Directions -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val foreground = LocalContentColor.current.copy(alpha = .32f)
                SkeletonBlock(Modifier.size(28.dp), CircleShape, foreground)
                SkeletonBlock(Modifier.fillMaxWidth(.62f).height(14.dp), color = foreground)
            }
            SkeletonLayout.NewsFeed, SkeletonLayout.NewsArticle -> repeat(rows) {
                SkeletonCard {
                    val article = layout == SkeletonLayout.NewsArticle
                    if (!article) SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(2.1f))
                    SkeletonLines(.24f, height = 9.dp)
                    SkeletonLines(.89f, .70f, height = 17.dp)
                    if (article) {
                        SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(2.1f))
                        SkeletonLines(.96f, .85f, .68f)
                    } else SkeletonLines(.92f, .65f)
                }
            }
            SkeletonLayout.FitnessSummary -> {
                SkeletonCard {
                    SkeletonLines(.42f, height = 14.dp)
                    MetricRow(3)
                }
                SkeletonCard {
                    SkeletonLines(.30f, height = 20.dp)
                    SkeletonBlock(Modifier.fillMaxWidth().height(8.dp), CircleShape)
                    MetricRow(3)
                }
            }
            SkeletonLayout.FitnessActivity -> {
                MetricRow(2)
                FitnessChartPlaceholder()
            }
            SkeletonLayout.FitnessVitals -> {
                SkeletonCard {
                    SkeletonLines(.48f, height = 15.dp)
                    Box(Modifier.fillMaxWidth().height(94.dp)) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                            repeat(4) { SkeletonBlock(Modifier.fillMaxWidth().height(1.dp)) }
                        }
                    }
                    SkeletonLines(.35f, height = 8.dp)
                }
                MetricRow(2)
            }
            SkeletonLayout.FitnessNutrition -> {
                SkeletonCard {
                    SkeletonBlock(Modifier.fillMaxWidth().height(8.dp), CircleShape)
                    MetricRow(3)
                }
                SkeletonCard {
                    SkeletonLines(.26f, height = 14.dp)
                    FoodResultPlaceholder()
                }
            }
            SkeletonLayout.FoodResults -> repeat(rows) { SkeletonCard { FoodResultPlaceholder() } }
            SkeletonLayout.Form -> repeat(rows) {
                SkeletonLines(.28f, height = 10.dp)
                SkeletonBlock(Modifier.fillMaxWidth().height(44.dp))
            }
            SkeletonLayout.Action -> SkeletonBlock(Modifier.fillMaxWidth().height(5.dp), CircleShape)
            SkeletonLayout.Image, SkeletonLayout.Video -> {
                val image = layout == SkeletonLayout.Image
                SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(if (image) 1.25f else 16f / 9f))
                if (!image) SkeletonBlock(Modifier.fillMaxWidth().height(6.dp), CircleShape)
            }
            SkeletonLayout.DocumentPage -> SkeletonCard(
                Modifier.align(Alignment.CenterHorizontally).widthIn(max = 270.dp)
                    .heightIn(max = 360.dp).fillMaxWidth().aspectRatio(.72f),
            ) {
                SkeletonLines(.70f, height = 15.dp)
                Spacer(Modifier.height(8.dp))
                repeat(3) { SkeletonLines(.94f, .87f, .61f) }
            }
            SkeletonLayout.TextDocument -> SkeletonCard {
                repeat(rows) { SkeletonLines(.97f, .86f, .78f, .59f) }
            }
        }
    }
}

@Composable
private fun SkeletonCard(
    modifier: Modifier = Modifier.fillMaxWidth(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun SkeletonLines(
    vararg widths: Float,
    modifier: Modifier = Modifier,
    height: Dp = 11.dp,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        widths.forEach { width -> SkeletonBlock(Modifier.fillMaxWidth(width).height(height)) }
    }
}

@Composable
private fun MetricRow(columns: Int, compact: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(columns) {
            SkeletonCard(Modifier.weight(1f)) {
                if (compact) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SkeletonBlock(Modifier.size(width = 30.dp, height = 20.dp))
                    SkeletonLines(.85f, modifier = Modifier.weight(1f), height = 9.dp)
                } else {
                    SkeletonLines(.52f, height = 20.dp)
                    SkeletonLines(.87f, height = 9.dp)
                }
            }
        }
    }
}

@Composable
private fun SkeletonActions(count: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { SkeletonBlock(Modifier.weight(1f).height(32.dp), CircleShape) }
    }
}

@Composable
private fun FoodResultPlaceholder() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SkeletonLines(.72f, .46f, modifier = Modifier.weight(1f))
        SkeletonBlock(Modifier.width(37.dp).height(16.dp))
        SkeletonBlock(Modifier.size(27.dp), CircleShape)
    }
}

@Composable
private fun FitnessChartPlaceholder() {
    SkeletonCard {
        SkeletonLines(.40f, height = 15.dp)
        Row(
            Modifier.fillMaxWidth().height(88.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            repeat(7) { SkeletonBlock(Modifier.weight(1f).fillMaxHeight(.54f)) }
        }
    }
}

@Composable
internal fun SkeletonBlock(
    modifier: Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    color: Color = MaterialTheme.colorScheme.outline.copy(alpha = .22f),
) {
    Spacer(modifier.background(color, shape))
}

/** Uses the app's existing polite live-region pattern for an in-progress secure file open. */
@Composable
internal fun SecureFileOpeningIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.documents_opening_securely), style = MaterialTheme.typography.labelLarge)
    }
}
