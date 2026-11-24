package com.olive.app.ui.users.shared.walkthrough

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.network.sha256Hex
import com.olive.app.ui.shared.OliveButton
import com.olive.app.ui.shared.OliveTextButton
import com.olive.app.ui.users.shared.navigation.SafePage

internal class WalkthroughVisits(context: Context, accountId: String) {
    private val preferences = context.getSharedPreferences("olive_walkthrough_visits", Context.MODE_PRIVATE)
    private val accountKey = sha256Hex("walkthrough:$accountId")

    fun hasSeen(page: SafePage): Boolean = preferences.getBoolean(key(page), false)

    fun markSeen(page: SafePage) {
        preferences.edit().putBoolean(key(page), true).apply()
    }

    private fun key(page: SafePage): String = sha256Hex("$accountKey:${page.name}")
}

/** Groups related topics so opening a page displays at most one introduction. */
internal fun walkthroughTopicsForPage(role: AccountRole, page: SafePage): List<WalkthroughTopic> =
    when (role) {
        AccountRole.USER, AccountRole.SAFE_CONTACT -> walkthroughTopicsForRole(role).filter {
            it.targetPage == page ||
                (role == AccountRole.USER && page == SafePage.Contacts && it.targetPage == SafePage.Hotlines)
        }
        else -> emptyList()
    }

/** An in-screen card keeps private-app navigation and Quick Exit available. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FirstVisitWalkthrough(
    accountId: String,
    role: AccountRole,
    page: SafePage,
    onOpenGuide: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (accountId.isBlank()) return
    val topics = remember(role, page) { walkthroughTopicsForPage(role, page) }
    if (topics.isEmpty()) return

    val context = LocalContext.current
    val visits = remember(context, accountId) { WalkthroughVisits(context, accountId) }
    var show by remember(visits, role, page) { mutableStateOf(!visits.hasSeen(page)) }
    if (!show) return
    LaunchedEffect(visits, page) { visits.markSeen(page) }

    Surface(
        modifier = modifier.fillMaxWidth().testTag("first_visit_walkthrough"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                topics.forEach { topic ->
                    Text(
                        stringResource(if (page == SafePage.Contacts) R.string.ui_contacts else topic.titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    val detail = when {
                        page == SafePage.Contacts -> R.string.walkthrough_topic_hotlines_step_3
                        role == AccountRole.SAFE_CONTACT && page == SafePage.SafetyPlan ->
                            R.string.safe_contact_safety_plan_help
                        role == AccountRole.SAFE_CONTACT && page == SafePage.Documents ->
                            R.string.safe_contact_documents_help
                        else -> topic.summaryRes
                    }
                    Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OliveTextButton(
                    onClick = {
                        show = false
                        onOpenGuide(topics.first().id)
                    },
                    modifier = Modifier.testTag("first_visit_open_walkthrough"),
                ) { Text(stringResource(R.string.support_walkthrough)) }
                OliveButton(
                    onClick = { show = false },
                    modifier = Modifier.testTag("first_visit_dismiss"),
                ) { Text(stringResource(R.string.close)) }
            }
        }
    }
}
