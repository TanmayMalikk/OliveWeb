// User location sharing: validates the selected sharing period and converts it to milliseconds.
package com.olive.app.ui.users.user.location

internal object ShareDurationPolicy {
    const val TEST_DURATION_MILLISECONDS = 10_000L
    const val MAX_CUSTOM_HOURS = 72L
    private const val MILLISECONDS_PER_HOUR = 60L * 60L * 1_000L

    // Converts an allowed number of hours to milliseconds; values outside the supported range are rejected.
    fun millisecondsForHours(hours: Long): Long? =
        hours.takeIf { it in 1..MAX_CUSTOM_HOURS }?.times(MILLISECONDS_PER_HOUR)

    // Reads custom hours from the field and rejects text, zero, negative values, and overflow.
    fun customDurationMilliseconds(value: String): Long? =
        value.toLongOrNull()?.let(::millisecondsForHours)
}
