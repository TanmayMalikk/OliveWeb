// Tests large deterministic input sets for credential rules that are easy to miss in hand-written examples.
package com.olive.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UnexpectedCredentialPropertyTest {
    // Inputs: 1,000 seeded strings containing ASCII, Unicode, whitespace, controls, and punctuation. Expected: username validation exactly follows the documented rule.
    @Test
    fun randomUsernameInputsAlwaysFollowThePublishedRule() {
        val seed = 0xC0FFEE
        val random = kotlin.random.Random(seed)
        val alphabet = charArrayOf(
            'a', 'Z', '0', '9', '_', '-', '@', '\'', ' ', '\n', '\u0000', 'é', '中', '１', '.', '/', '\\',
        )
        repeat(1_000) { case ->
            val input = buildString {
                repeat(random.nextInt(0, 40)) { append(alphabet[random.nextInt(alphabet.size)]) }
            }
            val normalized = input.trim().lowercase(java.util.Locale.ROOT)
            val expected = normalized.matches(Regex("[a-z0-9_]{8,24}")) &&
                    input.any { it in 'a'..'z' || it in 'A'..'Z' }
            assertEquals(
                "seed=$seed case=$case input=${input.toCharArray().joinToString { it.code.toString() }}",
                expected,
                InputPolicy.validUsername(input),
            )
        }
    }

    // Inputs: every printable ASCII punctuation character inside a username and PIN. Expected: no punctuation can bypass either validator.
    @Test
    fun everyAsciiPunctuationCharacterIsRejectedInUsernamesAndPins() {
        val punctuation = (33..126).map(Int::toChar).filterNot { it.isLetterOrDigit() || it == '_' }
        for (character in punctuation) {
            assertFalse("Username accepted punctuation code=${character.code}", InputPolicy.validUsername("olive${character}user"))
            assertFalse("PIN accepted punctuation code=${character.code}", InputPolicy.validPin("12${character}34"))
        }
    }
}
