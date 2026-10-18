package com.olive.app.ui.covers.news
import com.olive.app.ui.rememberResourceText

import com.olive.app.ui.LocalizedIcon as Icon
import androidx.compose.ui.res.stringResource
import com.olive.app.R
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.olive.app.data.NewsArticle
import com.olive.app.ui.Cream
import com.olive.app.ui.Muted
import com.olive.app.ui.OliveGreen
import java.net.URI
import androidx.compose.ui.platform.LocalContext
import com.olive.app.data.NewsMetadataRepository
import com.olive.app.data.isSecureNewsUrl

// Google News and publisher redirects stay inside the reader; local files and app intents do not open.
internal fun isNewsReaderUrlAllowed(url:String):Boolean=isSecureNewsUrl(url)

// Show the publisher page in an in-app WebView; ads and subscription requirements remain controlled by the publisher.
@Composable internal fun NewsArticleReader(article:NewsArticle,saved:Boolean,active:Boolean,
    modifier:Modifier=Modifier,onBack:()->Unit,onBookmark:()->Unit) {
    val uiText=rememberResourceText()
    val context=LocalContext.current
    val readerUrl=remember(article.link) {
        article.publisherUrl.takeIf(::isSecureNewsUrl)
            ?: NewsMetadataRepository(context.applicationContext).cached(article.link)?.publisherUrl?.takeIf(::isSecureNewsUrl)
            ?: article.link
    }
    var browser by remember(article.link) { mutableStateOf<WebView?>(null) }
    var progress by remember(article.link) { mutableIntStateOf(0) }
    var failed by remember(article.link) { mutableStateOf(!isNewsReaderUrlAllowed(readerUrl)) }
    var currentUrl by remember(article.link) { mutableStateOf(readerUrl) }
    var publisherReached by remember(article.link) { mutableStateOf(false) }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val host=runCatching { URI(currentUrl).host }.getOrNull().orEmpty()

    BackHandler(active) {
        val view=browser
        if(view!=null && view.canGoBack()) view.goBack() else onBack()
    }
    DisposableEffect(browser,active,lifecycle) {
        val view=browser
        fun updateVisibility() {
            if(active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view?.onResume()
            else view?.onPause()
        }
        val observer=LifecycleEventObserver { _,_ -> updateVisibility() }
        lifecycle.addObserver(observer)
        updateVisibility()
        onDispose { lifecycle.removeObserver(observer);view?.onPause() }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick=onBack,modifier=Modifier.testTag("news_reader_back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack,null)
                Spacer(Modifier.width(5.dp));Text(stringResource(R.string.back_to_stories))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick={browser?.reload()},enabled=browser!=null && !failed) {
                Icon(Icons.Outlined.Refresh,uiText(R.string.a11y_refresh_article))
            }
            IconButton(onClick=onBookmark,modifier=Modifier.testTag("news_reader_bookmark")) {
                Icon(if(saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                    if(saved) uiText(R.string.a11y_remove_bookmark) else uiText(R.string.a11y_save_article),tint=OliveGreen)
            }
        }
        Text(article.title,modifier=Modifier.padding(horizontal=20.dp),
            style=MaterialTheme.typography.titleMedium,maxLines=2)
        Text(host,color=Muted,style=MaterialTheme.typography.labelSmall,
            modifier=Modifier.padding(horizontal=20.dp,vertical=6.dp).testTag("news_reader_host"))
        if(progress<100 && !failed) LinearProgressIndicator(progress={progress/100f},modifier=Modifier.fillMaxWidth())
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(article.link) {
                AndroidView(modifier=Modifier.fillMaxSize().testTag("news_article_webview"),
                    factory={context ->
                        WebView(context).apply {
                            // Required for interactive publisher articles. Remote scripts still carry XSS risk, so
                            // keep the warning visible; allow HTTPS only, with no native bridge or file/content access.
                            settings.javaScriptEnabled=true
                            settings.domStorageEnabled=true
                            settings.allowFileAccess=false
                            settings.allowContentAccess=false
                            settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.safeBrowsingEnabled=true
                            settings.javaScriptCanOpenWindowsAutomatically=false
                            settings.setSupportMultipleWindows(false)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this,false)
                            webChromeClient=object:WebChromeClient() {
                                override fun onProgressChanged(view:WebView,newProgress:Int) { progress=newProgress }
                            }
                            webViewClient=object:WebViewClient() {
                                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                                    val blocked=!isNewsReaderUrlAllowed(request.url.toString())
                                    if(blocked && request.isForMainFrame) failed=true
                                    return blocked
                                }
                                override fun onPageStarted(view:WebView,url:String,favicon:Bitmap?) {
                                    currentUrl=url;failed=false;progress=0
                                }
                                override fun onPageFinished(view:WebView,url:String) {
                                    currentUrl=url;progress=100
                                }
                                override fun doUpdateVisitedHistory(view:WebView,url:String,isReload:Boolean) {
                                    val pageHost=runCatching { URI(url).host }.getOrNull()
                                    if(!publisherReached && pageHost!=null && pageHost!="news.google.com") {
                                        // Skip the Google redirect only; direct publisher links keep normal history.
                                        if(runCatching { URI(article.link).host }.getOrNull()=="news.google.com") view.clearHistory()
                                        publisherReached=true
                                    }
                                    currentUrl=url
                                }
                                override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError) {
                                    if(request.isForMainFrame) { failed=true;progress=100 }
                                }
                                override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse) {
                                    if(request.isForMainFrame) { failed=true;progress=100 }
                                }
                                override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:SslError) {
                                    handler.cancel();failed=true;progress=100
                                }
                            }
                            setDownloadListener { _,_,_,_,_ -> failed=true }
                            browser=this
                            if(isNewsReaderUrlAllowed(readerUrl)) loadUrl(readerUrl)
                        }
                    },
                    update={view ->
                        view.visibility=if(failed) View.INVISIBLE else View.VISIBLE
                        view.importantForAccessibility=if(active && !failed) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    },
                    onRelease={view ->
                        view.stopLoading();view.onPause();view.removeAllViews();view.destroy()
                        if(browser===view) browser=null
                    })
            }
            if(failed) Column(Modifier.fillMaxSize().background(Cream).padding(24.dp),
                verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                Text(uiText(R.string.ui_this_article_could_not_be_loaded_check_your_connection_and_try_ag))
                Spacer(Modifier.height(12.dp))
                Button(onClick={
                    if(isNewsReaderUrlAllowed(readerUrl)) {
                        failed=false;progress=0;browser?.loadUrl(readerUrl)
                    }
                },enabled=isNewsReaderUrlAllowed(readerUrl),modifier=Modifier.testTag("news_reader_retry")) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}
