// News cover users: displays the newspaper masthead date, edition, location picker, and scope selector.
package com.olive.app.ui.covers.news

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * location picker action, offline badge, and compact segmented local/global scope switch.
 *
 * @param scope Currently active news scope (Local or Global).
 * @param onScopeChange Callback triggered when switching between Local and Global scope.
 * @param edition Edition label, such as the local city or "Global".
 * @param isOffline Whether stories are currently served from cache or offline fallback.
 * @param onEditLocation Callback invoked to open the location picker dialog, or null if editing is not applicable.
 * @param loading Whether the local place or news is currently loading.
 * @param modifier Compose modifier applied to the strip.
 */
@Composable
internal fun NewsDateHeader(
    scope: NewsScope,
    onScopeChange: (NewsScope) -> Unit,
    edition: String,
    isOffline: Boolean,
    onEditLocation: (() -> Unit)? = null,
    loading: Boolean = false,
    modifier: Modifier = Modifier
) {
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
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        dateText,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = Ink
                    )
                    if (isOffline) Surface(color = PaleOlive, shape = RoundedCornerShape(10.dp)) {
                        Row(
                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(Icons.Outlined.CloudOff, null, Modifier.size(11.dp), tint = DarkOlive)
                            Text(
                                uiText(R.string.news_offline),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = DarkOlive,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                // Compact segmented scope toggle (Local / Global)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = WarmWhite,
                    border = BorderStroke(0.8.dp, SoftGray)
                ) {
                    Row(
                        modifier = Modifier.padding(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = { onScopeChange(NewsScope.LOCAL) },
                            shape = RoundedCornerShape(14.dp),
                            color = if (scope == NewsScope.LOCAL) OliveGreen else Color.Transparent,
                            contentColor = if (scope == NewsScope.LOCAL) Cream else DarkOlive,
                            modifier = Modifier.height(26.dp).testTag("news_scope_local")
                        ) {
                            Row(
                                Modifier.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(Icons.Outlined.LocationOn, null, Modifier.size(12.dp))
                                Text(
                                    uiText(R.string.local),
                                    fontSize = 11.sp,
                                    fontWeight = if (scope == NewsScope.LOCAL) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                        Surface(
                            onClick = { onScopeChange(NewsScope.GLOBAL) },
                            shape = RoundedCornerShape(14.dp),
                            color = if (scope == NewsScope.GLOBAL) OliveGreen else Color.Transparent,
                            contentColor = if (scope == NewsScope.GLOBAL) Cream else DarkOlive,
                            modifier = Modifier.height(26.dp).testTag("news_scope_global")
                        ) {
                            Row(
                                Modifier.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(Icons.Outlined.Public, null, Modifier.size(12.dp))
                                Text(
                                    uiText(R.string.global),
                                    fontSize = 11.sp,
                                    fontWeight = if (scope == NewsScope.GLOBAL) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    if (scope == NewsScope.LOCAL) Icons.Outlined.LocationOn else Icons.Outlined.Public,
                    null,
                    Modifier.size(12.dp),
                    tint = OliveGreen
                )
                if (loading) {
                    SkeletonBlock(Modifier.width(90.dp).height(12.dp))
                } else {
                    Text(
                        if (scope == NewsScope.LOCAL) uiText(R.string.news_near_location, edition)
                        else uiText(R.string.news_daily_edition, edition),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = Muted,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                if (scope == NewsScope.LOCAL && onEditLocation != null) {
                    IconButton(
                        onClick = onEditLocation,
                        modifier = Modifier.size(24.dp).testTag("change_news_location")
                    ) {
                        Icon(
                            Icons.Outlined.Edit,
                            uiText(R.string.change_local_news_location),
                            tint = OliveGreen,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}
