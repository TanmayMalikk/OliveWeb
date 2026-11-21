// News cover users: displays the newspaper masthead date, edition, and location picker strip.
package com.olive.app.ui.covers.news

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
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
import com.olive.app.data.news.NewsScope
import com.olive.app.ui.shared.*
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.shared.OliveIconButton as IconButton
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Editorial date and location strip above the feed showing today's date, the local edition/location,
 * location picker action, and an offline badge when stories come from the device cache.
 *
 * @param edition Edition label, such as the local city or "Global".
 * @param isOffline Whether stories are currently served from cache or offline fallback.
 * @param onEditLocation Callback invoked to open the location picker dialog, or null if editing is not applicable.
 * @param loading Whether the local place or news is currently loading.
 * @param scope News scope (Local or Global).
 * @param modifier Compose modifier applied to the strip.
 */
@Composable
internal fun NewsDateHeader(
    edition: String,
    isOffline: Boolean,
    onEditLocation: (() -> Unit)? = null,
    loading: Boolean = false,
    scope: NewsScope = NewsScope.LOCAL,
    modifier: Modifier = Modifier
) {
    val uiText = rememberResourceText()
    val dateText = remember {
        LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
    }
    Surface(
        modifier = modifier.fillMaxWidth().testTag("news_date_header"),
        color = Cream,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(0.6.dp, SoftGray)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = PaleOlive,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (scope == NewsScope.LOCAL) Icons.Outlined.LocationOn else Icons.Outlined.Today,
                        null,
                        Modifier.size(20.dp),
                        tint = OliveGreen
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    dateText,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = Ink
                )
                if (loading) {
                    SkeletonBlock(Modifier.width(110.dp).height(14.dp))
                } else {
                    Text(
                        if (scope == NewsScope.LOCAL) uiText(R.string.news_near_location, edition)
                        else uiText(R.string.news_daily_edition, edition),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        maxLines = 1
                    )
                }
            }
            if (isOffline) Surface(color = PaleOlive, shape = RoundedCornerShape(10.dp)) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Outlined.CloudOff, null, Modifier.size(13.dp), tint = DarkOlive)
                    Text(
                        uiText(R.string.news_offline),
                        style = MaterialTheme.typography.labelSmall,
                        color = DarkOlive,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (scope == NewsScope.LOCAL && onEditLocation != null) {
                IconButton(
                    onClick = onEditLocation,
                    modifier = Modifier.size(38.dp).testTag("change_news_location")
                ) {
                    Icon(
                        Icons.Outlined.Edit,
                        uiText(R.string.change_local_news_location),
                        tint = OliveGreen,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
