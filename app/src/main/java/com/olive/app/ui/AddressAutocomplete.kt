package com.olive.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

// The provider stays behind this field, so onboarding and cover forms share one accessible address workflow.
@Composable fun AddressAutocomplete(current:DeliveryAddress,label:String,onFocus:(()->Unit)?=null,cityOnly:Boolean=false,
    fieldError:String?=null,onChange:(DeliveryAddress)->Unit) {
    val uiText=rememberResourceText()
    var suggestions by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedText by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    val query=if(cityOnly) current.city else current.line1
    LaunchedEffect(query,focused) {
        suggestions=emptyList()
        if(selectedText==query) { selectedText=null;return@LaunchedEffect }
        if(!focused || query.trim().length<3 || !GeoapifyClient.configured) return@LaunchedEffect
        delay(200.milliseconds)
        runCatching { GeoapifyClient.autocomplete(query,cityOnly) }
            .onSuccess { suggestions=it }
            .onFailure { if(it is CancellationException) throw it else error=uiText(R.string.address_suggestions_are_unavailable_continue_entering_your_address) }
    }

    OutlinedTextField(value=query,onValueChange={ value ->
        val cleared=if(cityOnly) current.copy(city=value.take(160),latitude=null,longitude=null)
            else current.copy(line1=value.take(160),latitude=null,longitude=null)
        onChange(cleared);error=null
    },singleLine=true,label={Text(label)},modifier=Modifier.fillMaxWidth().testTag(if(cityOnly) "address_city" else "address_line1")
        .onFocusChanged { focused=it.isFocused;if(it.isFocused) onFocus?.invoke() }.keepVisibleWhileTyping(),
        isError=error!=null || fieldError!=null,supportingText=(fieldError ?: error)?.let { value -> { AccessibleMessage(value) } })
    suggestions.forEach { suggestion ->
        Surface(Modifier.fillMaxWidth().clickable {
            val resolved=suggestion.address
            val selected=if(cityOnly) current.copy(city=resolved.city,province=resolved.province,country=resolved.country,
                latitude=resolved.latitude,longitude=resolved.longitude) else resolved.copy(line2=current.line2)
            selectedText=if(cityOnly) selected.city else selected.line1;suggestions=emptyList();onChange(selected);onFocus?.invoke();error=null
        },shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(12.dp)) {
                Text(suggestion.primary,style=MaterialTheme.typography.titleSmall)
                if(suggestion.secondary.isNotBlank()) Text(suggestion.secondary,style=MaterialTheme.typography.bodySmall)
            }
        }
    }
    if(suggestions.isNotEmpty()) Text(stringResource(R.string.powered_by_geoapify),style=MaterialTheme.typography.labelSmall,color=Muted)
}
