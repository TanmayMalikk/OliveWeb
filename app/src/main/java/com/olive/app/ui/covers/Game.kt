// Contains the game code for this part of Olive.
package com.olive.app.ui.covers.game

import com.olive.app.ui.shared.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.R
import com.olive.app.ui.shared.Cream
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveGreen

/** A small, usable game cover. The nickname field also accepts the configured safe-entry phrase. */
@Composable
internal fun GameCover(
    interactive: Boolean = true,
    onEntryAttempt: (String) -> Boolean = { false },
) {
    var nickname by remember { mutableStateOf("") }
    var score by remember { mutableIntStateOf(0) }
    BackHandler(interactive) { }
    Column(
        Modifier.fillMaxSize().background(Cream).safeDrawingPadding().padding(24.dp)
            .testTag("game_cover"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) { },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    Icons.Outlined.SportsEsports,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp).size(34.dp).testTag("cover_brand_game"),
                    tint = OliveGreen,
                )
            }
            Text(
                stringResource(R.string.game_cover_title),
                modifier = Modifier.semantics { heading() },
                fontFamily = FontFamily.Serif,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                color = OliveGreen,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.game_cover_instructions), color = Muted)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = nickname,
            onValueChange = { value ->
                if (onEntryAttempt(value)) nickname = "" else nickname = value.take(64)
            },
            enabled = interactive,
            singleLine = true,
            label = { Text(stringResource(R.string.game_player_name)) },
            modifier = Modifier.fillMaxWidth().testTag("game_entry"),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { score++ },
            enabled = interactive,
            modifier = Modifier.size(112.dp).testTag("game_olive"),
            shape = CircleShape,
        ) {
            Text(stringResource(R.string.game_tap_olive))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.game_score, score),
            modifier = Modifier.testTag("game_score")
                .semantics { liveRegion = LiveRegionMode.Polite },
            fontWeight = FontWeight.SemiBold,
        )
    }
}
