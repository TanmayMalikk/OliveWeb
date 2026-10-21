package com.olive.app

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.olive.app.data.LocationTrailPoint
import com.olive.app.data.MapLocationPoint
import com.olive.app.ui.InteractiveLocationMap
import com.olive.app.ui.OliveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocationMapMotionUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun markerAndTrailUpdateWithoutRecreatingMap() {
        val started=System.currentTimeMillis()
        val first=LocationTrailPoint(53.5461,-113.4937,started)
        val points=mutableStateOf(listOf(MapLocationPoint("moving-user","You",first.latitude,first.longitude,
            first.updatedAt,true,8f,trail=listOf(first))))
        compose.activity.setContent { OliveTheme { InteractiveLocationMap(points.value,Modifier.fillMaxSize(),"moving-user") } }
        compose.waitUntil(25_000) { compose.onAllNodesWithTag("map_zoom_in").fetchSemanticsNodes().isNotEmpty() }
        var original:WebView?=null
        compose.runOnIdle { original=webView() }
        assertNotNull(original)
        waitForJavaScript("points.length>0&&points[0].latitude===53.5461&&!!markers['moving-user']")
        compose.onNodeWithTag("follow_user_location").performClick()
        compose.waitForIdle();Thread.sleep(300)

        val trail=listOf(first,
            LocationTrailPoint(53.5465,-113.4931,started+2_000),
            LocationTrailPoint(53.5470,-113.4925,started+4_000))
        compose.runOnIdle { points.value=listOf(MapLocationPoint("moving-user","You",trail.last().latitude,
            trail.last().longitude,trail.last().updatedAt,true,7f,trail=trail)) }
        compose.waitForIdle()
        waitForJavaScript("points.length>0&&points[0].latitude===53.547&&!!trails['moving-user']")
        Thread.sleep(1_900)

        var sameWebView=false
        compose.runOnIdle { sameWebView=original===webView() }
        assertTrue(sameWebView)
        val state=evaluate("JSON.stringify({markers:Object.keys(markers).length,trail:trails['moving-user']?trails['moving-user'].getLatLngs().length:-1,latitude:points.length?points[0].latitude:0,follow:followEnabled,centered:markers['moving-user']?map.distance(map.getCenter(),markers['moving-user'].getLatLng())<10:false})")
        assertTrue(state,state.contains("\\\"markers\\\":1"))
        assertTrue(state,state.contains("\\\"trail\\\":3"))
        assertTrue(state,state.contains("53.547"))
        assertTrue(state,state.contains("\\\"follow\\\":true"))
        assertTrue(state,state.contains("\\\"centered\\\":true"))

        compose.runOnIdle { points.value=listOf(points.value.single().copy(live=false,lastKnown=true)) }
        compose.waitForIdle();Thread.sleep(250)
        val lastKnown=evaluate("JSON.stringify({red:!!markers['moving-user'].getElement().querySelector('.olive-last-known-dot'),animation:getComputedStyle(markers['moving-user'].getElement().firstChild).animationName})")
        assertTrue(lastKnown.contains("\\\"red\\\":true"))
        assertTrue(lastKnown.contains("\\\"animation\\\":\\\"none\\\""))
    }

    private fun webView():WebView?=findWebView(compose.activity.window.decorView)

    private fun findWebView(view:View):WebView? {
        if(view is WebView) return view
        if(view is ViewGroup) for(index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun evaluate(script:String):String {
        val latch=CountDownLatch(1);var result=""
        compose.activity.runOnUiThread { webView()?.evaluateJavascript(script) { value -> result=value;latch.countDown() } }
        assertTrue(latch.await(5,TimeUnit.SECONDS))
        assertEquals(false,result.isBlank())
        return result
    }

    private fun waitForJavaScript(condition:String) {
        val deadline=System.currentTimeMillis()+8_000L
        while(System.currentTimeMillis()<deadline) {
            if(evaluate("Boolean($condition)")=="true") return
            Thread.sleep(100)
        }
        throw AssertionError("JavaScript condition was not met: $condition")
    }
}
