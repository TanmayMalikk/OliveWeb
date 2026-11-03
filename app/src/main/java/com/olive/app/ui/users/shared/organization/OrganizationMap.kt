// Admins: shows every available user location on the protected organization map.
package com.olive.app.ui.users.shared.organization

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import com.olive.app.ui.users.user.location.InteractiveLocationMap

/** Opens the admin feed and draws each user as a red live or last-known marker. */
@Composable
internal fun OrganizationMap(session: SessionViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    DisposableEffect(session.coverAccountId, session.accountRole) {
        session.adminLocationMap.open(session.accountRole)
        onDispose(session.adminLocationMap::close)
    }
    val state = session.adminLocationMap.state
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.safety_back)) }
        Text(
            stringResource(R.string.organization_live_map),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.organization_live_map_description), color = Muted)
        Box(
            Modifier.fillMaxWidth().height(420.dp),
            contentAlignment = Alignment.Center,
        ) {
            InteractiveLocationMap(
                points = state.points,
                ownPointUid = null,
                modifier = Modifier.fillMaxSize(),
            )
            if (state.loading) CircularProgressIndicator()
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!state.loading && state.error == null && state.points.isEmpty()) {
            Text(stringResource(R.string.organization_location_feed_empty), color = Muted)
        }
    }
}
