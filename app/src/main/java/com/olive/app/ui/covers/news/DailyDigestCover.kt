// News cover users: shows the daily digest cover.
package com.olive.app.ui.covers.news

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveTextButton as TextButton
import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.ui.shared.rememberResourceText

import com.olive.app.ui.shared.LocalizedIcon as Icon
import androidx.compose.ui.res.stringResource
import com.olive.app.R

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.olive.app.data.location.CoverLocation
import com.olive.app.data.news.GoogleNewsRepository
import com.olive.app.data.news.NewsArticle
import com.olive.app.data.news.NewsFeedCacheStore
import com.olive.app.data.news.NewsReadingHistoryStore
import com.olive.app.data.news.NewsScope
import com.olive.app.data.news.curatedOfflineArticles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Daily Digest refreshes local/global headlines while bookmarks stay saved separately. UI sizing/color: the feed uses Cream bars, 20dp side padding, 14dp gaps, and the shared Olive typography/colors.
 *
 * @param session Shared session view model owning application state and actions.
 * @param locationPermissionVersion Location permission version supplied to [NewsCover].
 * @param interactive Whether to apply interactive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewsCover(
    session: SessionViewModel,
    locationPermissionVersion: Int = 0,
    interactive: Boolean = true
) {
    val uiText = rememberResourceText()
    val context = LocalContext.current
    val tabSaver = remember {
        Saver<String, String>(
            save = { if (it == "Account") "Today" else it },
            restore = { if (it == "Account") "Today" else it },
        )
    }
    var tab by rememberSaveable(session.coverAccountId, stateSaver = tabSaver) { mutableStateOf("Today") }
    var scope by rememberSaveable(session.coverAccountId) { mutableStateOf(NewsScope.LOCAL) }
    var query by remember { mutableStateOf("") }
    val articleSaver = remember {
        androidx.compose.runtime.saveable.Saver<NewsArticle?, String>(
            save = { it?.toJson()?.toString() },
            restore = { runCatching { NewsArticle.fromJson(org.json.JSONObject(it)) }.getOrNull() },
        )
    }
    var selected by rememberSaveable(session.coverAccountId, stateSaver = articleSaver) { mutableStateOf<NewsArticle?>(null) }
    var articles by remember { mutableStateOf<List<NewsArticle>>(emptyList()) }
    var completedRequest by remember { mutableStateOf<List<Any>?>(null) }
    var newsError by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val tabHistory = remember { mutableListOf<String>() }
    val saved = session.savedNews
    val fallbackPlace = session.deliveryAddress.generalLocation.ifBlank { "Canada" }
    val locationPreferenceKey = "custom_location_${session.coverAccountId ?: "guest"}"
    val locationPreferences = remember(context) {
        context.getSharedPreferences(
            "daily_digest_preferences",
            android.content.Context.MODE_PRIVATE
        )
    }
    val feedCache = remember(context) { NewsFeedCacheStore(context.applicationContext) }
    var customPlace by rememberSaveable(locationPreferenceKey) {
        mutableStateOf(
            locationPreferences.getString(
                locationPreferenceKey,
                ""
            ).orEmpty()
        )
    }
    var currentPlace by rememberSaveable(locationPreferenceKey) { mutableStateOf(customPlace.ifBlank { fallbackPlace }) }
    var locationEditorOpen by remember { mutableStateOf(false) }
    var locationDraft by remember { mutableStateOf("") }
    var category by rememberSaveable(session.coverAccountId) { mutableStateOf("All") }
    var showHistory by rememberSaveable(session.coverAccountId) { mutableStateOf(false) }
    val historyStore = remember(context) { NewsReadingHistoryStore(context.applicationContext) }
    var readingHistory by remember { mutableStateOf<List<NewsArticle>>(emptyList()) }
    // Reload history whenever the user returns from an article or opens the Saved tab.
    LaunchedEffect(selected, tab) {
        if (selected == null) readingHistory = withContext(Dispatchers.IO) { historyStore.load() }
    }
    var handledCoverReset by remember { mutableIntStateOf(session.coverReset) }
    LaunchedEffect(interactive, session.coverReset) {
        val resetChanged = session.coverReset != handledCoverReset
        handledCoverReset = session.coverReset
        if (!interactive || resetChanged) {
            if (tab == "Account") {
                tab = "Today"
                selected = null
            }
            tabHistory.removeAll { it == "Account" }
            locationEditorOpen = false
        }
    }
    val todayListState = rememberLazyListState()
    val savedListState = rememberLazyListState()
    val accountListState = rememberLazyListState()
    var lastTab by remember { mutableStateOf(tab) }
    LaunchedEffect(tab) {
        if (tab != lastTab) {
            lastTab = tab
            when (tab) {
                "Today" -> todayListState
                "Saved" -> savedListState
                else -> accountListState
            }.scrollToItem(0)
        }
    }
    var lastFeedFilter by remember { mutableStateOf(Triple(scope, category, customPlace)) }
    LaunchedEffect(scope, category, customPlace) {
        val current = Triple(scope, category, customPlace)
        if (current != lastFeedFilter) {
            lastFeedFilter = current
            todayListState.scrollToItem(0)
        }
    }
    var lastShowHistory by remember { mutableStateOf(showHistory) }
    LaunchedEffect(showHistory) {
        if (showHistory != lastShowHistory) {
            lastShowHistory = showHistory
            savedListState.scrollToItem(0)
        }
    }
    // Back closes an article first, then returns through the previous news tabs.
    val back = rememberOliveBack(interactive, preview = tab != "Today" || tabHistory.isNotEmpty()) {
        // Opens the selected news section or saved-article view.
        when {
            selected != null -> selected = null
            tabHistory.isNotEmpty() -> {
                selected = null
                tab = tabHistory.removeAt(tabHistory.lastIndex)
            }

            tab != "Today" -> {
                selected = null
                tab = "Today"
            }
        }
    }
    val request = listOf(
        session.language,
        scope,
        refresh,
        session.coverReset,
        fallbackPlace,
        locationPermissionVersion,
        customPlace,
        locationPreferenceKey,
        category,
    )
    val loading = request != completedRequest
    LaunchedEffect(request) {
        if (!interactive && articles.isNotEmpty()) {
            completedRequest = request
            return@LaunchedEffect
        }
        newsError = false
        if (scope == NewsScope.LOCAL) currentPlace =
            customPlace.ifBlank { CoverLocation.city(context, fallbackPlace) }
        if (articles.isEmpty()) {
            val cached = withContext(Dispatchers.IO) { feedCache.load(session.language, scope) }
                ?.takeIf { scope != NewsScope.LOCAL || it.place == currentPlace }?.articles.orEmpty()
            val instant = cached.ifEmpty { curatedOfflineArticles(currentPlace) }
                .filter { category == "All" || it.topic.equals(category, true) }
            if (instant.isNotEmpty()) articles = instant
        }
        // Use the fallback when news cover is unavailable.
        try {
            GoogleNewsRepository().latest(session.language, scope, currentPlace, category).also {
                articles = it
                newsError = it.isEmpty()
                // Only the unfiltered edition is cached so category feeds never overwrite it.
                if (category == "All") withContext(Dispatchers.IO) {
                    feedCache.save(
                        session.language,
                        scope,
                        currentPlace,
                        it
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val cached = withContext(Dispatchers.IO) { feedCache.load(session.language, scope) }
                ?.takeIf { scope != NewsScope.LOCAL || it.place == currentPlace }?.articles.orEmpty()
            // Offline: show the cached edition, else evergreen stories so the cover never looks empty.
            articles = cached.ifEmpty { curatedOfflineArticles(currentPlace) }
                .filter { category == "All" || it.topic.equals(category, true) }
            newsError = true
        }
        completedRequest = request
    }
    LaunchedEffect(query) {
        if (session.account.interceptSearchAsync(query)) query = ""
    }
    LaunchedEffect(locationEditorOpen, locationDraft) {
        if (locationEditorOpen && session.account.interceptSearchAsync(locationDraft)) locationDraft = ""
    }
    val locationSaveScope = rememberCoroutineScope()
    var savingLocation by remember { mutableStateOf(false) }
    if (locationEditorOpen) NewsLocationDialog(locationDraft, { value ->
        locationDraft = value.take(120)
    }, onDismiss = { locationEditorOpen = false }, onSave = {
        if (!savingLocation) {
            val candidate = locationDraft
            savingLocation = true
            locationSaveScope.launch {
                try {
                    if (!session.account.interceptSearchAsync(candidate) &&
                        locationEditorOpen && locationDraft == candidate
                    ) {
                        customPlace = candidate.trim()
                        currentPlace = customPlace
                        locationPreferences.edit { putString(locationPreferenceKey, customPlace) }
                        locationEditorOpen = false
                        refresh++
                    }
                } finally {
                    savingLocation = false
                }
            }
        }
    }, onUseDeviceLocation = {
        customPlace = ""
        currentPlace = fallbackPlace
        locationPreferences.edit { remove(locationPreferenceKey) }
        locationEditorOpen = false
        refresh++
    })
    Scaffold(containerColor = Cream, contentWindowInsets = WindowInsets.safeDrawing, topBar = {
        TopAppBar(
            title = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Newspaper,
                            null,
                            Modifier.size(22.dp).testTag("cover_brand_news"),
                            tint = OliveGreen
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            uiText(R.string.ui_daily_digest),
                            fontFamily = FontFamily.Serif,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = OliveGreen
                        )
                    }
                    if (tab == "Today" && selected == null) Text(
                        if (scope == NewsScope.LOCAL) uiText(
                            R.string.local_edition
                        ) else uiText(R.string.global_edition),
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted
                    )
                }
            },
            actions = {
                if (tab == "Today" && selected == null) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = WarmWhite,
                        border = BorderStroke(0.8.dp, SoftGray),
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                onClick = { scope = NewsScope.LOCAL },
                                shape = RoundedCornerShape(16.dp),
                                color = if (scope == NewsScope.LOCAL) OliveGreen else Color.Transparent,
                                contentColor = if (scope == NewsScope.LOCAL) Cream else Ink,
                                modifier = Modifier.height(32.dp).testTag("news_scope_local")
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Outlined.LocationOn, null, Modifier.size(14.dp))
                                    Text(
                                        uiText(R.string.local),
                                        fontSize = 13.sp,
                                        fontWeight = if (scope == NewsScope.LOCAL) FontWeight.Bold else FontWeight.SemiBold
                                    )
                                }
                            }
                            Surface(
                                onClick = { scope = NewsScope.GLOBAL },
                                shape = RoundedCornerShape(16.dp),
                                color = if (scope == NewsScope.GLOBAL) OliveGreen else Color.Transparent,
                                contentColor = if (scope == NewsScope.GLOBAL) Cream else Ink,
                                modifier = Modifier.height(32.dp).testTag("news_scope_global")
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Outlined.Public, null, Modifier.size(14.dp))
                                    Text(
                                        uiText(R.string.global),
                                        fontSize = 13.sp,
                                        fontWeight = if (scope == NewsScope.GLOBAL) FontWeight.Bold else FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                    IconButton(
                        onClick = { refresh++ },
                        enabled = !loading,
                        modifier = Modifier.size(38.dp)
                    ) { Icon(Icons.Outlined.Refresh, uiText(R.string.refresh), Modifier.size(20.dp)) }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Cream),
        )
    }, bottomBar = {
        NavigationBar(
            modifier = Modifier.testTag("cover_navigation"),
            containerColor = Cream,
            tonalElevation = 0.dp
        ) {
            listOf("Today", "Saved", "Account").forEach { choice ->
                NavigationBarItem(
                    selected = tab == choice, onClick = {
                        if (choice == tab && selected == null) {
                            val activeListState = when (tab) {
                                "Today" -> todayListState
                                "Saved" -> savedListState
                                else -> accountListState
                            }
                            locationSaveScope.launch { activeListState.scrollToItem(0) }
                        } else {
                            if (choice != tab) tabHistory += tab
                            selected = null
                            tab = choice
                            if (choice == "Account") session.recordSettingsInteraction(
                                "Daily Digest",
                                "Opened account page"
                            )
                        }
                    },
                    icon = {
                        Icon(
                            when (choice) {
                                "Today" -> Icons.AutoMirrored.Outlined.Article
                                "Saved" -> if (tab == choice) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder
                                else -> Icons.Outlined.PersonOutline
                            }, null
                        )
                    },
                    label = {
                        Text(
                            // Opens the selected news section or saved-article view.
                            when (choice) {
                                "Today" -> uiText(R.string.today)
                                "Saved" -> uiText(R.string.saved)
                                else -> uiText(
                                    R.string.account
                                )
                            }
                        )
                    })
            }
        }
    }) { padding ->
        val article = selected
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            OliveUndoOverlay(scopeKey = session.coverAccountId, enabled = interactive) {
                OliveRouteEntrance(article?.link ?: tab, back) {
                    if (article != null && tab != "Account") {
                        val onBookmark = newsBookmarkAction(session, article)
                        NewsArticleReader(
                            article, saved.any { it.link == article.link }, interactive,
                            modifier = Modifier.fillMaxSize().testTag("news_cover"),
                            onBack = { selected = null }, onBookmark = onBookmark)
                    } else {
                        NewsFeed(
                            session,
                            NewsFeedState(
                                tab, scope, query, articles, saved, loading, newsError, currentPlace,
                                category = category,
                                history = readingHistory,
                                showHistory = showHistory,
                                offline = newsError && articles.isNotEmpty(),
                            ),
                            NewsFeedActions(
                                search = { value ->
                                    query = value
                                },
                                changeScope = { scope = it },
                                editLocation = {
                                    locationDraft = currentPlace
                                    locationEditorOpen = true
                                },
                                refresh = { refresh++ },
                                openArticle = { selected = it },
                                changeCategory = { category = it },
                                toggleHistory = { showHistory = it },
                                clearHistory = {
                                    historyStore.clear()
                                    readingHistory = emptyList()
                                },
                            ),
                                Modifier.fillMaxSize(),
                                listState = when (tab) {
                                    "Today" -> todayListState
                                    "Saved" -> savedListState
                                    else -> accountListState
                                },
                        )
                    }
                }
            }
        }
    }
}

/**
 * News cover shows this news location dialog view so the user can view its details and use its actions.
 *
 * @param value Input value to store, parse, or format.
 * @param onValueChange Callback invoked when value change changes or occurs.
 * @param onDismiss Callback invoked when the dialog or sheet is dismissed.
 * @param onSave Callback invoked when save changes or occurs.
 * @param onUseDeviceLocation Callback invoked when use device location changes or occurs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewsLocationDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onUseDeviceLocation: () -> Unit
) {
    val uiText = rememberResourceText()
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            Modifier.fillMaxWidth().imePadding(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = OliveGreen
                    ) {
                        Box(
                            Modifier.size(46.dp),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Outlined.LocationOn, null) }
                    }
                    Text(
                        stringResource(R.string.change_local_news_location),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f)
                    )
                }
                var showLocationError by remember { mutableStateOf(false) }
                var locationShake by remember { mutableIntStateOf(0) }
                val locationErrorShake = rememberFieldErrorShakeTrigger(
                    if (showLocationError && value.isBlank()) "location_required" else null,
                    locationShake,
                )
                Text(
                    uiText(R.string.ui_this_changes_the_local_news_search_on_this_device),
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value,
                    {
                        onValueChange(it)
                        showLocationError = false
                    },
                    label = { Text(stringResource(R.string.city_or_region)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    isError = showLocationError && value.isBlank(),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("news_location_input")
                        .oliveErrorShake(locationErrorShake)
                        .keepVisibleWhileTyping()
                )
                OutlinedButton(
                    onClick = onUseDeviceLocation,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("news_location_device")
                ) {
                    Icon(Icons.Outlined.MyLocation, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.use_device_location)
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .testTag("news_location_cancel")
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = {
                            if (value.isBlank()) {
                                showLocationError = true
                                locationShake += 1
                            } else {
                                onSave()
                            }
                        },
                        enabled = true,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .testTag("news_location_save")
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }
}
