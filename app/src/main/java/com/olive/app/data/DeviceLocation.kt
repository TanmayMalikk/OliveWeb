package com.olive.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

internal data class DeviceCoordinates(val latitude:Double,val longitude:Double,val accuracy:Float?,val timestamp:Long)

// A cancellable, one-off lookup for nearby resources. Coordinates and the result never update the account.
internal object DeviceLocation {
    suspend fun coordinates(context:Context):Pair<Double,Double>? {
        return currentCoordinates(context)?.let { it.latitude to it.longitude }
    }

    suspend fun currentCoordinates(context:Context):DeviceCoordinates? {
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) return null
        val manager=context.getSystemService(LocationManager::class.java)
        if(!LocationManagerCompat.isLocationEnabled(manager)) return null
        return withTimeoutOrNull(15.seconds) {
            val location=fusedCurrent(context) ?: current(context,manager) ?: recent(manager) ?: return@withTimeoutOrNull null
            DeviceCoordinates(location.latitude,location.longitude,location.accuracy.takeIf { location.hasAccuracy() },location.time)
        }
    }

    suspend fun coordinatesForAddress(context:Context,address:DeliveryAddress):Pair<Double,Double>? {
        if(!Geocoder.isPresent()) return null
        val query=listOf(address.line1,address.city,address.province,address.postalCode,address.country).filter(String::isNotBlank).joinToString(", ")
        if(query.isBlank()) return null
        val address=forwardGeocode(Geocoder(context,Locale.getDefault()),query) ?: return null
        return address.latitude to address.longitude
    }

    suspend fun address(context:Context):DeliveryAddress? {
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) return null
        val manager=context.getSystemService(LocationManager::class.java)
        if(!LocationManagerCompat.isLocationEnabled(manager) || !Geocoder.isPresent()) return null
        return withTimeoutOrNull(15.seconds) {
            val location=fusedCurrent(context) ?: current(context,manager) ?: recent(manager) ?: return@withTimeoutOrNull null
            val address=geocode(context,location) ?: return@withTimeoutOrNull null
            DeliveryAddress(city=address.locality ?: address.subAdminArea.orEmpty(),province=address.adminArea.orEmpty(),
                postalCode=address.postalCode.orEmpty(),country=address.countryCode ?: address.countryName.orEmpty())
                .takeIf { it.country.isNotBlank() && (it.city.isNotBlank() || it.province.isNotBlank() || it.postalCode.isNotBlank()) }
        }
    }
    @RequiresPermission(anyOf=[Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION])
    private fun recent(manager:LocationManager):Location? {
        val now=System.currentTimeMillis()
        return manager.getProviders(true)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { now-it.time<=MAX_CACHED_LOCATION_AGE_MS }
            .minByOrNull { location ->
                val ageSeconds=((now-location.time).coerceAtLeast(0L)/1_000L).toFloat()
                ageSeconds*2f+(if(location.hasAccuracy()) location.accuracy else 1_000f)
            }
    }

    @RequiresPermission(anyOf=[Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION])
    private suspend fun fusedCurrent(context:Context):Location? {
        val cancellation=CancellationTokenSource()
        return try {
            val request=CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(0L).setDurationMillis(14_000L).build()
            LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(request,cancellation.token).await()
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(_:Exception) { null } finally { cancellation.cancel() }
    }

    @RequiresPermission(anyOf=[Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION])
    private suspend fun current(context:Context,manager:LocationManager):Location? = suspendCancellableCoroutine { continuation ->
        val provider=listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER)
            .firstOrNull { manager.isProviderEnabled(it) }
        if(provider==null) { continuation.resume(null);return@suspendCancellableCoroutine }
        val cancellation=CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        runCatching {
            LocationManagerCompat.getCurrentLocation(manager,provider,cancellation,ContextCompat.getMainExecutor(context)) {
                if(continuation.isActive) continuation.resume(it)
            }
        }.onFailure { if(continuation.isActive) continuation.resume(null) }
    }
    private suspend fun geocode(context:Context,location:Location):Address? {
        val geocoder=Geocoder(context,Locale.getDefault())
        return if(Build.VERSION.SDK_INT>=33) suspendCancellableCoroutine { continuation ->
            runCatching {
                geocoder.getFromLocation(location.latitude,location.longitude,1,object:Geocoder.GeocodeListener {
                    override fun onGeocode(addresses:MutableList<Address>) {
                        if(continuation.isActive) continuation.resume(addresses.firstOrNull())
                    }
                    override fun onError(errorMessage:String?) { if(continuation.isActive) continuation.resume(null) }
                })
            }.onFailure { if(continuation.isActive) continuation.resume(null) }
        } else legacyReverseGeocode(geocoder,location)
    }

    private suspend fun forwardGeocode(geocoder:Geocoder,query:String):Address? =
        if(Build.VERSION.SDK_INT>=33) suspendCancellableCoroutine { continuation ->
            runCatching {
                geocoder.getFromLocationName(query,1,object:Geocoder.GeocodeListener {
                    override fun onGeocode(addresses:MutableList<Address>) {
                        if(continuation.isActive) continuation.resume(addresses.firstOrNull())
                    }
                    override fun onError(errorMessage:String?) { if(continuation.isActive) continuation.resume(null) }
                })
            }.onFailure { if(continuation.isActive) continuation.resume(null) }
        } else legacyForwardGeocode(geocoder,query)

    @Suppress("DEPRECATION")
    private suspend fun legacyForwardGeocode(geocoder:Geocoder,query:String):Address? = withContext(Dispatchers.IO) {
        // Android 9–12 expose only this blocking overload, so it must run away from the UI thread.
        runCatching { geocoder.getFromLocationName(query,1)?.firstOrNull() }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private suspend fun legacyReverseGeocode(geocoder:Geocoder,location:Location):Address? = withContext(Dispatchers.IO) {
        // Android 9–12 expose only this blocking overload, so it must run away from the UI thread.
        runCatching { geocoder.getFromLocation(location.latitude,location.longitude,1)?.firstOrNull() }.getOrNull()
    }

    private const val MAX_CACHED_LOCATION_AGE_MS=5*60*1_000L
}
