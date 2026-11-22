package com.olive.app.ui.users.user.location

import com.olive.app.data.location.LoadedSupportArea
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

/**
 * Caches nearby support service queries in memory by geographic grid
 * to provide 0ms instant display when revisiting the location map.
 */
internal object SupportServiceSpatialCache {
    private val cache = ConcurrentHashMap<Pair<Long, Long>, LoadedSupportArea>()

    /**
     * Converts coordinates into a ~5.5 km geographic grid bucket.
     */
    private fun gridKey(latitude: Double, longitude: Double): Pair<Long, Long> {
        val latGrid = (latitude / 0.05).roundToLong()
        val lonGrid = (longitude / 0.05).roundToLong()
        return latGrid to lonGrid
    }

    /**
     * Looks up a previously fetched support area for the coordinates.
     *
     * @param latitude Target latitude in degrees.
     * @param longitude Target longitude in degrees.
     * @return The cached [LoadedSupportArea] or null if not in cache.
     */
    fun get(latitude: Double, longitude: Double): LoadedSupportArea? =
        cache[gridKey(latitude, longitude)]

    /**
     * Stores a fetched support area in the spatial cache.
     *
     * @param area Loaded support area including center and service list.
     */
    fun put(area: LoadedSupportArea) {
        val key = gridKey(area.center.latitude, area.center.longitude)
        cache[key] = area
    }

    /**
     * Clears all cached shelter coordinates from memory on session lock or quick exit.
     */
    fun clear() {
        cache.clear()
    }
}
