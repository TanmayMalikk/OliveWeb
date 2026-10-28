// Tests username, password, PIN, and idle-lock rules for users.
package com.olive.app.security

import com.olive.app.ui.shared.*

import org.junit.Assert.*
import org.junit.Test

class SecurityPolicyTest {
    @Test
    fun newUsernamesNeedALetterAndPasswordsCannotContainTheirUsername() {
        assertFalse(InputPolicy.validUsername("12345678"))
        assertFalse(InputPolicy.validUsername("________"))
        assertTrue(InputPolicy.validUsername("letters_only"))
        assertTrue(InputPolicy.validUsername("1234567a"))
        // Existing numeric usernames remain able to sign in.
        assertTrue(InputPolicy.validExistingUsername("12345678"))
        assertFalse(InputPolicy.validAccountPassword("Test_user123!", "test_user"))
        assertFalse(InputPolicy.validAccountPassword("!123Test_user", " TEST_USER "))
        assertFalse(InputPolicy.validAccountPassword("testuser", "testuser"))
        assertTrue(InputPolicy.validAccountPassword("Unrelated123!", "test_user"))
    }

    @Test
    fun pinVerifierUsesRandomSaltAndRejectsWrongPin() {
        val first = SecretVerifier.create("123456")
        val second = SecretVerifier.create("123456")
        assertNotEquals(first, second)
        assertTrue(SecretVerifier.matches("123456", first))
        assertFalse(SecretVerifier.matches("000000", first))
        assertFalse(SecretVerifier.matches("123456", "invalid"))
    }

    @Test
    fun fifthFailureAllowsImmediateNewWindow() {
        var gate = GateState()
        repeat(5) { index ->
            gate = gate.failed()
            assertEquals(index + 1, gate.failures)
        }
        assertEquals(GateState(1), gate.failed())
        assertEquals(GateState(1), GateState().failed())
    }

    @Test
    fun idleWarningStartsAfterOneMinuteAndLocksAfterFifteenMoreSeconds() {
        assertNull(IdlePolicy.countdown(59_999))
        assertEquals(15, IdlePolicy.countdown(60_000))
        assertEquals(1, IdlePolicy.countdown(74_999))
        assertEquals(0, IdlePolicy.countdown(75_000))
    }

    @Test
    fun accessibilityCanExtendTheWarningButCannotShortenThePrivacyDefault() {
        assertNull(IdlePolicy.countdown(59_999, 60_000))
        assertEquals(60, IdlePolicy.countdown(60_000, 60_000))
        assertEquals(45, IdlePolicy.countdown(75_000, 60_000))
        assertEquals(0, IdlePolicy.countdown(120_000, 60_000))
        assertEquals(15, IdlePolicy.countdown(60_000, 0))
        assertEquals(0, IdlePolicy.countdown(75_000, 0))
    }

    @Test
    fun triggerRequiresExactNormalizedMatch() {
        assertEquals(InputPolicy.triggerHash("  MY WORD  "), InputPolicy.triggerHash("my word"))
        assertEquals(InputPolicy.triggerHash("  He L P  "), InputPolicy.triggerHash("help"))
        assertNotEquals(InputPolicy.triggerHash("my word"), InputPolicy.triggerHash("my words"))
    }

    @Test
    fun credentialsAndBirthDatesAreValidated() {
        assertTrue(InputPolicy.validUsername("Test_user"))
        assertFalse(InputPolicy.validUsername("a@b.com"))
        assertFalse(InputPolicy.validUsername("short_1"))
        assertTrue(InputPolicy.validUsername("user_123"))
        assertFalse(InputPolicy.validUsername("user name"))
        assertFalse(InputPolicy.validUsername("user-name"))
        assertTrue(InputPolicy.validExistingUsername("old_1"))
        assertFalse(InputPolicy.validPassword("1234567"))
        assertFalse(InputPolicy.validPassword("12345678!"))
        assertFalse(InputPolicy.validPassword("abcdefgh!"))
        assertFalse(InputPolicy.validPassword("Abcd1234"))
        assertTrue(InputPolicy.validPassword("Abcd123!"))
        assertEquals(InputPolicy.username("Olive_User"), InputPolicy.username("olive_user"))
        val verifier = SecretVerifier.create("Abcd1234")
        assertTrue(SecretVerifier.matches("Abcd1234", verifier))
        assertFalse(SecretVerifier.matches("abcd1234", verifier))
        assertTrue(InputPolicy.validPin("000123"))
        assertTrue(InputPolicy.validPin("12345"))
        assertTrue(InputPolicy.validPin("1234"))
        assertFalse(InputPolicy.validPin("123"))
        assertFalse(InputPolicy.validPin("1234567"))
        assertFalse(InputPolicy.validUsername("abc"))
        assertTrue(InputPolicy.validUsername("a".repeat(24)))
        assertFalse(InputPolicy.validUsername("a".repeat(25)))
        assertTrue(InputPolicy.validPassword("a1!" + "a".repeat(21)))
        assertFalse(InputPolicy.validPassword("a1!" + "a".repeat(22)))
        assertFalse(InputPolicy.validDob("2024-02-30"))
        assertFalse(InputPolicy.validDob("2999-01-01"))
        assertTrue(InputPolicy.validDob("2000-02-29"))
    }
}
