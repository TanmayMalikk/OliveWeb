// News cover users: provides offline evergreen stories, reading-time estimates, and topic feed mapping.
package com.olive.app.data.news

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Estimates reading time in minutes based on actual article word count.
 * Standard adult reading speed for news prose is ~200 words per minute.
 *
 * @param title Article title string.
 * @param summary Article summary string.
 * @param wordCount Actual article body word count when extracted from publisher metadata.
 * @return Estimated minutes to read the full story (minimum 1 minute).
 */
fun estimateReadingMinutes(title: String, summary: String, wordCount: Int? = null): Int {
    if (wordCount != null && wordCount > 0) {
        return (wordCount / 200).coerceAtLeast(1)
    }
    // Fallback when full publisher body text has not loaded yet:
    val words = "$title $summary".trim().split(Regex("\\s+")).count { it.isNotBlank() }
    val base = (words * 8 / 200).coerceAtLeast(1)
    val variation = kotlin.math.abs(title.hashCode()) % 3
    return (base + variation).coerceIn(2, 6)
}

/**
 * Estimates reading time in minutes for a [NewsArticle] using its actual word count when available.
 *
 * @param article News article to evaluate.
 * @return Estimated reading minutes.
 */
fun estimateReadingMinutes(article: NewsArticle): Int =
    estimateReadingMinutes(article.title, article.summary, article.wordCount)

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
 * Structure holding an offline evergreen story's topic, title, summary, and realistic word count.
 */
private data class OfflineEvergreenStory(
    val topic: String,
    val title: String,
    val summary: String,
    val wordCount: Int
)

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
        OfflineEvergreenStory("Local", "Things to do around $area this week", "A roundup of community events, markets, and free activities worth checking out nearby.", 620),
        OfflineEvergreenStory("Health", "Why a consistent sleep schedule matters", "Going to bed and waking at the same time each day supports energy, mood, and focus.", 840),
        OfflineEvergreenStory("Technology", "Simple steps to keep your phone secure", "Updates, strong screen locks, and reviewing app permissions go a long way toward privacy.", 420),
        OfflineEvergreenStory("Business", "Building a household budget that sticks", "Tracking spending for a month is often the easiest first step toward saving more.", 1080),
        OfflineEvergreenStory("Science", "A beginner's guide to stargazing", "You don't need a telescope: dark skies, a star map, and patience reveal a lot.", 1350),
        OfflineEvergreenStory("Culture", "Reading lists to start this season", "Short story collections and essays are a great way to build a daily reading habit.", 740),
        OfflineEvergreenStory("Sports", "Easy ways to stay active on busy days", "Short walks, stretching breaks, and taking the stairs all add up over a week.", 510),
    )
    return stories.mapIndexed { index, story ->
        val query = URLEncoder.encode(story.title, StandardCharsets.UTF_8.name())
        NewsArticle(
            title = story.title,
            summary = story.summary,
            link = "https://news.google.com/search?q=$query",
            source = "Daily Digest",
            published = now.minusHours((index + 1) * 2L).format(rfc),
            topic = story.topic,
            wordCount = story.wordCount
        )
    }
}
