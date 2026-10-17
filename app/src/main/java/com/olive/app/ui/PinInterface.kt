package com.olive.app.ui
import com.olive.app.R
import androidx.compose.ui.res.stringResource

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.security.InputPolicy

// USER ONLY: every user device gets a local PIN; Safe Contacts, Staff, and Admins do not use this step.
// UI sizing/text: PIN setup uses the shared form spacing and full-width fields; warning copy uses the Muted color.
@Composable internal fun DevicePin(session: SessionViewModel) {
    val uiText=rememberResourceText()
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val pinValid=InputPolicy.validPin(pin)
    val confirmationValid=confirm.isNotEmpty() && confirm==pin
    val stages=if(session.creatingAccount) 4 else 2
    val page=stages-1
    val completedBeforePin=if(session.creatingAccount) 7 else 2
    val totalRequired=completedBeforePin+2
    FormColumn {
        Heading(uiText(R.string.secure_this_device), uiText(R.string.ui_create_your_device_pin))
        OnboardingProgress(completedBeforePin+listOf(pinValid,confirmationValid).count { it },totalRequired,page,stages,
            List(stages) { index -> index<page || (index==page && pinValid && confirmationValid) })
        Field(uiText(R.string.str_4_6_digit_pin),pin,"new_pin",secret=true,numeric=true,showSecretToggle=true,enabled=!session.busy,
            error=if(showErrors && !pinValid) uiText(R.string.ui_invalid_pin) else null) {
            pin=it.filter(Char::isDigit).take(6);showErrors=false
        }
        RequirementBullet(uiText(R.string.ui_4_6_digits),pinValid,pin.isNotEmpty())
        Field(uiText(R.string.confirm_pin),confirm,"confirm_pin",secret=true,numeric=true,showSecretToggle=true,enabled=!session.busy,
            error=if(showErrors && !confirmationValid) uiText(R.string.ui_pins_differ) else null) {
            confirm=it.filter(Char::isDigit).take(6);showErrors=false
        }
        RequirementBullet(uiText(R.string.ui_pins_match),confirmationValid,pin.isNotEmpty() || confirm.isNotEmpty())
        Status(session)
        OnboardingNavigation(session::back,{showErrors=true;if(pinValid && confirmationValid) session.createDevicePin(pin,confirm)},
            last=true,nextTag="pin_setup_submit",enabled=!session.busy)
    }
}

// USER ONLY: a search trigger opens this local PIN challenge; calculator entry verifies through SessionAccess separately.
// UI sizing/color: this screen owns the compact breakpoint, keypad dimensions, PIN-dot size, OliveGreen accents, and responsive text sizes.
@Composable internal fun PinPrompt(session: SessionViewModel) {
    val uiText=rememberResourceText()
    val challenge=session.pinChallengeVersion
    var pin by remember(challenge) { mutableStateOf("") }
    val headingScrollState=rememberScrollState()
    val pinLabel=uiText(R.string.a11y_pin)
    val enteredLabel=uiText(R.string.a11y_digits_entered)
    val deleteLabel=uiText(R.string.a11y_delete_digit)
    val unlockLabel=uiText(R.string.a11y_unlock)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Sizing note: raise 650dp to switch into the compact PIN layout sooner. The 48–72dp range below is the keypad's min/max size.
        // When adding a language with longer PIN text, test this breakpoint with Android's largest font setting too.
        val compact=maxHeight<650.dp
        val sidePadding=if(maxWidth<360.dp) 12.dp else 24.dp
        val horizontalLimit=(maxWidth-sidePadding*2-32.dp)/3
        val verticalLimit=(maxHeight-if(compact) 190.dp else 235.dp)/4
        val keySize=minOf(72.dp,horizontalLimit,verticalLimit).coerceAtLeast(48.dp)
        val gap=if(compact) 7.dp else 12.dp
        Column(Modifier.fillMaxSize().padding(horizontal=sidePadding,vertical=if(compact) 10.dp else 20.dp),
            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(if(compact) 7.dp else 12.dp)) {
            Column(Modifier.weight(1f).oliveScrollbar(headingScrollState).verticalScroll(headingScrollState),horizontalAlignment=Alignment.CenterHorizontally,
                verticalArrangement=Arrangement.spacedBy(if(compact) 7.dp else 12.dp)) {
                // These are the compact/regular PIN headings; change both together so translated text scales consistently.
                Text(stringResource(R.string.olive_branch),fontFamily=FontFamily.Serif,fontSize=if(compact) 22.sp else 26.sp,color=OliveGreen)
                Text(stringResource(R.string.enter_pin),modifier=Modifier.padding(top=if(compact) 12.dp else 18.dp).semantics { heading() },
                    style=if(compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.enter_your_pin),color=Muted)
                Row(Modifier.height(if(compact) 11.dp else 14.dp).testTag("unlock_pin").clearAndSetSemantics {
                    contentDescription=pinLabel
                    stateDescription="$enteredLabel: ${pin.length}"
                    liveRegion=LiveRegionMode.Polite
                },horizontalArrangement=Arrangement.spacedBy(if(compact) 8.dp else 12.dp)) {
                    repeat(pin.length) {
                        Box(Modifier.size(if(compact) 11.dp else 14.dp).background(OliveGreen,CircleShape))
                    }
                }
                Status(session)
            }
            // The keypad scales from the remaining width and height so compact phones never crop its bottom rows.
            Column(verticalArrangement=Arrangement.spacedBy(gap)) {
                listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf("⌫","0","↪")).forEach { row ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                        row.forEach { key ->
                            val tag=when(key) { "⌫" -> "pin_backspace"; "↪" -> "unlock_submit"; else -> "pin_digit_$key" }
                            FilledTonalButton(onClick={
                                // TalkBack can deliver an action from a node whose enabled state is stale.
                                if(session.busy || session.privacyBlackout || session.screen!=SessionScreen.Pin ||
                                    session.pinChallengeVersion!=challenge) return@FilledTonalButton
                                when(key) {
                                    "⌫" -> pin=pin.dropLast(1)
                                    "↪" -> if(InputPolicy.validPin(pin)) { val attempt=pin;pin="";session.submitPin(attempt,challenge) }
                                    else -> if(pin.length<6) pin+=key
                                }
                            },enabled=!session.busy && (key!="↪" || pin.length in 4..6),
                                modifier=Modifier.size(keySize).testTag(tag),shape=CircleShape,
                                contentPadding=PaddingValues(0.dp)) {
                                Text(key,modifier=if(key=="⌫" || key=="↪") Modifier.clearAndSetSemantics {
                                    contentDescription=if(key=="⌫") deleteLabel else unlockLabel
                                } else Modifier,fontSize=if(compact) 23.sp else if(key=="⌫" || key=="↪") 26.sp else 30.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
