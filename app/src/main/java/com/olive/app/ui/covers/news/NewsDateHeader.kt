// News cover users: displays the newspaper masthead date and edition strip.
package com.olive.app.ui.covers.news

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.R
import com.olive.app.ui.shared.*
import com.olive.app.ui.shared.LocalizedIcon as Icon
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Editorial date strip above the feed showing today's date, the edition, and an offline badge
 * when stories come from the device cache.
 *
 * @param edition Edition label, such as the local city or "Global".
 * @param isOffline Whether stories are currently served from cache or offline fallback.
 * @param modifier Compose modifier applied to the strip.
 */
@Composable
internal fun NewsDateHeader(edition: String, isOffline: Boolean, modifier: Modifier = Modifier) {
    val uiText = rememberResourceText()
    val dateText = remember {
        LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
    }
    Surface(
        modifier = modifier.fillMaxWidth().testTag("news_date_header"),
        color = Cream,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.6.dp, SoftGray)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Outlined.Today, null, Modifier.size(18.dp), tint = OliveGreen)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(dateText, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Ink)
                Text(uiText(R.string.news_daily_edition, edition), style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1)
            }
            if (isOffline) Surface(color = PaleOlive, shape = RoundedCornerShape(12.dp)) {
                Row(
                    Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Outlined.CloudOff, null, Modifier.size(13.dp), tint = DarkOlive)
                    Text(uiText(R.string.news_offline), style = MaterialTheme.typography.labelSmall, color = DarkOlive, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
