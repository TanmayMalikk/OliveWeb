package com.olive.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

// Grab a one-off coarse location for cover content; nothing from here is saved to the account.
object CoverLocation {
    @SuppressLint("MissingPermission")
    suspend fun city(context: Context, fallback: String): String {
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED) return fallback
        val manager=context.getSystemService(LocationManager::class.java)
        val cached=manager.getProviders(true).mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull(Location::getTime)
        val location=cached ?: withTimeoutOrNull(6_000) { current(manager) }
        if(location==null) return fallback
        return withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(context,Locale.getDefault()).getFromLocation(location.latitude,location.longitude,1)?.firstOrNull()?.let { address ->
                    listOf(address.locality,address.adminArea,address.countryName).filterNotNull().filter(String::isNotBlank).joinToString(", ")
                }
            }.getOrNull().takeUnless(String?::isNullOrBlank) ?: fallback
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun current(manager: LocationManager): Location? = suspendCancellableCoroutine { continuation ->
        val provider=when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if(provider==null) continuation.resume(null)
        else if(Build.VERSION.SDK_INT>=30) {
            runCatching { manager.getCurrentLocation(provider,null,{ it.run() }) { if(continuation.isActive) continuation.resume(it) } }
                .onFailure { if(continuation.isActive) continuation.resume(null) }
        } else {
            // Android 9 and 10 use the older one-shot listener, then remove it as soon as it answers.
            val listener=object : LocationListener {
                override fun onLocationChanged(location: Location) { if(continuation.isActive) continuation.resume(location) }
                @Deprecated("Deprecated in Android") override fun onStatusChanged(provider: String?,status: Int,extras: Bundle?)=Unit
                override fun onProviderEnabled(provider: String)=Unit
                override fun onProviderDisabled(provider: String) { if(continuation.isActive) continuation.resume(null) }
            }
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            runCatching { manager.requestSingleUpdate(provider,listener,null) }
                .onFailure { if(continuation.isActive) continuation.resume(null) }
        }
    }
}
