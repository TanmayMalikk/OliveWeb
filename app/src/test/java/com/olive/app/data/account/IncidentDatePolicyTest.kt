package com.olive.app.data.account

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class IncidentDatePolicyTest {
    @Test fun rejectsMalformedImpossibleAndFutureDates() {
        val today = LocalDate.of(2026, 11, 15)
        listOf("222222-02-22", "2026-02-30", "2027-01-01", "2026-11-16", "0000-01-01", "").forEach {
            assertNull(it, parseIncidentDate(it, today))
        }
        assertEquals(today, parseIncidentDate("2026-11-15", today))
        assertEquals(LocalDate.of(2024, 2, 29), parseIncidentDate("2024-02-29", today))
    }
}
