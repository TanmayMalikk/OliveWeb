package com.olive.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.olive.app.R

// The overlay owns both corners so an expanded menu never blocks the real Quick exit control.
@Composable internal fun SafeSupportControls(session:SessionViewModel,exit:()->Unit,showExit:Boolean,
    showSupport:Boolean,expanded:Boolean,onExpanded:(Boolean)->Unit) {
    if(!showExit && !showSupport) return
    BackHandler(enabled=expanded) { onExpanded(false) }
    val menuTitle=stringResource(R.string.support_menu)
    val iconRotation by animateFloatAsState(if(expanded) 45f else 0f,
        animationSpec=tween(240,easing=FastOutSlowInEasing),label="support button rotation")
    BoxWithConstraints(Modifier.fillMaxSize().then(if(expanded) Modifier.semantics {
        paneTitle=menuTitle;isTraversalGroup=true
    } else Modifier)) {
        AnimatedVisibility(visible=expanded,enter=fadeIn(tween(180)),exit=fadeOut(tween(140))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.22f))
                .clickable(enabled=expanded) { onExpanded(false) }.clearAndSetSemantics {})
        }
        // Grow from the trigger, then reveal the nearest option first. Compose respects the system animation scale.
        AnimatedVisibility(visible=expanded,
            modifier=Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start=12.dp,end=12.dp,bottom=92.dp)
                .widthIn(max=360.dp).heightIn(max=(maxHeight-116.dp).coerceAtLeast(0.dp)),
            enter=fadeIn(tween(160))+scaleIn(tween(260,easing=FastOutSlowInEasing),initialScale=.92f,
                transformOrigin=TransformOrigin(0f,1f)),
            exit=fadeOut(tween(120))+scaleOut(tween(140),targetScale=.96f,transformOrigin=TransformOrigin(0f,1f))) {
            // Closing options immediately leave the accessibility tree even while their visual exit finishes.
            Column(Modifier.then(if(expanded) Modifier else Modifier.clearAndSetSemantics {})
                .verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                SupportAction(R.string.support_walkthrough,Icons.AutoMirrored.Outlined.MenuBook,"support_walkthrough",false,120) {}
                SupportAction(R.string.support_staff_help,Icons.Outlined.SupportAgent,"support_staff_help",false,90) {}
                SupportAction(R.string.support_emergency,Icons.Outlined.WarningAmber,"support_emergency",false,60) {}
                SupportAction(R.string.support_hotlines,Icons.Outlined.PhoneInTalk,"support_hotlines",true,30) {
                    if(expanded) { onExpanded(false);session.navigate(SafePage.Hotlines) }
                }
                SupportAction(R.string.safe_contacts,Icons.Outlined.Contacts,"support_contacts",true,0) {
                    if(expanded) { onExpanded(false);session.navigate(SafePage.Contacts) }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp),
            verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.SpaceBetween) {
            if(showSupport) {
                val label=stringResource(if(expanded) R.string.support_close_menu else R.string.support_open_menu)
                val state=stringResource(if(expanded) R.string.support_expanded else R.string.support_collapsed)
                FloatingActionButton(onClick={onExpanded(!expanded)},containerColor=OliveGreen,
                    contentColor=MaterialTheme.colorScheme.onPrimary,modifier=Modifier.testTag("support_menu_toggle").semantics {
                        contentDescription=label;stateDescription=state
                    }) { Icon(Icons.Outlined.Add,null,Modifier.rotate(iconRotation)) }
            } else Spacer(Modifier.width(0.dp))
            if(showExit || expanded) {
                val label=stringResource(R.string.a11y_quick_exit)
                ExtendedFloatingActionButton(onClick={onExpanded(false);exit()},containerColor=OliveGreen,
                    contentColor=MaterialTheme.colorScheme.onPrimary,modifier=Modifier.testTag("quick_exit").semantics { contentDescription=label },
                    icon={Icon(Icons.Outlined.Close,null)},text={Text(stringResource(R.string.quick_exit),modifier=Modifier.clearAndSetSemantics {})})
            }
        }
    }
}

@Composable private fun AnimatedVisibilityScope.SupportAction(labelId:Int,icon:ImageVector,tag:String,enabled:Boolean,
    delayMillis:Int,onClick:()->Unit) {
    val label=stringResource(labelId)
    val title=if(enabled) label else stringResource(R.string.support_coming_soon,label)
    Button(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).testTag(tag)
        .animateEnterExit(enter=fadeIn(tween(160,delayMillis))+slideInVertically(tween(220,delayMillis,FastOutSlowInEasing)) { it/3 },
            exit=fadeOut(tween(90))),
        shape=MaterialTheme.shapes.large,contentPadding=PaddingValues(16.dp),
        colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.surface,
            contentColor=MaterialTheme.colorScheme.onSurface,disabledContainerColor=LightSage,disabledContentColor=Ink)) {
        Icon(icon,null,Modifier.size(24.dp));Spacer(Modifier.width(12.dp))
        Text(title,Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
    }
}
