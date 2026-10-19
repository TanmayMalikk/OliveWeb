package com.olive.app.ui

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.ViewGroup
import android.os.Bundle
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.viewinterop.AndroidView
import android.view.accessibility.AccessibilityManager
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import android.view.ViewTreeObserver
import kotlin.coroutines.resume

// Native accessibility focus is distinct from Compose/keyboard input focus.
// Coalesce simultaneous validation errors so the first field wins, rather than
// letting several errors scroll the form and steal focus from one another.
internal class AccessibilityFocusCoordinator(private val scope:CoroutineScope) {
    private data class Target(val view:View,val reveal:suspend ()->Unit)
    private val pending=mutableListOf<Target>()
    private var job:Job?=null
    fun request(view:View,reveal:suspend ()->Unit) {
        pending+=Target(view,reveal)
        if(job?.isActive==true) return
        job=scope.launch {
            try {
                withFrameNanos { }
                val target=pending.filter { it.view.isAttachedToWindow && it.view.isShown && it.view.hasWindowFocus() && it.view.importantForAccessibility==View.IMPORTANT_FOR_ACCESSIBILITY_YES }.minByOrNull {
                    val position=IntArray(2);it.view.getLocationOnScreen(position);position[1]
                }
                pending.clear()
                if(target!=null) {
                    target.reveal()
                    withFrameNanos { }
                    // These explicit message/exit transitions must stop stale private
                    // speech. Never send the focus action into a background window.
                    if(target.view.isAttachedToWindow && target.view.isShown && target.view.hasWindowFocus())
                        target.view.performAccessibilityAction(AccessibilityActionCompat.ACTION_ACCESSIBILITY_FOCUS.id,null)
                }
            } finally {
                job=null
                val queued=pending.toList();pending.clear()
                queued.forEach { request(it.view,it.reveal) }
            }
        }
    }
}
internal val LocalAccessibilityFocusCoordinator=staticCompositionLocalOf<AccessibilityFocusCoordinator?> { null }
internal val LocalAccessibilityMessagesVisible=staticCompositionLocalOf { true }

// Keep the existing Compose typography/layout, with one native accessibility
// node over the same bounds so TalkBack receives an actual focus event. No
// deprecated announcement API or fabricated Compose focus state is used.
// Exit announcements can omit the visible text while retaining the native spoken focus event.
@Composable internal fun AccessibleMessage(text:String,modifier:Modifier=Modifier,color:Color=LocalContentColor.current,
                                         requestFocus:Boolean=true,style:TextStyle=LocalTextStyle.current,showText:Boolean=true) {
    val context=LocalContext.current
    val spoken=text
    val bringIntoView=remember { BringIntoViewRequester() }
    val scope=rememberCoroutineScope()
    val fallback=remember(scope) { AccessibilityFocusCoordinator(scope) }
    val coordinator=LocalAccessibilityFocusCoordinator.current ?: fallback
    val visible=LocalAccessibilityMessagesVisible.current
    val native=remember(context) { View(context).apply {
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setScreenReaderFocusable(this,true)
    } }
    Box(modifier.bringIntoViewRequester(bringIntoView)) {
        if(showText) androidx.compose.material3.Text(text,color=color,style=style,modifier=Modifier.semantics { hideFromAccessibility() })
        AndroidView(factory={native},modifier=Modifier.matchParentSize(),update={
            it.contentDescription=spoken
            it.importantForAccessibility=if(visible) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        })
    }
    LaunchedEffect(spoken,requestFocus,visible) {
        val manager=context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if(visible && requestFocus && manager.isEnabled && manager.isTouchExplorationEnabled) {
            // Android authentication owns another window. Deliver feedback only after this
            // window returns; disposal cancels the wait if quick exit changes the screen.
            native.awaitAccessibilityWindowFocus()
            coordinator.request(native) { bringIntoView.bringIntoView() }
        }
    }
}

private suspend fun View.awaitAccessibilityWindowFocus() {
    if(hasWindowFocus()) return
    suspendCancellableCoroutine<Unit> { continuation ->
        val observer=viewTreeObserver
        lateinit var listener:ViewTreeObserver.OnWindowFocusChangeListener
        fun remove() { if(observer.isAlive) observer.removeOnWindowFocusChangeListener(listener) }
        listener=ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if(focused && continuation.isActive) { remove();continuation.resume(Unit) }
        }
        observer.addOnWindowFocusChangeListener(listener)
        continuation.invokeOnCancellation { remove() }
        // Cover a focus change between the initial check and listener registration.
        if(hasWindowFocus() && continuation.isActive) { remove();continuation.resume(Unit) }
    }
}

// Callers resolve action labels from Android resources; decorative icons keep a null description.
@Composable internal fun LocalizedIcon(imageVector:ImageVector,contentDescription:String?,
    modifier:Modifier=Modifier,tint:Color=LocalContentColor.current) {
    Icon(imageVector,contentDescription,modifier,tint)
}

internal fun isAccessibilityUserInteraction(eventType:Int):Boolean = eventType in setOf(
    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
    AccessibilityEvent.TYPE_VIEW_CLICKED,
    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
    AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY,
)

// Observe the existing outer content view, preserving its delegate and the Compose/WebView delegates below it.
internal fun observeAccessibilityInteractions(view:ViewGroup,onInteraction:()->Unit) {
    val previous=ViewCompat.getAccessibilityDelegate(view)
    ViewCompat.setAccessibilityDelegate(view,object:AccessibilityDelegateCompat() {
        override fun onRequestSendAccessibilityEvent(host:ViewGroup,child:View,event:AccessibilityEvent):Boolean {
            val accepted=previous?.onRequestSendAccessibilityEvent(host,child,event) ?: super.onRequestSendAccessibilityEvent(host,child,event)
            if(accepted && isAccessibilityUserInteraction(event.eventType)) onInteraction()
            return accepted
        }
        override fun dispatchPopulateAccessibilityEvent(host:View,event:AccessibilityEvent):Boolean =
            previous?.dispatchPopulateAccessibilityEvent(host,event) ?: super.dispatchPopulateAccessibilityEvent(host,event)
        override fun getAccessibilityNodeProvider(host:View):AccessibilityNodeProviderCompat? =
            previous?.getAccessibilityNodeProvider(host) ?: super.getAccessibilityNodeProvider(host)
        override fun onInitializeAccessibilityEvent(host:View,event:AccessibilityEvent) {
            if(previous!=null) previous.onInitializeAccessibilityEvent(host,event) else super.onInitializeAccessibilityEvent(host,event)
        }
        override fun onInitializeAccessibilityNodeInfo(host:View,info:AccessibilityNodeInfoCompat) {
            if(previous!=null) previous.onInitializeAccessibilityNodeInfo(host,info) else super.onInitializeAccessibilityNodeInfo(host,info)
        }
        override fun onPopulateAccessibilityEvent(host:View,event:AccessibilityEvent) {
            if(previous!=null) previous.onPopulateAccessibilityEvent(host,event) else super.onPopulateAccessibilityEvent(host,event)
        }
        override fun performAccessibilityAction(host:View,action:Int,args:Bundle?):Boolean =
            previous?.performAccessibilityAction(host,action,args) ?: super.performAccessibilityAction(host,action,args)
        override fun sendAccessibilityEvent(host:View,eventType:Int) {
            if(previous!=null) previous.sendAccessibilityEvent(host,eventType) else super.sendAccessibilityEvent(host,eventType)
        }
        override fun sendAccessibilityEventUnchecked(host:View,event:AccessibilityEvent) {
            if(previous!=null) previous.sendAccessibilityEventUnchecked(host,event) else super.sendAccessibilityEventUnchecked(host,event)
        }
    })
}
