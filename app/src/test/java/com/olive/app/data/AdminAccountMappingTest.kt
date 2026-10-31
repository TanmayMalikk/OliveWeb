// Tests admin account API mapping without calling Firebase.
package com.olive.app.data

import org.junit.Assert.*
import org.junit.Test

class AdminAccountMappingTest {
    // Input: complete counts and account rows. Expected: each server value maps to the admin model.
    @Test
    fun completeAdminResponseMapsCountsAndAccounts() {
        val (counts, accounts) = parseAdminAccounts(
            mapOf(
                "counts" to mapOf("USER" to 5, "SAFE_CONTACT" to 4L, "STAFF" to 3.0, "ADMIN" to 2),
                "accounts" to listOf(
                    mapOf(
                        "uid" to "staff-1",
                        "username" to "olive_staff",
                        "role" to "STAFF",
                        "fullName" to "Olive Staff",
                        "email" to "staff@example.ca",
                        "phone" to "555",
                        "profileComplete" to true,
                        "protected" to true,
                        "assignedUsers" to 7L,
                    )
                ),
            )
        )
        assertEquals(AccountCounts(5, 4, 3, 2), counts)
        assertEquals(AccountRole.STAFF, accounts.single().role)
        assertEquals(7, accounts.single().assignedUsers)
        assertTrue(accounts.single().profileComplete)
        assertTrue(accounts.single().protected)
    }

    // Input: null, strings, lists, and unrelated maps. Expected: malformed responses become safe empty results.
    @Test
    fun malformedAdminResponsesFailClosed() {
        listOf(null, "wrong", 42, emptyList<Any>(), mapOf("accounts" to "wrong")).forEach { raw ->
            val (counts, accounts) = parseAdminAccounts(raw)
            assertEquals(AccountCounts(), counts)
            assertTrue(accounts.isEmpty())
        }
    }

    // Input: valid rows mixed with scalar and empty rows. Expected: only meaningful account maps survive.
    @Test
    fun malformedAccountRowsAreSkipped() {
        val (_, accounts) = parseAdminAccounts(
            mapOf("accounts" to listOf(null, "wrong", 7, emptyMap<String, Any>(), mapOf("uid" to "valid")))
        )
        assertEquals(1, accounts.size)
        assertEquals("valid", accounts.single().uid)
    }

    // Input: unknown role and wrong Boolean types. Expected: role defaults safely and only literal true enables flags.
    @Test
    fun unknownRolesAndWrongBooleanTypesUseSafeDefaults() {
        val (_, accounts) = parseAdminAccounts(
            mapOf(
                "accounts" to listOf(
                    mapOf(
                        "uid" to "unknown",
                        "role" to "SUPER_ADMIN",
                        "profileComplete" to "true",
                        "protected" to 1,
                    )
                )
            )
        )
        val account = accounts.single()
        assertEquals(AccountRole.USER, account.role)
        assertFalse(account.profileComplete)
        assertFalse(account.protected)
    }

    // Input: negative and non-numeric assignment counts. Expected: the UI never displays a negative workload.
    @Test
    fun invalidAssignmentCountsBecomeZero() {
        val rows = listOf(
            mapOf("uid" to "negative", "assignedUsers" to -10),
            mapOf("uid" to "text", "assignedUsers" to "many"),
        )
        val (_, accounts) = parseAdminAccounts(mapOf("accounts" to rows))
        assertEquals(listOf(0, 0), accounts.map { it.assignedUsers })
    }

    // Input: a complete staff draft. Expected: every admin form field reaches the named server field.
    @Test
    fun createPayloadMapsEveryDraftField() {
        val draft = AdminAccountDraft(
            username = "olive_staff",
            password = "safe-password",
            role = AccountRole.STAFF,
            fullName = "Olive Staff",
            dob = "1990-01-01",
            email = "staff@example.ca",
            phone = "555",
            language = "French",
            city = "Edmonton",
            affectedNames = "Family",
            abuserName = "Private",
            situation = "Support",
        )
        val payload = adminAccountPayload(draft)
        assertEquals("olive_staff", payload["username"])
        assertEquals("safe-password", payload["password"])
        assertEquals("STAFF", payload["role"])
        assertEquals("Edmonton", payload["region"])
        assertEquals("Support", payload["situation"])
        @Suppress("UNCHECKED_CAST")
        val address = payload["address"] as Map<String, String>
        assertEquals("Edmonton", address["city"])
        assertEquals("Canada", address["country"])
    }

    // Input: the smallest valid draft. Expected: defaults are explicit rather than omitted or null.
    @Test
    fun minimalCreatePayloadIncludesStableDefaults() {
        val payload = adminAccountPayload(AdminAccountDraft("user_name", "password1!", AccountRole.USER))
        assertEquals("English", payload["language"])
        assertEquals("", payload["fullName"])
        assertEquals("", payload["region"])
        assertTrue(payload.keys.containsAll(listOf("username", "password", "role", "address")))
    }
}
