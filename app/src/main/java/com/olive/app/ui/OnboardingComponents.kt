package com.olive.app.ui

import androidx.compose.ui.semantics.*
import com.olive.app.ui.LocalizedIcon as Icon
import androidx.compose.ui.res.stringResource
import com.olive.app.R

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.Month
import java.util.Locale

// Requirement bullets begin neutral, then turn green or red as the user types.
@Composable internal fun RequirementBullet(text:String,met:Boolean,active:Boolean) {
    val color=when { !active -> Color.Black;met -> OliveGreen;else -> MaterialTheme.colorScheme.error }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
        Text("•",color=color,fontSize=18.sp)
        Text(text,color=color,style=MaterialTheme.typography.bodySmall)
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(visible=active && met,enter=fadeIn(tween(150)),exit=fadeOut(tween(100))) {
            Icon(Icons.Outlined.CheckCircle,contentDescription="Requirement met",tint=OliveGreen,modifier=Modifier.size(18.dp))
        }
    }
}

// Every onboarding page ends with the same Back and Next arrow controls.
@Composable internal fun OnboardingNavigation(back:()->Unit,next:()->Unit,last:Boolean=false,nextTag:String="onboarding_next",
    enabled:Boolean=true,nextLabel:String?=null) {
    Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick=back,enabled=enabled,modifier=Modifier.weight(1f).testTag("onboarding_back")) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.back))
        }
        Button(onClick=next,enabled=enabled,modifier=Modifier.weight(1f).testTag(nextTag)) {
            Text(nextLabel ?: if(last) "Finish" else "Next");Spacer(Modifier.width(8.dp));Icon(Icons.AutoMirrored.Outlined.ArrowForward,null)
        }
    }
}

// USER and SAFE CONTACT: birthday is required and saved as YYYY-MM-DD.
// UI sizing: the three birthday buttons share one row equally with 8dp gaps and 8dp horizontal button padding.
@Composable internal fun BirthdayPicker(value: String, tag: String, enabled: Boolean,error:String?=null,onChange: (String)->Unit) {
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    val initial=remember { runCatching { LocalDate.parse(value) }.getOrNull() }
    var year by remember { mutableStateOf(initial?.year) }
    var month by remember { mutableStateOf(initial?.monthValue) }
    var day by remember { mutableStateOf(initial?.dayOfMonth) }
    val locale= remember { Locale.getDefault() }
    fun setDate(y: Int?,m: Int?,d: Int?) {
        if(y!=null && m!=null && d!=null) onChange("%04d-%02d-%02d".format(Locale.ROOT,y,m,d))
    }
    Text(stringResource(R.string.date_of_birth_required),style=MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth().testTag(tag),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        @Composable fun Choice(label: String, selected: String, options: List<Pair<String,String>>, choiceTag: String, choose: (String)->Unit) {
            var open by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick={keyboard?.hide();focus.clearFocus(force=true);open=true},enabled=enabled,
                    border=if(error!=null) BorderStroke(1.dp,MaterialTheme.colorScheme.error) else ButtonDefaults.outlinedButtonBorder(enabled),
                    modifier=Modifier.fillMaxWidth().testTag(choiceTag),contentPadding=PaddingValues(horizontal=8.dp)) {
                    Text(selected.ifBlank { label },maxLines=1)
                }
                DropdownMenu(expanded=open,onDismissRequest={open=false}) {
                    options.forEach { (id,text) ->
                        DropdownMenuItem(text={Text(text)},onClick={choose(id);open=false},modifier=Modifier.testTag("${choiceTag}_$id"))
                    }
                }
            }
        }
        Choice("Year",year?.toString().orEmpty(),(1950..LocalDate.now().year).reversed().map { it.toString() to it.toString() },"${tag}_year") {
            year=it.toInt();setDate(year,month,day)
        }
        Choice("Month",month?.let { Month.of(it).getDisplayName(java.time.format.TextStyle.FULL,locale) }.orEmpty(),
            (1..12).map { it.toString() to Month.of(it).getDisplayName(java.time.format.TextStyle.FULL,locale) },"${tag}_month") {
            month=it.toInt();setDate(year,month,day)
        }
        Choice("Day",day?.toString().orEmpty(),(1..31).map { it.toString() to it.toString() },"${tag}_day") {
            day=it.toInt();setDate(year,month,day)
        }
    }
    error?.let { AccessibleMessage(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
}
