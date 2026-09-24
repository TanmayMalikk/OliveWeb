package com.olive.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
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
    private suspend fun current(manager: LocationManager): Location? = suspendCancellableCoroutine { continuation ->
        val provider=when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if(provider==null) continuation.resume(null)
        else runCatching { manager.getCurrentLocation(provider,null,{ it.run() }) { if(continuation.isActive) continuation.resume(it) } }
            .onFailure { if(continuation.isActive) continuation.resume(null) }
    }
}
