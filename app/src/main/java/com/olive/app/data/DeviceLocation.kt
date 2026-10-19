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
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

// A cancellable, one-off lookup for nearby resources. Coordinates and the result never update the account.
internal object DeviceLocation {
    suspend fun address(context:Context):DeliveryAddress? {
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) return null
        val manager=context.getSystemService(LocationManager::class.java)
        if(!LocationManagerCompat.isLocationEnabled(manager) || !Geocoder.isPresent()) return null
        return withTimeoutOrNull(15.seconds) {
            val location=recent(manager) ?: current(context,manager) ?: return@withTimeoutOrNull null
            val address=geocode(context,location) ?: return@withTimeoutOrNull null
            DeliveryAddress(city=address.locality ?: address.subAdminArea.orEmpty(),province=address.adminArea.orEmpty(),
                postalCode=address.postalCode.orEmpty(),country=address.countryCode ?: address.countryName.orEmpty())
                .takeIf { it.country.isNotBlank() && (it.city.isNotBlank() || it.province.isNotBlank() || it.postalCode.isNotBlank()) }
        }
    }
    @RequiresPermission(anyOf=[Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION])
    private fun recent(manager:LocationManager):Location? = manager.getProviders(true)
        .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .filter { SystemClock.elapsedRealtimeNanos()-it.elapsedRealtimeNanos in 0..120_000_000_000L }
        .maxByOrNull(Location::getElapsedRealtimeNanos)

    @RequiresPermission(anyOf=[Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION])
    private suspend fun current(context:Context,manager:LocationManager):Location? = suspendCancellableCoroutine { continuation ->
        val provider=listOf(LocationManager.NETWORK_PROVIDER,LocationManager.GPS_PROVIDER)
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
        } else withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION") // Android 9–12 have only the synchronous Geocoder API; keep it off the UI thread.
            geocoder.getFromLocation(location.latitude,location.longitude,1)?.firstOrNull()
        }
    }
}
