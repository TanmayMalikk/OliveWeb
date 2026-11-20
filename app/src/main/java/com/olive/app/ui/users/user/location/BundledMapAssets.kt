package com.olive.app.ui.users.user.location

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.IOException

private const val MAP_ASSET_HOST = "appassets.androidplatform.net"
internal const val MAP_ASSET_URL = "https://$MAP_ASSET_HOST/map/map.html"

/**
 * A local HTTPS origin lets the vector renderer use workers without granting file access.
 *
 * @param context Android context used to access system services, resources, or storage.
 * @param url HTTPS URL to validate, fetch, or open.
 * @return The resulting [WebResourceResponse], or `null` if unavailable.
 */
internal fun bundledMapAsset(context: Context, url: Uri): WebResourceResponse? {
    if (url.scheme != "https" || url.host != MAP_ASSET_HOST) return null
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
