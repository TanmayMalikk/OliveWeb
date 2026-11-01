// Tests username, password, PIN, and idle-lock rules for users.
package com.olive.app.security

import org.junit.Assert.*
import org.junit.Test

class SecurityPolicyTest {
    // Inputs: the values shown below. Expected: new usernames need aletter and passwords cannot contain their username.
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

    // Inputs: the values shown below. Expected: pin verifier uses random salt and rejects wrong pin.
    @Test
    fun pinVerifierUsesRandomSaltAndRejectsWrongPin() {
        val first = SecretVerifier.create("123456")
        val second = SecretVerifier.create("123456")
        assertNotEquals(first, second)
        assertTrue(SecretVerifier.matches("123456", first))
        assertFalse(SecretVerifier.matches("000000", first))
        assertFalse(SecretVerifier.matches("123456", "invalid"))
    }

    // Inputs: the values shown below. Expected: fifth failure allows immediate new window.
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

    // Inputs: the values shown below. Expected: idle warning starts after one minute and locks after fifteen more seconds.
    @Test
    fun idleWarningStartsAfterOneMinuteAndLocksAfterFifteenMoreSeconds() {
        assertNull(IdlePolicy.countdown(59_999))
        assertEquals(15, IdlePolicy.countdown(60_000))
        assertEquals(1, IdlePolicy.countdown(74_999))
        assertEquals(0, IdlePolicy.countdown(75_000))
    }

    // Inputs: the values shown below. Expected: accessibility can extend the warning but cannot shorten the privacy default.
    @Test
    fun accessibilityCanExtendTheWarningButCannotShortenThePrivacyDefault() {
        assertNull(IdlePolicy.countdown(59_999, 60_000))
        assertEquals(60, IdlePolicy.countdown(60_000, 60_000))
        assertEquals(45, IdlePolicy.countdown(75_000, 60_000))
        assertEquals(0, IdlePolicy.countdown(120_000, 60_000))
        assertEquals(15, IdlePolicy.countdown(60_000, 0))
        assertEquals(0, IdlePolicy.countdown(75_000, 0))
    }

    // Inputs: the values shown below. Expected: trigger requires exact normalized match.
    @Test
    fun triggerRequiresExactNormalizedMatch() {
        assertEquals(InputPolicy.triggerHash("  MY WORD  "), InputPolicy.triggerHash("my word"))
        assertEquals(InputPolicy.triggerHash("  He L P  "), InputPolicy.triggerHash("help"))
        assertNotEquals(InputPolicy.triggerHash("my word"), InputPolicy.triggerHash("my words"))
    }

    // Inputs: the values shown below. Expected: credentials and birth dates are validated.
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

    // Inputs: the values shown below. Expected: malformed and unicode credential values fail closed.
    @Test
    fun malformedAndUnicodeCredentialValuesFailClosed() {
        assertFalse(InputPolicy.validUsername("abcdefgh@"))
        assertFalse(InputPolicy.validUsername("éééééééé"))
        assertFalse(InputPolicy.validUsername("name\u0000user"))
        assertFalse(InputPolicy.validPassword("Password1 "))
        assertFalse(InputPolicy.validPassword("        1!"))
        assertFalse(InputPolicy.validPin("12a4"))
        assertFalse(InputPolicy.validPin("１２３４"))
        assertFalse(InputPolicy.validTrigger("   "))
        assertFalse(InputPolicy.validTrigger("x".repeat(65)))
        assertFalse(InputPolicy.validDob("not-a-date"))
        assertFalse(InputPolicy.validDob(""))
    }

    // Inputs: the values shown below. Expected: verifier rejects corrupt base64 iterations and field counts.
    @Test
    fun verifierRejectsCorruptBase64IterationsAndFieldCounts() {
        listOf(
            "",
            "not:a:verifier",
            "99999:YWJj:YWJj",
            "1000001:YWJj:YWJj",
            "120000:not-base64:also-bad",
            "120000:YWJj",
        ).forEach { encoded ->
            assertFalse("Unexpected match for $encoded", SecretVerifier.matches("secret", encoded))
        }

        val passwordHash = SecretVerifier.create("secret")
        val pinHash = SecretVerifier.createPin("1234")
        assertTrue(SecretVerifier.pinNeedsUpgrade(passwordHash))
        assertFalse(SecretVerifier.pinNeedsUpgrade(pinHash))
        assertFalse(SecretVerifier.pinNeedsUpgrade("invalid"))
    }

    // Inputs: the values shown below. Expected: idle policy handles negative and very large durations.
    @Test
    fun idlePolicyHandlesNegativeAndVeryLargeDurations() {
        assertNull(IdlePolicy.countdown(-1L))
        assertNull(IdlePolicy.countdown(0L, Long.MIN_VALUE))
        assertEquals(15, IdlePolicy.countdown(60_000L, -1L))
        assertEquals(0, IdlePolicy.countdown(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    // Gives every credential field SQL-like control text and expects validation to reject it as invalid input.
    @Test
    fun injectionLikeCredentialTextIsRejectedInsteadOfUsedAsAQuery() {
        val injectionText = "' OR 1=1; DROP TABLE users; --"

        assertFalse(InputPolicy.validUsername(injectionText))
        assertFalse(InputPolicy.validExistingUsername(injectionText))
        assertFalse(InputPolicy.validPin(injectionText))
        assertFalse(InputPolicy.validDob(injectionText))
        assertFalse(InputPolicy.validAccountPassword(injectionText, "normal_user"))
    }

    // Gives the idle timer Long extremes and expects stable lock decisions without arithmetic overflow.
    @Test
    fun idleTimerDoesNotOverflowAtLongBoundaries() {
        assertNull(IdlePolicy.countdown(Long.MIN_VALUE, Long.MAX_VALUE))
        assertEquals(0, IdlePolicy.countdown(Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
