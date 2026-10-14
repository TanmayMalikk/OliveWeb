package com.olive.app

import android.view.View
import android.view.InputDevice
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import com.olive.app.data.NewsArticle
import com.olive.app.ui.OliveTheme
import com.olive.app.ui.covers.news.NewsArticleReader
import com.olive.app.ui.covers.news.NewsArticleCard
import com.olive.app.data.NewsMetadataRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import android.util.Base64
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

class NewsReaderUiTest:OliveUiTestBase() {
    @Test fun fullStoryNavigationBookmarksAndBackStayInTheApp() {
        val saved=mutableStateOf(false)
        val opened=mutableStateOf(false)
        var closed=false
        val article=NewsArticle("Community garden brings neighbours together for a greener future",
            "Volunteers are turning an unused corner of the neighbourhood into a shared garden, with fresh produce and a welcoming space for everyone.",
            "https://publisher.example/story","Test Gazette","Tue, 13 Oct 2026 10:00:00 GMT")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            compose.activity.setContent {
                OliveTheme {
                    if(opened.value) NewsArticleReader(article,saved.value,true,Modifier.fillMaxSize(),
                        onBack={closed=true},onBookmark={saved.value=!saved.value})
                    else Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
                        NewsArticleCard(article,"English",saved.value,onOpen={opened.value=true},
                            onBookmark={saved.value=!saved.value})
                    }
                }
            }
        }
        waitFor("news_article_card")
        compose.onNodeWithTag("news_article_topic",useUnmergedTree=true).onChildren().assertAny(hasText("Environment"))
        compose.onNodeWithTag("news_article_paywall",useUnmergedTree=true).assertDoesNotExist()
        if(InstrumentationRegistry.getArguments().getString("captureNewsCard")=="true") {
            compose.waitForIdle()
            val rendered=AtomicBoolean(false)
            instrumentation.runOnMainSync {
                val root=compose.activity.window.decorView
                root.viewTreeObserver.registerFrameCommitCallback { rendered.set(true) }
                root.invalidate()
            }
            compose.waitUntil(5_000) { rendered.get() }
            val preview=File(instrumentation.targetContext.getExternalFilesDir("reader-preview"),"newspaper-card.png")
            val bitmap=compose.onNodeWithTag("news_article_card").captureToImage().asAndroidBitmap()
            preview.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        }
        compose.onNodeWithText(article.title).performClick()
        waitFor("news_article_webview")
        lateinit var browser:WebView
        val finishedUrl=AtomicReference("")
        instrumentation.runOnMainSync {
            browser=findBrowser(compose.activity.window.decorView)!!
            browser.stopLoading()
            val readerClient=browser.webViewClient
            // Deterministic publisher responses exercise the real reader without relying on live news.
            browser.webViewClient=object:WebViewClient() {
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                    if(request.url.host!="publisher.example") return null
                    val body=if(request.url.path=="/next") "Related story final paragraph" else
                        (1..20).joinToString("") { "<p>Full article paragraph $it.</p>" }+
                            "<a href='https://publisher.example/next'>Related story</a>"
                    return WebResourceResponse("text/html","UTF-8",ByteArrayInputStream("<html><body>$body</body></html>".toByteArray()))
                }
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=readerClient.shouldOverrideUrlLoading(view,request)
                override fun onPageStarted(view:WebView,url:String,favicon:android.graphics.Bitmap?)=readerClient.onPageStarted(view,url,favicon)
                override fun onPageFinished(view:WebView,url:String) {
                    readerClient.onPageFinished(view,url);finishedUrl.set(url)
                }
                override fun doUpdateVisitedHistory(view:WebView,url:String,isReload:Boolean)=readerClient.doUpdateVisitedHistory(view,url,isReload)
            }
            browser.loadUrl(article.link)
        }
        fun waitForBody(expected:String,path:String) {
            val body=AtomicReference("")
            compose.waitUntil(20_000) {
                instrumentation.runOnMainSync { browser.evaluateJavascript("document.body.innerText") { body.set(it) } }
                body.get().contains(expected) && finishedUrl.get().endsWith(path)
            }
        }
        waitForBody("Full article paragraph 20.","/story")
        compose.onNodeWithTag("news_reader_bookmark").performClick()
        assertTrue(saved.value)
        compose.onNodeWithContentDescription("Remove bookmark").assertExists()
        val linkPosition=AtomicReference("")
        instrumentation.runOnMainSync {
            browser.evaluateJavascript("(function(){const a=document.querySelector('a');a.scrollIntoView({block:'center'});const r=a.getBoundingClientRect();return (r.left+r.width/2)+'|'+(r.top+r.height/2)})()") { linkPosition.set(it) }
        }
        compose.waitUntil(5_000) { linkPosition.get().contains('|') }
        val position=linkPosition.get().trim('"').split('|').map(String::toFloat)
        // A real tap creates a history entry; script-only redirects can be skipped by Chromium.
        onView(isAssignableFrom(WebView::class.java)).perform(GeneralClickAction(Tap.SINGLE,{ view ->
            val location=IntArray(2);view.getLocationOnScreen(location)
            val density=view.resources.displayMetrics.density
            floatArrayOf(location[0]+position[0]*density,location[1]+position[1]*density)
        },Press.FINGER,InputDevice.SOURCE_TOUCHSCREEN,0))
        waitForBody("Related story final paragraph","/next")
        compose.waitForIdle()
        compose.onNodeWithTag("news_reader_host").assertTextEquals("publisher.example")
        instrumentation.runOnMainSync {
            assertTrue("Related links must create reader history",browser.canGoBack())
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        waitForBody("Full article paragraph 20.","/story")
        assertEquals("com.olive.app",compose.activity.packageName)
        compose.onNodeWithTag("news_reader_back").performClick()
        assertTrue(closed)

        // Opt-in smoke check for a live RSS link; normal test runs remain independent of publishers.
        InstrumentationRegistry.getArguments().getString("newsReaderLiveUrl")?.let { url ->
            instrumentation.runOnMainSync { browser.loadUrl(url) }
            val livePage=AtomicReference("")
            compose.waitUntil(60_000) {
                instrumentation.runOnMainSync {
                    browser.evaluateJavascript("location.hostname+'|'+document.body.innerText.length") { livePage.set(it) }
                }
                val result=livePage.get().trim('"')
                val host=result.substringBefore('|')
                host.isNotBlank() && !host.endsWith("google.com") &&
                    (result.substringAfter('|').toIntOrNull() ?: 0)>800 &&
                    finishedUrl.get().contains(host)
            }
            println("Live article loaded in app: ${livePage.get()}")
            val visible=AtomicBoolean(false)
            instrumentation.runOnMainSync {
                browser.postVisualStateCallback(1,object:WebView.VisualStateCallback() {
                    override fun onComplete(requestId:Long) { browser.postOnAnimation { visible.set(true) } }
                })
            }
            compose.waitUntil(10_000) { visible.get() }
            instrumentation.waitForIdleSync()
            val screenshot=instrumentation.uiAutomation.takeScreenshot()
            val preview=File(instrumentation.targetContext.getExternalFilesDir("reader-preview"),"live-reader.png")
            preview.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        }

        InstrumentationRegistry.getArguments().getString("newsCardLiveFixture")?.let { encoded ->
            val liveArticle=NewsArticle.fromJson(JSONObject(String(Base64.decode(encoded,Base64.DEFAULT),Charsets.UTF_8)))
            val metadata=runBlocking { NewsMetadataRepository(instrumentation.targetContext).load(liveArticle) }
            assertTrue("Use the actual publisher's article image",metadata.imageUrl.isNotBlank())
            val enriched=metadata.applyTo(liveArticle)
            instrumentation.runOnMainSync {
                compose.activity.setContent {
                    OliveTheme { Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
                        NewsArticleCard(enriched,"English",false,onOpen={},onBookmark={})
                    } }
                }
            }
            compose.waitUntil(30_000) {
                compose.onAllNodesWithTag("news_article_image",useUnmergedTree=true).fetchSemanticsNodes().any {
                    it.config.getOrElse(SemanticsProperties.StateDescription) { "" }=="Article photo loaded"
                }
            }
            val preview=File(instrumentation.targetContext.getExternalFilesDir("reader-preview"),"live-news-card.png")
            val bitmap=compose.onNodeWithTag("news_article_card").captureToImage().asAndroidBitmap()
            preview.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            println("Article metadata: publisher=${enriched.publisherUrl}, topic=${enriched.topic}, paywall=${enriched.isPaywalled}, image=${enriched.imageUrl}")
        }
    }

    @Test fun confirmedPaywallIsVisibleBesideTheArticleTopic() {
        val article=NewsArticle("An article requiring a subscription","Publisher preview.","https://publisher.example/paid", "Test Publisher","",topic="Politics",isPaywalled=true)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setContent {
                OliveTheme { Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
                    NewsArticleCard(article,"English",false,onOpen={},onBookmark={})
                } }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("news_article_paywall",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("news_article_topic",useUnmergedTree=true).onChildren().assertAny(hasText("Politics"))
        compose.onNodeWithTag("news_article_paywall",useUnmergedTree=true).onChildren().assertAny(hasText("Paywall"))
    }

    private fun findBrowser(view:View):WebView? {
        if(view is WebView) return view
        if(view is ViewGroup) for(index in 0 until view.childCount) {
            findBrowser(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
