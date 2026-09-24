package com.olive.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

// Google suggestions fill the address fields here; manual entry below always stays available.
@Composable fun AddressAutocomplete(current: DeliveryAddress, onSelected: (DeliveryAddress)->Unit) {
    val context=LocalContext.current
    val key=BuildConfig.PLACES_API_KEY
    var query by remember { mutableStateOf("") }
    var predictions by remember { mutableStateOf<List<AutocompletePrediction>>(emptyList()) }
    var token by remember { mutableStateOf(AutocompleteSessionToken.newInstance()) }
    var error by remember { mutableStateOf<String?>(null) }
    val client=remember(key) {
        if(key.isBlank()) null else {
            if(!Places.isInitialized()) Places.initializeWithNewPlacesApiEnabled(context.applicationContext,key)
            Places.createClient(context)
        }
    }

    LaunchedEffect(query,token,client) {
        predictions=emptyList()
        if(client==null || query.trim().length<3) return@LaunchedEffect
        delay(300)
        runCatching {
            client.findAutocompletePredictions(FindAutocompletePredictionsRequest.builder()
                .setQuery(query.trim()).setSessionToken(token).setRegionCode("ca").build()).await().autocompletePredictions
        }.onSuccess { predictions=it }.onFailure { error="Address suggestions are unavailable. Continue entering your address." }
    }

    if(client==null) {
        LocalizedText("Address suggestions are unavailable. Continue entering your address.",style=MaterialTheme.typography.bodySmall,color=Muted)
    } else {
        OutlinedTextField(value=query,onValueChange={query=it.take(200);error=null},singleLine=true,
            label={LocalizedText("Search address with Google")},modifier=Modifier.fillMaxWidth().testTag("address_search"))
        predictions.forEach { prediction ->
            Surface(Modifier.fillMaxWidth().clickable {
                predictions=emptyList();query=prediction.getFullText(null).toString()
                val request=FetchPlaceRequest.builder(prediction.placeId,listOf(Place.Field.ADDRESS_COMPONENTS,Place.Field.FORMATTED_ADDRESS))
                    .setSessionToken(token).build()
                client.fetchPlace(request).addOnSuccessListener { response ->
                    onSelected(response.place.toDeliveryAddress(current))
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
        error?.let { LocalizedText(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
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
