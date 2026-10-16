package com.olive.app.ui


import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

// One numbered olive owns its animation, orientation and test identity.
@Composable internal fun OnboardingOlive(index:Int,done:Boolean,active:Boolean,stepOffset:Float) {
    val oliveColor by animateColorAsState(if(done) OliveGreen else PaleOlive,tween(300),label="step_fill_$index")
    val borderColor by animateColorAsState(if(done || active) OliveGreen else SoftGray,tween(300),label="step_border_$index")
    val numberColor by animateColorAsState(if(done) Paper else if(active) OliveGreen else Muted,tween(300),label="step_number_$index")
    val oliveWidth by animateDpAsState(if(active) 34.dp else 30.dp,tween(250),label="step_width_$index")
    val oliveHeight by animateDpAsState(if(active) 44.dp else 40.dp,tween(250),label="step_height_$index")
    val borderWidth by animateDpAsState(if(active) 2.dp else 1.dp,tween(250),label="step_border_width_$index")
    val oliveOffset=if(index%2==0) 24.dp else (-24).dp
    val oliveAngle=if(index%2==0) -18f else 18f
    Box(Modifier.offset(x=stepOffset.dp,y=oliveOffset).width(48.dp).fillMaxHeight(),contentAlignment=Alignment.Center) {
        Surface(shape=RoundedCornerShape(50),color=oliveColor,
            border=BorderStroke(borderWidth,borderColor),
            modifier=Modifier.size(oliveWidth,oliveHeight).rotate(oliveAngle).testTag("onboarding_step_${index+1}")) {
            Box(Modifier.rotate(-oliveAngle),contentAlignment=Alignment.Center) {
                Text("${index+1}",fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color=numberColor)
            }
        }
    }
}
