// News cover users: provides offline evergreen stories, reading-time estimates, and topic feed mapping.
package com.olive.app.data.news

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Estimates reading time for an article from its title and summary.
 *
 * @param title Article title string.
 * @param summary Article summary string.
 * @return Estimated minutes to read the full story, between 2 and 8.
 */
fun estimateReadingMinutes(title: String, summary: String): Int {
    val words = "$title $summary".trim().split(Regex("\\s+")).count { it.isNotBlank() }
    // Feed summaries are short; full stories run roughly five times longer, read at ~200 wpm.
    return (words.coerceAtLeast(30) * 5 / 200).coerceIn(2, 8)
}

/**
 * Maps a Daily Digest category chip to its Google News topic section id.
 *
 * @param category Category label shown in the chip row.
 * @return Google News topic id, or `null` for "All".
 */
internal fun googleNewsTopicId(category: String): String? = when (category) {
    "World" -> "WORLD"
    "Business" -> "BUSINESS"
    "Technology" -> "TECHNOLOGY"
    "Science" -> "SCIENCE"
    "Health" -> "HEALTH"
    "Sports" -> "SPORTS"
    "Culture" -> "ENTERTAINMENT"
    else -> null
}

/**
 * Builds evergreen in-house stories shown when no live or cached feed is available, so the
 * cover never looks empty offline. Stories are credited to Daily Digest (never to real outlets)
 * and link to a live Google News search for their topic once the connection returns.
 *
 * @param place City or region used to label the local feature.
 * @return Offline evergreen articles, newest first.
 */
fun curatedOfflineArticles(place: String): List<NewsArticle> {
    val area = place.ifBlank { "your area" }
    val now = ZonedDateTime.now()
    val rfc = DateTimeFormatter.RFC_1123_DATE_TIME
    val stories = listOf(
        Triple("Local", "Things to do around $area this week", "A roundup of community events, markets, and free activities worth checking out nearby."),
        Triple("Health", "Why a consistent sleep schedule matters", "Going to bed and waking at the same time each day supports energy, mood, and focus."),
        Triple("Technology", "Simple steps to keep your phone secure", "Updates, strong screen locks, and reviewing app permissions go a long way toward privacy."),
        Triple("Business", "Building a household budget that sticks", "Tracking spending for a month is often the easiest first step toward saving more."),
        Triple("Science", "A beginner's guide to stargazing", "You don't need a telescope: dark skies, a star map, and patience reveal a lot."),
        Triple("Culture", "Reading lists to start this season", "Short story collections and essays are a great way to build a daily reading habit."),
        Triple("Sports", "Easy ways to stay active on busy days", "Short walks, stretching breaks, and taking the stairs all add up over a week."),
    )
    return stories.mapIndexed { index, (topic, title, summary) ->
        val query = URLEncoder.encode(title, StandardCharsets.UTF_8.name())
        NewsArticle(
            title = title,
            summary = summary,
            link = "https://news.google.com/search?q=$query",
            source = "Daily Digest",
            published = now.minusHours((index + 1) * 2L).format(rfc),
            topic = topic,
        )
    }
}
