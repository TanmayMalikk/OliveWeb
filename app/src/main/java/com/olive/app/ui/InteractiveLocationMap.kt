package com.olive.app.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.olive.app.R
import com.olive.app.data.MapLocationPoint
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
@Composable internal fun InteractiveLocationMap(points:List<MapLocationPoint>,modifier:Modifier=Modifier,
    ownPointUid:String?=points.firstOrNull()?.uid) {
    val description=stringResource(R.string.location_map_accessibility)
    val lastKnownLabel=stringResource(R.string.location_last_known)
    val webView=remember { arrayOfNulls<WebView>(1) }
    var loadState by remember { mutableStateOf(MapLoadState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        delay(15_000)
        if(loadState==MapLoadState.Loading) loadState=MapLoadState.Error
    }

    fun updatePoints(view:WebView,moveCamera:Boolean) {
        val encoded=JSONArray().apply { points.forEach { point -> put(JSONObject().apply {
            put("uid",point.uid);put("name",point.name);put("latitude",point.latitude);put("longitude",point.longitude)
            put("label",if(point.lastKnown) "${point.name} · $lastKnownLabel" else point.name)
            put("live",point.live);put("own",point.uid==ownPointUid);point.accuracy?.let { put("accuracy",it.toDouble()) }
            put("trail",JSONArray().apply { point.trail.forEach { trailPoint -> put(JSONObject().apply {
                put("latitude",trailPoint.latitude);put("longitude",trailPoint.longitude);put("updatedAt",trailPoint.updatedAt)
            }) } })
        }) } }
        view.evaluateJavascript("window.oliveSetPoints($encoded,$moveCamera);",null)
    }
    fun resizeMap(view:WebView) {
        if(view.height<=0) return
        val cssHeight=(view.height/view.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        view.evaluateJavascript("window.oliveResizeTo&&window.oliveResizeTo($cssHeight);",null)
    }

    LaunchedEffect(points,loadState,attempt) {
        if(loadState==MapLoadState.Ready) webView[0]?.let { updatePoints(it,false) }
    }

    Box(modifier.semantics { contentDescription=description }) {
        key(attempt) {
            AndroidView(factory={ context ->
                WebView(context).apply {
                    webView[0]=this
                    setBackgroundColor(Color.rgb(241,237,228))
                    settings.javaScriptEnabled=true
                    settings.domStorageEnabled=false
                    settings.allowContentAccess=false
                    settings.allowFileAccess=true
                    settings.allowFileAccessFromFileURLs=false
                    settings.allowUniversalAccessFromFileURLs=false
                    settings.setSupportZoom(false)
                    webChromeClient=object:WebChromeClient() {
                        override fun onReceivedTitle(view:WebView,title:String?) {
                            if(title==MAP_READY_TITLE) loadState=MapLoadState.Ready
                        }
                    }
                    webViewClient=object:WebViewClient() {
                        override fun onPageFinished(view:WebView,url:String) {
                            view.post { resizeMap(view);updatePoints(view,true) }
                        }
                        override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=true
                        override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError) {
                            if(request.isForMainFrame) loadState=MapLoadState.Error
                        }
                    }
                    addOnLayoutChangeListener { changed,_,top,_,bottom,_,oldTop,_,oldBottom ->
                        if(bottom-top!=oldBottom-oldTop) resizeMap(changed as WebView)
                    }
                    loadUrl("file:///android_asset/map/map.html")
                }
            },update={ view -> resizeMap(view);if(loadState==MapLoadState.Ready) updatePoints(view,false) },
                onRelease={ view -> view.stopLoading();view.destroy();if(webView[0]===view) webView[0]=null },
                modifier=Modifier.fillMaxSize().testTag("location_map_view"))
        }
        when(loadState) {
            MapLoadState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).testTag("map_loading"))
            MapLoadState.Error -> Column(Modifier.align(Alignment.Center).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,
                verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.location_map_unavailable),style=MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick={loadState=MapLoadState.Loading;attempt++},modifier=Modifier.testTag("map_retry")) {
                    Text(stringResource(R.string.retry))
                }
            }
            MapLoadState.Ready -> Column(Modifier.align(Alignment.TopEnd).padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                SmallFloatingActionButton(onClick={webView[0]?.evaluateJavascript("window.oliveZoomIn();",null)},
                    modifier=Modifier.testTag("map_zoom_in")) {
                    Icon(Icons.Outlined.Add,stringResource(R.string.location_zoom_in))
                }
                SmallFloatingActionButton(onClick={webView[0]?.evaluateJavascript("window.oliveZoomOut();",null)},
                    modifier=Modifier.testTag("map_zoom_out")) {
                    Icon(Icons.Outlined.Remove,stringResource(R.string.location_zoom_out))
                }
                SmallFloatingActionButton(onClick={webView[0]?.evaluateJavascript("window.oliveRecenter();",null)},
                    modifier=Modifier.testTag("recenter_map")) {
                    Icon(Icons.Outlined.MyLocation,stringResource(R.string.recenter_map))
                }
            }
        }
    }
}

private enum class MapLoadState { Loading, Ready, Error }
private const val MAP_READY_TITLE="olive-map-ready"
