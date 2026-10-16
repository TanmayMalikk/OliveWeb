package com.olive.app.ui
import com.olive.app.R
import androidx.compose.ui.res.stringResource

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The Game cover owns its own placeholder until its gameplay and safe-entry trigger are designed.
// UI sizing/color: the placeholder uses Cream, 24dp page padding, a 52dp icon with 22dp inset, and a 32sp OliveGreen title.
@Composable fun GameCover(interactive: Boolean=true) {
    // HARDCODED GAME NOTIFICATION SPOT: edit AppNotifications.kt after its name/icon are final, then call it from an event.
    // val notificationContext=androidx.compose.ui.platform.LocalContext.current
    // com.olive.app.notification.AppNotifications.showGame(notificationContext)
    BackHandler(interactive) { }
    Column(
        Modifier.fillMaxSize().background(Cream).safeDrawingPadding().padding(24.dp).testTag("game_cover"),
        horizontalAlignment=Alignment.CenterHorizontally,
        verticalArrangement=Arrangement.Center,
    ) {
        Surface(shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer) {
            Icon(Icons.Outlined.SportsEsports,null,Modifier.padding(22.dp).size(52.dp),tint=OliveGreen)
        }
        Spacer(Modifier.height(20.dp))
        Text("Game",fontFamily=FontFamily.Serif,fontSize=32.sp,fontWeight=FontWeight.SemiBold,color=OliveGreen)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.in_development),color=Muted)
    }
}
