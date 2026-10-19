package com.olive.app.ui
import com.olive.app.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.olive.app.BuildConfig
import com.olive.app.data.DeliveryAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlin.time.Duration.Companion.milliseconds

// Street typing and Google suggestions share one field so the address form never needs a second search box.
// UI sizing/color: the address field fills its form; suggestion rows use surfaceVariant, 12dp padding, titleSmall, and bodySmall text.
@Composable fun AddressAutocomplete(current: DeliveryAddress, label: String, onFocus: (() -> Unit)?=null, cityOnly:Boolean=false,fieldError:String?=null,onChange: (DeliveryAddress)->Unit) {
    val uiText=rememberResourceText()
    val context=LocalContext.current
    val key=BuildConfig.PLACES_API_KEY
    var predictions by remember { mutableStateOf<List<AutocompletePrediction>>(emptyList()) }
    var token by remember { mutableStateOf(AutocompleteSessionToken.newInstance()) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedLine by remember { mutableStateOf<String?>(null) }
    val client=remember(key) {
        if(key.isBlank()) null else {
            if(!Places.isInitialized()) Places.initializeWithNewPlacesApiEnabled(context.applicationContext,key)
            Places.createClient(context)
        }
    }

    var focused by remember { mutableStateOf(false) }
    val query=if(cityOnly) current.city else current.line1
    LaunchedEffect(query,token,client,focused) {
        predictions=emptyList()
        if(selectedLine==query) { selectedLine=null;return@LaunchedEffect }
        if(!focused || client==null || query.trim().length<3) return@LaunchedEffect
        delay(300.milliseconds)
        runCatching {
            client.findAutocompletePredictions(FindAutocompletePredictionsRequest.builder()
                .setQuery(query.trim()).setSessionToken(token).setRegionCode("ca")
                .apply { if(cityOnly) setTypesFilter(listOf("locality")) }.build()).await().autocompletePredictions
        }.onSuccess { predictions=it }.onFailure {
            if(it is CancellationException) throw it
            error=uiText(R.string.address_suggestions_are_unavailable_continue_entering_your_address)
        }
    }

    OutlinedTextField(value=query,onValueChange={onChange(if(cityOnly) current.copy(city=it.take(160)) else current.copy(line1=it.take(160)));error=null},singleLine=true,
        label={Text(label)},modifier=Modifier.fillMaxWidth().testTag(if(cityOnly) "address_city" else "address_line1").onFocusChanged { focused=it.isFocused;if(it.isFocused) onFocus?.invoke() }.keepVisibleWhileTyping(),
        isError=error!=null || fieldError!=null,supportingText=(fieldError ?: error)?.let { value -> { AccessibleMessage(value) } })
    if(client!=null) {
        predictions.forEach { prediction ->
            Surface(Modifier.fillMaxWidth().clickable {
                predictions=emptyList();onFocus?.invoke()
                val request=FetchPlaceRequest.builder(prediction.placeId,listOf(Place.Field.ADDRESS_COMPONENTS,Place.Field.FORMATTED_ADDRESS))
                    .setSessionToken(token).build()
                client.fetchPlace(request).addOnSuccessListener { response ->
                    val resolved=response.place.toDeliveryAddress(current)
                    val selected=if(cityOnly) current.copy(city=resolved.city,province=resolved.province,country=resolved.country) else resolved
                    selectedLine=if(cityOnly) selected.city else selected.line1;onChange(selected)
                    token=AutocompleteSessionToken.newInstance();error=null
                }.addOnFailureListener { error=uiText(R.string.that_address_could_not_be_filled_automatically_continue_entering_your_address) }
            },shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(12.dp)) {
                    Text(prediction.getPrimaryText(null).toString(),style=MaterialTheme.typography.titleSmall)
                    Text(prediction.getSecondaryText(null).toString(),style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        if(predictions.isNotEmpty()) Text(stringResource(R.string.powered_by_google),style=MaterialTheme.typography.labelSmall,color=Muted)
    }
}

private fun Place.toDeliveryAddress(previous: DeliveryAddress): DeliveryAddress {
    val parts=addressComponents?.asList().orEmpty()
    fun long(type: String)=parts.firstOrNull { type in it.types }?.name.orEmpty()
    fun short(type: String)=parts.firstOrNull { type in it.types }?.shortName.orEmpty()
    val street=listOf(long("street_number"),long("route")).filter(String::isNotBlank).joinToString(" ")
    val fallback=formattedAddress?.substringBefore(',').orEmpty()
    return DeliveryAddress(
        line1=street.ifBlank { fallback }.take(160),
        line2=long("subpremise").ifBlank { previous.line2 }.take(160),
        city=listOf("locality","postal_town","administrative_area_level_2").firstNotNullOfOrNull { type -> long(type).takeIf(String::isNotBlank) }.orEmpty().take(160),
        province=short("administrative_area_level_1").ifBlank { long("administrative_area_level_1") }.take(160),
        postalCode=long("postal_code").take(160),
        country=long("country").ifBlank { previous.country }.take(160),
    )
}
