package com.olive.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.olive.app.R
import com.olive.app.data.SafetyConsultation
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

@Composable internal fun SafetyConsultationsScreen(session:SessionViewModel,onBack:()->Unit) {
    var selected by remember { mutableStateOf<SafetyConsultation?>(null) }
    val controller=session.safetyPlans
    DisposableEffect(Unit) { onDispose { controller.close() } }
    BackHandler { if(selected!=null) selected=null else onBack() }
    val plan=selected
    if(plan!=null) {
        SafetyPlanScreen(session,plan.ownerUid,plan.ownerName) { selected=null }
        return
    }
    LaunchedEffect(Unit) { while(true) { controller.refreshConsultations();delay(15.seconds) } }
    FormColumn {
        TextButton(onClick=onBack) { Text(stringResource(R.string.safety_back)) }
        Heading(stringResource(R.string.safety_inbox),stringResource(R.string.safety_staff_intro),stringResource(R.string.olive_tree))
        val availabilityLabel=stringResource(R.string.safety_available)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(availabilityLabel,Modifier.weight(1f))
            Switch(checked=controller.available,onCheckedChange=controller::changeAvailability,modifier=Modifier.testTag("safety_staff_available").semantics { contentDescription=availabilityLabel })
        }
        controller.error?.let { AccessibleMessage(stringResource(it)) }
        if(controller.consultations.isEmpty()) Text(stringResource(R.string.safety_inbox_empty))
        controller.consultations.forEach { consultation ->
            OutlinedButton(onClick={selected=consultation},modifier=Modifier.fillMaxWidth().testTag("safety_consultation_${consultation.ownerUid}")) {
                Text(stringResource(R.string.safety_open_plan,consultation.ownerName))
            }
        }
    }
}
