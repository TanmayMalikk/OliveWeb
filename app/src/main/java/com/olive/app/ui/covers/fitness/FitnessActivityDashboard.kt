// Fitness cover users: summarizes today's steps, distance, and calories in one card.
package com.olive.app.ui.covers.fitness

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.ui.shared.Paper
import java.util.Locale
import kotlin.math.roundToInt

private val MacroRingColor = Color(0xFF875E78)

// Shows today's movement summary and the 14-day activity graphs.
@Composable
internal fun FitnessActivityDashboard(snapshot: FitnessSnapshot) {
    val distance = FitnessMath.distanceKm(snapshot.stepsToday, snapshot.profile.heightCm)
    val burned = FitnessMath.walkingCalories(snapshot.stepsToday, snapshot.profile)
    val progress = (
        snapshot.stepsToday.toFloat() / snapshot.profile.stepGoal.coerceAtLeast(1)
    ).coerceIn(0f, 1f)
    val description = pluralStringResource(
        R.plurals.fitness_activity_dashboard_description,
        snapshot.stepsToday,
        snapshot.stepsToday,
        distance,
        burned,
    )

    Card(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = description
        },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF263722)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.daily_activity),
                color = Paper,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    DashboardMetric(
                        snapshot.stepsToday.toString(),
                        stringResource(R.string.fitness_steps_unit),
                        Color(0xFFA7CA65),
                    )
                    DashboardMetric(
                        String.format(Locale.ROOT, "%.2f", distance),
                        stringResource(R.string.fitness_kilometres_unit),
                        Color(0xFF53BBD8),
                    )
                    DashboardMetric(
                        burned.roundToInt().toString(),
                        stringResource(R.string.fitness_kilocalories_unit),
                        MacroRingColor,
                    )
                }
                ActivityProgressRings(progress)
            }
        }
    }
}

// Builds the dashboard metric that the fitness cover needs.
@Composable
private fun DashboardMetric(value: String, label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.size(13.dp).background(color, CircleShape))
        Text(value, color = Paper, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, color = Paper.copy(alpha = 0.72f))
    }
}

// Draws progress for steps, active time, and burned calories.
@Composable
private fun ActivityProgressRings(progress: Float) {
    Canvas(Modifier.size(142.dp)) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val widths = floatArrayOf(11.dp.toPx(), 10.dp.toPx(), 9.dp.toPx())
        val radii = floatArrayOf(size.minDimension * 0.43f, size.minDimension * 0.32f, size.minDimension * 0.21f)
        val colours = listOf(Color(0xFFA7CA65), Color(0xFF53BBD8), MacroRingColor)
        radii.indices.forEach { index ->
            drawCircle(
                color = Paper.copy(alpha = 0.12f),
                radius = radii[index],
                center = centre,
                style = Stroke(widths[index]),
            )
            drawArc(
                color = colours[index],
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = Offset(centre.x - radii[index], centre.y - radii[index]),
                size = androidx.compose.ui.geometry.Size(radii[index] * 2f, radii[index] * 2f),
                style = Stroke(widths[index], cap = StrokeCap.Round),
            )
        }
    }
}
