// Cover users: reads the device location for cover app features.
package com.olive.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
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

// Grab a one-off coarse location for cover content; nothing from here is saved to the account.
object CoverLocation {
    // Joins available address parts into the city label shown by the cover.
    suspend fun city(context: Context, fallback: String): String {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return fallback
        val manager = context.getSystemService(LocationManager::class.java)
        val cached = newestCachedLocation(manager)
        val location = cached ?: withTimeoutOrNull(6.seconds) { current(context, manager) }
        if (location == null) return fallback
        return reverseGeocode(context, location) ?: fallback
    }

    // Keeps the newest cached GPS point returned by Android.
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION])
    private fun newestCachedLocation(manager: LocationManager): Location? =
        manager.getProviders(true)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull(Location::getTime)

    // Requests the best available current device location.
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION])
    private suspend fun current(context: Context, manager: LocationManager): Location? =
        suspendCancellableCoroutine { continuation ->
            val provider = when {
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> null
            }
            if (provider == null) continuation.resume(null)
            else runCatching {
                LocationManagerCompat.getCurrentLocation(
                    manager, provider, null as android.os.CancellationSignal?,
                    ContextCompat.getMainExecutor(context)
                ) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            }.onFailure { if (continuation.isActive) continuation.resume(null) }
        }

    // Gives location sharing the reverse geocode it needs.
    private suspend fun reverseGeocode(context: Context, location: Location): String? {
        if (Build.VERSION.SDK_INT < 33 || !Geocoder.isPresent()) return null
        val address = withTimeoutOrNull(6.seconds) {
            suspendCancellableCoroutine<Address?> { continuation ->
                Geocoder(context, Locale.getDefault()).getFromLocation(
                    location.latitude, location.longitude, 1,
                    // Converts the cover's coordinates into the first local address returned by Android.
                    object : Geocoder.GeocodeListener {
                        // Runs the geocode callback when location sharing finishes that step.
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                        }

                        // Returns a readable location error when the device request fails.
                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    })
            }
        } ?: return null
        return withContext(Dispatchers.Default) {
            listOfNotNull(address.locality, address.adminArea, address.countryName)
                .filter(String::isNotBlank).joinToString(", ").takeUnless(String::isBlank)
        }
    }
}
