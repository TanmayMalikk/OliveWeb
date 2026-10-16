package com.olive.app.ui.covers.news

import com.olive.app.data.NewsArticle
import com.olive.app.data.fallbackNewsTopic
import com.olive.app.data.parseNewsArticleMetadata
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NewsArticleMetadataTest {
    private val url="https://publisher.example/news/article"

    @Test fun usesTheArticlesImageSectionAndExplicitPaywallFlag() {
        val result=parseNewsArticleMetadata("""
            <script type="application/ld+json">{
              "@type":"NewsArticle","articleSection":"Politics",
              "image":{"@type":"ImageObject","contentUrl":"/photos/story.jpg"},
              "isAccessibleForFree":false,"description":"The publisher's article preview."
            }</script>
        """.trimIndent(),url)
        assertEquals("https://publisher.example/photos/story.jpg",result.imageUrl)
        assertEquals("Politics",result.topic)
        assertEquals(true,result.isPaywalled)
        assertEquals("The publisher's article preview.",result.description)
    }

    @Test fun findsArticlesInGraphsAndAcceptsImageArrays() {
        val result=parseNewsArticleMetadata("""
            <script type="application/ld+json">{"@graph":[
              {"@type":"Organization","image":"https://publisher.example/logo.png"},
              {"@type":["NewsArticle","CreativeWork"],"image":[{"url":"https://images.publisher.example/story.jpg"}],
               "articleSection":["Science"],"isAccessibleForFree":"True"}
            ]}</script>
        """.trimIndent(),url)
        assertEquals("https://images.publisher.example/story.jpg",result.imageUrl)
        assertEquals("Science",result.topic)
        assertEquals(false,result.isPaywalled)
    }

    @Test fun detectsPartiallyPaywalledArticles() {
        val result=parseNewsArticleMetadata("""
            <script type="application/ld+json">{"@type":"NewsArticle","isAccessibleForFree":true,
              "hasPart":[{"@type":"WebPageElement","isAccessibleForFree":false}]}</script>
        """.trimIndent(),url)
        assertEquals(true,result.isPaywalled)
    }

    @Test fun subscriptionFooterAndMissingMetadataDoNotProveAPaywall() {
        val result=parseNewsArticleMetadata("<p>Subscribe to our newsletter.</p><footer>Subscribe today</footer>",url)
        assertNull(result.isPaywalled)
        assertEquals("",result.imageUrl)
    }

    @Test fun genericSiteLogosAreNotUsedAsArticleImages() {
        val result=parseNewsArticleMetadata("""<meta property="og:type" content="website"><meta property="og:image" content="/logo.jpg">""",url)
        assertEquals("",result.imageUrl)
    }

    @Test fun readsArticleOpenGraphWithoutDependingOnAttributeOrder() {
        val result=parseNewsArticleMetadata("""
            <meta content="article" property="og:type">
            <meta content="/photo.jpg?size=800&amp;crop=wide" property="og:image">
            <meta content="Health" property="article:section">
            <meta content="false" itemprop="isAccessibleForFree">
        """.trimIndent(),url)
        assertEquals("https://publisher.example/photo.jpg?size=800&crop=wide",result.imageUrl)
        assertEquals("Health",result.topic)
        assertEquals(true,result.isPaywalled)
    }

    @Test fun rejectsLocalAndInsecureImageReferences() {
        listOf("file:///private/photo.jpg","http://images.example/photo.jpg","javascript:alert(1)").forEach { image ->
            val result=parseNewsArticleMetadata("""<script type="application/ld+json">{"@type":"NewsArticle","image":"$image"}</script>""",url)
            assertEquals("",result.imageUrl)
        }
    }

    @Test fun selectsACardSizedPublisherVariantOfTheActualPhoto() {
        val result=parseNewsArticleMetadata("""
            <meta property="og:type" content="article"><meta property="og:image" content="/photo.jpg">
            <picture><source srcset="/photo.jpg?w=480 480w, /photo.jpg?w=960 960w, /photo.jpg?w=1920 1920w"></picture>
        """.trimIndent(),url)
        assertEquals("https://publisher.example/photo.jpg?w=960",result.imageUrl)
    }

    @Test fun responsiveImagesDoNotSubstituteADifferentPhotoOrCrop() {
        val result=parseNewsArticleMetadata("""
            <meta property="og:type" content="article"><meta property="og:image" content="/proxy?photo=story&amp;crop=wide">
            <img srcset="/proxy?photo=other&amp;crop=wide&amp;w=960 960w">
            <img srcset="/proxy?photo=story&amp;crop=square&amp;w=960 960w">
            <img srcset="http://publisher.example/proxy?photo=story&amp;crop=wide&amp;w=960 960w">
        """.trimIndent(),url)
        assertEquals("https://publisher.example/proxy?photo=story&crop=wide",result.imageUrl)
    }

    @Test fun responsiveImagesHandleCommasInsideImageUrlsAndRelativePaths() {
        val result=parseNewsArticleMetadata("""
            <meta property="og:type" content="article"><meta property="og:image" content="/photo.jpg?crop=0,0,100,100">
            <img data-srcset="/photo.jpg?crop=0,0,100,100&amp;w=480 480w, /photo.jpg?crop=0,0,100,100&amp;w=800 800w">
        """.trimIndent(),url)
        assertEquals("https://publisher.example/photo.jpg?crop=0,0,100,100&w=800",result.imageUrl)
    }

    @Test fun oldBookmarksRemainCompatibleAndNewDetailsRoundTrip() {
        val original=NewsArticle("Title","Summary",url,"Publisher","Today")
        val legacy=JSONObject().put("title",original.title).put("summary",original.summary).put("link",url)
            .put("source",original.source).put("published",original.published)
        assertEquals(original,NewsArticle.fromJson(legacy))
        val enriched=original.copy(imageUrl="https://publisher.example/photo.jpg",topic="Politics",isPaywalled=true,publisherUrl=url)
        assertEquals(enriched,NewsArticle.fromJson(enriched.toJson()))
        assertNull(NewsArticle.fromJson(original.toJson()).isPaywalled)
    }

    @Test fun fallsBackToBroadTopicsWithoutGuessingAccessStatus() {
        assertEquals("Politics",fallbackNewsTopic("Message ahead of separation referendum"))
        assertEquals("Sports",fallbackNewsTopic("Hockey championship begins"))
        assertEquals("World",fallbackNewsTopic("Latest developments","World"))
    }
}
