package com.olive.app.ui.covers.calculator
import com.olive.app.ui.*

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The bounded history and display stay separate from keypad layout and key dispatch.
@Composable internal fun ColumnScope.CalculatorDisplay(input:CalculatorInput,compact:Boolean,scientificExpanded:Boolean) {
    // UI sizing: history scrolls in its own bounded area and the clipped display can never overlap the keys.
                Column(Modifier.fillMaxWidth().weight(if(scientificExpanded) .65f else 1.25f)) {
                    Row(Modifier.fillMaxWidth().height(if(compact) 28.dp else 32.dp),verticalAlignment=Alignment.CenterVertically) {
                        LocalizedText("History",modifier=Modifier.weight(1f),color=Muted,style=MaterialTheme.typography.labelSmall)
                        if(input.history.isNotEmpty()) {
                            TextButton(onClick=input::clearHistory,modifier=Modifier.height(if(compact) 28.dp else 32.dp)
                                .testTag("clear_calculator_history"),contentPadding=PaddingValues(horizontal=6.dp,vertical=0.dp)) {
                                LocalizedText("Clear",fontSize=if(compact) 11.sp else 12.sp,color=OliveGreen)
                            }
                        }
                    }
                    if(input.history.isEmpty()) Spacer(Modifier.weight(1f)) else LazyColumn(
                        modifier=Modifier.fillMaxWidth().weight(1f).testTag("calculator_history_list"),
                        verticalArrangement=Arrangement.spacedBy(1.dp),
                    ) {
                        itemsIndexed(input.history,key={ index,entry -> "$index:${entry.expression}:${entry.result}" }) { index,entry ->
                            TextButton(onClick={input.reuse(entry)},modifier=Modifier.fillMaxWidth().heightIn(min=24.dp)
                                .testTag("calculator_history_$index"),contentPadding=PaddingValues(horizontal=2.dp,vertical=0.dp)) {
                                Text(entry.expression,modifier=Modifier.weight(1f),color=Muted,fontFamily=FontFamily.Monospace,
                                    fontSize=if(compact) 11.sp else 13.sp,maxLines=1,textAlign=TextAlign.Start)
                                Text("= ${entry.result}",color=OliveGreen,fontFamily=FontFamily.Monospace,
                                    fontSize=if(compact) 11.sp else 13.sp,maxLines=1)
                            }
                        }
                    }
                    val displayScroll=rememberScrollState()
                    LaunchedEffect(input.display,displayScroll.maxValue) { displayScroll.scrollTo(displayScroll.maxValue) }
                    Column(
                        modifier=Modifier.fillMaxWidth().height(if(compact) 54.dp else 68.dp).clipToBounds(),
                        horizontalAlignment=Alignment.End,
                        verticalArrangement=Arrangement.Bottom,
                    ) {
                        LocalizedText(if(input.expression.endsWith("=")) input.expression else "",modifier=Modifier.fillMaxWidth(),color=Muted,
                            fontSize=if(compact) 12.sp else 14.sp,textAlign=TextAlign.End,maxLines=1)
                        Row(Modifier.fillMaxWidth().clipToBounds().horizontalScroll(displayScroll),horizontalArrangement=Arrangement.End) {
                            Text(input.display,modifier=Modifier.testTag("calculator_display"),color=Ink,
                                fontSize=if(compact) { if(input.display.length>12) 25.sp else 38.sp } else { if(input.display.length>12) 31.sp else 48.sp },
                                fontFamily=FontFamily.Monospace,textAlign=TextAlign.End,maxLines=1,softWrap=false)
                        }
                    }
                }
}
