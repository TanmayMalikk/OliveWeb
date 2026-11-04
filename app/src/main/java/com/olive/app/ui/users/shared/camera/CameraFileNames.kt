// All safe-app roles: gives built-in camera captures clear, unique file names.
package com.olive.app.ui.users.shared.camera

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal enum class BuiltInCameraFile(
    val prefix: String,
    val extension: String,
) {
    IMAGE("Oliveimage", "jpg"),
    VIDEO("Olivevideo", "mp4"),
    DOCUMENT("OliveDoc", "pdf"),
}

// Adds the capture date and time so a new camera file never replaces an older one.
internal fun builtInCameraFileName(
    type: BuiltInCameraFile,
    timestampMillis: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault(),
): String {
    val formatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).apply {
        this.timeZone = timeZone
    }
    return "${type.prefix}${formatter.format(Date(timestampMillis))}.${type.extension}"
}
