package com.olive.app.data.account

import java.time.LocalDate

// Incident dates describe past events; malformed or future dates must be corrected explicitly.
internal fun parseIncidentDate(value: String, today: LocalDate = LocalDate.now()): LocalDate? {
    val text = value.trim()
    if (!text.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) return null
    return runCatching { LocalDate.parse(text) }.getOrNull()
        ?.takeIf { it.year >= 1 && !it.isAfter(today) }
}
