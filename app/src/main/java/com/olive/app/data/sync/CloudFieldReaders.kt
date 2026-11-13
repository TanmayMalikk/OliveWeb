package com.olive.app.data.sync

/** Reads a complete string-keyed record without trusting erased Firebase generic types. */
internal fun Any?.cloudStringMap(): Map<String, String>? {
    val fields = this as? Map<*, *> ?: return null
    return buildMap {
        for ((key, value) in fields) {
            if (key !is String || value !is String) return null
            put(key, value)
        }
    }
}
