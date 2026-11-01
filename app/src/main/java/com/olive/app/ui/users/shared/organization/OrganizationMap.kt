// Staff and admins: shows permitted user locations on the organization map.
package com.olive.app.ui.users.shared.organization

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.ui.shared.Muted

/** A normalized point used by the future organization location feed. */
internal data class OrganizationMapPoint(val x: Float, val y: Float)

/** Shows the organization map shell without starting or requesting user location sharing. */
@Composable
internal fun OrganizationMap(onBack: () -> Unit, points: List<OrganizationMapPoint> = emptyList()) {
    BackHandler(onBack = onBack)
    val pulse by rememberInfiniteTransition(label = "locationPulse").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "locationPulseAlpha",
    )
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
        Canvas(
            Modifier.fillMaxWidth().height(360.dp)
                .background(Color(0xFFEAF0E3), RoundedCornerShape(20.dp)),
        ) {
            val grid = Color(0xFFB9C8AE)
            repeat(6) { index ->
                val x = size.width * index / 5f
                drawLine(grid, Offset(x, 0f), Offset(x, size.height), 2f)
            }
            repeat(5) { index ->
                val y = size.height * index / 4f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), 2f)
            }
            points.forEach { point ->
                val center = Offset(size.width * point.x.coerceIn(0f, 1f), size.height * point.y.coerceIn(0f, 1f))
                drawCircle(Color.Red.copy(alpha = pulse * 0.25f), 28.dp.toPx(), center)
                drawCircle(Color.Red.copy(alpha = pulse), 10.dp.toPx(), center)
            }
        }
        if (points.isEmpty()) Text(
            stringResource(R.string.organization_location_feed_not_enabled),
            color = Muted,
        )
    }
}
