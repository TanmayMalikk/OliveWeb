package com.olive.app.ui

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
import kotlinx.coroutines.tasks.await

// Street typing and Google suggestions share one field so the address form never needs a second search box.
@Composable fun AddressAutocomplete(current: DeliveryAddress, label: String, onFocus: (() -> Unit)?=null, onChange: (DeliveryAddress)->Unit) {
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

    LaunchedEffect(current.line1,token,client) {
        predictions=emptyList()
        if(selectedLine==current.line1) { selectedLine=null;return@LaunchedEffect }
        if(client==null || current.line1.trim().length<3) return@LaunchedEffect
        delay(300)
        runCatching {
            client.findAutocompletePredictions(FindAutocompletePredictionsRequest.builder()
                .setQuery(current.line1.trim()).setSessionToken(token).setRegionCode("ca").build()).await().autocompletePredictions
        }.onSuccess { predictions=it }.onFailure { error="Address suggestions are unavailable. Continue entering your address." }
    }

    OutlinedTextField(value=current.line1,onValueChange={onChange(current.copy(line1=it.take(160)));error=null},singleLine=true,
        label={LocalizedText(label)},modifier=Modifier.fillMaxWidth().testTag("address_line1").onFocusChanged { if(it.isFocused) onFocus?.invoke() },
        isError=error!=null,supportingText=error?.let { value -> { LocalizedText(value) } })
    if(client!=null) {
        predictions.forEach { prediction ->
            Surface(Modifier.fillMaxWidth().clickable {
                predictions=emptyList();onFocus?.invoke()
                val request=FetchPlaceRequest.builder(prediction.placeId,listOf(Place.Field.ADDRESS_COMPONENTS,Place.Field.FORMATTED_ADDRESS))
                    .setSessionToken(token).build()
                client.fetchPlace(request).addOnSuccessListener { response ->
                    val selected=response.place.toDeliveryAddress(current)
                    selectedLine=selected.line1;onChange(selected)
                    token=AutocompleteSessionToken.newInstance();error=null
                }.addOnFailureListener { error="That address could not be filled automatically. Continue entering your address." }
            },shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(12.dp)) {
                    Text(prediction.getPrimaryText(null).toString(),style=MaterialTheme.typography.titleSmall)
                    Text(prediction.getSecondaryText(null).toString(),style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        if(predictions.isNotEmpty()) LocalizedText("Powered by Google",style=MaterialTheme.typography.labelSmall,color=Muted)
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
