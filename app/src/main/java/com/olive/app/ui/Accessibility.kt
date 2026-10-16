package com.olive.app.ui

import android.content.Context
import android.content.res.Configuration
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
import androidx.compose.ui.platform.LocalConfiguration
import com.olive.app.R
import java.util.Locale
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
@Composable internal fun AccessibleMessage(text:String,modifier:Modifier=Modifier,color:Color=LocalContentColor.current,
                                         requestFocus:Boolean=true,style:TextStyle=LocalTextStyle.current) {
    val context=LocalContext.current
    val spoken=accessibilityText(text)
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
        androidx.compose.material3.Text(text,color=color,style=style,modifier=Modifier.semantics { hideFromAccessibility() })
        AndroidView(factory={native},modifier=Modifier.matchParentSize(),update={
            it.contentDescription=spoken
            it.importantForAccessibility=if(visible) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        })
    }
    LaunchedEffect(spoken,requestFocus,visible) {
        val manager=context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if(visible && requestFocus && manager.isEnabled && manager.isTouchExplorationEnabled)
            coordinator.request(native) { bringIntoView.bringIntoView() }
    }
}

// Direct resource references also let Android validate and retain these dynamically selected labels.
private val accessibilityResources=mapOf(
    "Home" to R.string.home,
    "Welcome" to R.string.welcome,
    "Create account" to R.string.create_account,
    "Sign in" to R.string.sign_in,
    "Your profile" to R.string.your_profile,
    "Secure this device" to R.string.secure_this_device,
    "Enter PIN" to R.string.enter_pin,
    "Messages" to R.string.messages,
    "Settings" to R.string.settings,
    "Clear search" to R.string.clear_search,
    "Refresh" to R.string.refresh,
    "Close" to R.string.close,
    "PIN" to R.string.a11y_pin,
    "Digits entered" to R.string.a11y_digits_entered,
    "Delete digit" to R.string.a11y_delete_digit,
    "Unlock" to R.string.a11y_unlock,
    "Step" to R.string.a11y_step,
    "Completed requirements" to R.string.a11y_completed_requirements,
    "Equals" to R.string.a11y_equals,
    "Clear all" to R.string.a11y_clear_all,
    "Minus" to R.string.a11y_minus,
    "Multiply" to R.string.a11y_multiply,
    "Divide" to R.string.a11y_divide,
    "Parentheses" to R.string.a11y_parentheses,
    "Percent" to R.string.a11y_percent,
    "Decimal point" to R.string.a11y_decimal_point,
    "Graph" to R.string.a11y_graph,
    "Zoom" to R.string.a11y_zoom,
    "Zoom in" to R.string.a11y_zoom_in,
    "Zoom out" to R.string.a11y_zoom_out,
    "Pan left" to R.string.a11y_pan_left,
    "Pan right" to R.string.a11y_pan_right,
    "Pan up" to R.string.a11y_pan_up,
    "Pan down" to R.string.a11y_pan_down,
    "Show" to R.string.a11y_show,
    "Hide" to R.string.a11y_hide,
    "password" to R.string.a11y_password,
    "trigger word" to R.string.a11y_trigger_word,
    "Quick exit" to R.string.a11y_quick_exit,
    "Returned to cover" to R.string.a11y_returned_to_cover,
    "Refresh article" to R.string.a11y_refresh_article,
    "Back to stories" to R.string.a11y_back_to_stories,
    "Save article" to R.string.a11y_save_article,
    "Remove bookmark" to R.string.a11y_remove_bookmark,
    "Message actions" to R.string.a11y_message_actions,
    "Add one" to R.string.a11y_add_one,
    "Remove one" to R.string.a11y_remove_one,
    "Square root" to R.string.a11y_square_root,
    "Pi" to R.string.a11y_pi,
    "Power" to R.string.a11y_power,
    "Factorial" to R.string.a11y_factorial,
    "Inverse functions" to R.string.a11y_inverse_functions,
    "Sine" to R.string.a11y_sine,
    "Cosine" to R.string.a11y_cosine,
    "Tangent" to R.string.a11y_tangent,
    "Inverse sine" to R.string.a11y_inverse_sine,
    "Inverse cosine" to R.string.a11y_inverse_cosine,
    "Inverse tangent" to R.string.a11y_inverse_tangent,
    "Squared" to R.string.a11y_squared,
    "Exponential" to R.string.a11y_exponential,
    "Ten to the power" to R.string.a11y_ten_to_the_power,
    "Natural logarithm" to R.string.a11y_natural_logarithm,
    "Logarithm" to R.string.a11y_logarithm,
)

@Composable internal fun accessibilityText(text:String):String {
    val context=LocalContext.current
    val configuration=LocalConfiguration.current
    return remember(text,context,configuration) { localizedAccessibilityText(text,configuration.locales[0].toLanguageTag(),context) }
}

internal fun localizedAccessibilityText(text:String,language:String,context:Context):String {
    val tag=if("-" in language) language else AppLocaleManager.getLanguageTag(language)
    val locale=Locale.forLanguageTag(tag.replace("-r", "-"))
    accessibilityResources[text]?.let { id ->
        val resources=if(context.resources.configuration.locales[0].language==locale.language) context.resources
            else context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) }).resources
        return resources.getString(id)
    }
    if(locale.language=="en") return text
    listOf("Show ","Hide ","Add one ","Remove one ").firstOrNull { text.startsWith(it) }?.let { prefix ->
        return localizedAccessibilityText(prefix.trim(),language,context)+": "+localizedAccessibilityText(text.removePrefix(prefix),language,context)
    }
    return text
}

// Decorative icons stay silent; mapped action labels use Android's current resource locale.
// Labels without a matching resource keep their original text.
@Composable internal fun LocalizedIcon(imageVector:ImageVector,contentDescription:String?,
    modifier:Modifier=Modifier,tint:Color=LocalContentColor.current) {
    Icon(imageVector,contentDescription?.let { accessibilityText(it) },modifier,tint)
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
