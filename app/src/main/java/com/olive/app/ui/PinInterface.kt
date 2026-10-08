package com.olive.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.olive.app.data.*
import com.olive.app.security.InputPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

// USER ONLY: every user device gets a local PIN; Safe Contacts, Staff, and Admins do not use this step.
// UI sizing/text: PIN setup uses the shared form spacing and full-width fields; warning copy uses the Muted color.
@Composable internal fun DevicePin(session: SessionViewModel) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val pinValid=InputPolicy.validPin(pin)
    val confirmationValid=confirm.isNotEmpty() && confirm==pin
    val stages=if(session.creatingAccount) 4 else 2
    val page=stages-1
    val completedBeforePin=if(session.creatingAccount) 8 else 2
    val totalRequired=completedBeforePin+2
    FormColumn {
        Heading("Secure this device", "Create your device PIN.")
        OnboardingProgress(completedBeforePin+listOf(pinValid,confirmationValid).count { it },totalRequired,page,stages,
            List(stages) { index -> index<page || (index==page && pinValid && confirmationValid) })
        Field("4–6 digit PIN",pin,"new_pin",secret=true,numeric=true,showSecretToggle=true,enabled=!session.busy,
            error=if(showErrors && !pinValid) "Invalid PIN" else null) {
            pin=it.filter(Char::isDigit).take(6);showErrors=false
        }
        RequirementBullet("4–6 digits",pinValid,pin.isNotEmpty())
        Field("Confirm PIN",confirm,"confirm_pin",secret=true,numeric=true,showSecretToggle=true,enabled=!session.busy,
            error=if(showErrors && !confirmationValid) "PINs differ" else null) {
            confirm=it.filter(Char::isDigit).take(6);showErrors=false
        }
        RequirementBullet("PINs match",confirmationValid,pin.isNotEmpty() || confirm.isNotEmpty())
        Status(session)
        OnboardingNavigation(session::back,{showErrors=true;if(pinValid && confirmationValid) session.createDevicePin(pin,confirm)},
            last=true,nextTag="pin_setup_submit",enabled=!session.busy)
    }
}

// USER ONLY: a cover trigger opens this local PIN interface before showing Olive Branch.
// UI sizing/color: this screen owns the compact breakpoint, keypad dimensions, PIN-dot size, OliveGreen accents, and responsive text sizes.
@Composable internal fun PinPrompt(session: SessionViewModel) {
    var pin by remember { mutableStateOf("") }
    val headingScrollState=rememberScrollState()
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
                LocalizedText("Olive Branch",fontFamily=FontFamily.Serif,fontSize=if(compact) 22.sp else 26.sp,color=OliveGreen)
                LocalizedText("Enter PIN",modifier=Modifier.padding(top=if(compact) 12.dp else 18.dp),
                    style=if(compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium)
                LocalizedText("Enter your PIN.",color=Muted)
                Row(Modifier.height(if(compact) 11.dp else 14.dp).testTag("unlock_pin"),horizontalArrangement=Arrangement.spacedBy(if(compact) 8.dp else 12.dp)) {
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
                                when(key) {
                                    "⌫" -> pin=pin.dropLast(1)
                                    "↪" -> { val attempt=pin;pin="";session.submitPin(attempt) }
                                    else -> if(pin.length<6) pin+=key
                                }
                            },enabled=!session.busy && (key!="↪" || pin.length in 4..6),
                                modifier=Modifier.size(keySize).testTag(tag),shape=CircleShape,
                                contentPadding=PaddingValues(0.dp)) {
                                LocalizedText(key,fontSize=if(compact) 23.sp else if(key=="⌫" || key=="↪") 26.sp else 30.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
