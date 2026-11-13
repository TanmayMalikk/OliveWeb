// Shared connectivity check for features that require a verified internet path.
package com.olive.app.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

internal fun hasValidatedInternetConnection(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

