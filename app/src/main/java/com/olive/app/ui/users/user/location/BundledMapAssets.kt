package com.olive.app.ui.users.user.location

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.File
import java.io.IOException
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

private const val MAP_ASSET_HOST = "appassets.androidplatform.net"
internal const val MAP_ASSET_URL = "https://$MAP_ASSET_HOST/map/map.html"

@Volatile
private var mapTileClient: OkHttpClient? = null
private val mapClientLock = Any()

private fun getOrCreateMapClient(context: Context): OkHttpClient {
    return mapTileClient ?: synchronized(mapClientLock) {
        mapTileClient ?: run {
            val cacheDir = File(context.applicationContext.cacheDir, "map-tiles")
            val cache = Cache(cacheDir, 50L * 1024 * 1024)
            OkHttpClient.Builder()
                .cache(cache)
                .build()
                .also { mapTileClient = it }
        }
    }
}

private fun isMapTileHost(host: String): Boolean =
    host == "api.mapbox.com" ||
        host == "tile.openstreetmap.org" ||
        host.endsWith(".tile.openstreetmap.org")

private fun fetchCachedMapResource(context: Context, uri: Uri): WebResourceResponse? {
    return try {
        val client = getOrCreateMapClient(context)
        val request = Request.Builder().url(uri.toString()).build()
        val response = try {
            client.newCall(request).execute()
        } catch (_: IOException) {
            val staleRequest = Request.Builder()
                .url(uri.toString())
                .cacheControl(CacheControl.FORCE_CACHE)
                .build()
            client.newCall(staleRequest).execute()
        }
        if (!response.isSuccessful) {
            response.close()
            return null
        }
        val body = response.body
        val contentType = response.header("Content-Type") ?: "application/octet-stream"
        val mimeType = contentType.substringBefore(';').trim()
        val encoding = if (contentType.contains("charset=", ignoreCase = true)) {
            contentType.substringAfter("charset=", "").substringBefore(';').trim()
        } else if (mimeType.startsWith("text/") || mimeType.contains("json")) {
            "UTF-8"
        } else null

        val responseHeaders = mutableMapOf<String, String>()
        for (i in 0 until response.headers.size) {
            responseHeaders[response.headers.name(i)] = response.headers.value(i)
        }
        responseHeaders["access-control-allow-origin"] = "*"

        WebResourceResponse(
            mimeType,
            encoding,
            response.code,
            response.message.ifBlank { "OK" },
            responseHeaders,
            body.byteStream()
        )
    } catch (_: Exception) {
        null
    }
}

/**
 * A local HTTPS origin lets the vector renderer use workers without granting file access.
 *
 * @param context Android context used to access system services, resources, or storage.
 * @param url HTTPS URL to validate, fetch, or open.
 * @param method HTTP method for the request.
 * @return The resulting [WebResourceResponse], or `null` if unavailable.
 */
internal fun bundledMapAsset(context: Context, url: Uri, method: String = "GET"): WebResourceResponse? {
    if (url.scheme != "https") return null
    if (url.host == MAP_ASSET_HOST) {
        val path = url.path.orEmpty().removePrefix("/")
        val mime = when (path) {
            "map/map.html" -> "text/html"
            "map/leaflet.css", "map/mapbox-gl.css" -> "text/css"
            "map/leaflet.js", "map/map.js", "map/mapbox-basemap.js", "map/mapbox-gl.js" -> "text/javascript"
            "map/images/layers.png", "map/images/layers-2x.png", "map/images/marker-icon.png",
            "map/images/marker-icon-2x.png", "map/images/marker-shadow.png" -> "image/png"
            else -> null
        }
        if (mime != null) {
            try {
                return WebResourceResponse(mime, if (mime.startsWith("text/")) "UTF-8" else null, context.assets.open(path))
            } catch (_: IOException) {
                // Handle missing bundled assets without contacting the reserved host.
            }
        }
        return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), null)
    }
    if (url.host == "events.mapbox.com") {
        return WebResourceResponse("text/plain", "UTF-8", 204, "No Content", emptyMap(), null)
    }
    if (method.equals("GET", ignoreCase = true) && url.host?.let(::isMapTileHost) == true) {
        return fetchCachedMapResource(context, url)
    }
    return null
}
