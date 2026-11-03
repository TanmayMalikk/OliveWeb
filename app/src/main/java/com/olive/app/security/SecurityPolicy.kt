// All signed-in roles: checks security rules.
package com.olive.app.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.util.Base64
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

// Passwords and PINs get checked through a slow salted verifier, never saved as readable text.
object SecretVerifier {
    private const val PASSWORD_ITERATIONS = 600_000
    private const val PIN_ITERATIONS = 120_000
    // Creates a salted PIN verifier without storing the PIN itself.
    fun create(secret: String): String = encode(secret, PASSWORD_ITERATIONS)

    // The PIN stays in the Keystore-encrypted phone vault; fewer rounds make local unlock quick.
    fun createPin(pin: String): String = encode(pin, PIN_ITERATIONS)
    // Detects older PIN records that need stronger hashing after a valid sign-in.
    fun pinNeedsUpgrade(encoded: String) =
        encoded.substringBefore(':').toIntOrNull()?.let { it > PIN_ITERATIONS } ?: false

    // Formats a PIN or trigger verifier for encrypted storage.
    private fun encode(secret: String, iterations: Int): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "$iterations:${Base64.getEncoder().encodeToString(salt)}:${
            Base64.getEncoder().encodeToString(derive(secret, salt, iterations))
        }"
    }

    // Checks entered text against a saved verifier in constant time.
    fun matches(secret: String, encoded: String): Boolean = runCatching {
        val parts = encoded.split(':')
        require(parts.size == 3)
        val iterations = parts[0].toInt()
        require(iterations in 100_000..1_000_000)
        MessageDigest.isEqual(
            Base64.getDecoder().decode(parts[2]),
            derive(secret, Base64.getDecoder().decode(parts[1]), iterations)
        )
    }.getOrDefault(false)

    // Creates the derive that protects app security.
    private fun derive(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        val chars = secret.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }
}

// Change username, password, PIN, trigger, or DOB limits here, then update the matching screen message and test.
object InputPolicy {
    // Trims and lowercases a username before the app checks or stores it.
    fun username(value: String) = value.trim().lowercase(Locale.ROOT)
    // Makes sure the username contains at least one letter.
    fun usernameHasLetter(value: String) = value.any { it in 'a'..'z' || it in 'A'..'Z' }
    // Accepts only a new username with 8–24 letters, numbers, or underscores.
    fun validUsername(value: String) =
        username(value).matches(Regex("[a-z0-9_]{8,24}")) && usernameHasLetter(value)

    // Existing accounts created under the former four-character minimum remain able to sign in.
    fun validExistingUsername(value: String) = username(value).matches(Regex("[a-z0-9_]{4,24}"))
    // Keeps passwords between 8 and 24 characters.
    fun passwordHasValidLength(value: String) = value.length in 8..24
    // Makes sure the password contains at least one letter.
    fun passwordHasLetter(value: String) = value.any(Char::isLetter)
    // Makes sure the password contains at least one number.
    fun passwordHasNumber(value: String) = value.any(Char::isDigit)
    // Makes sure the password contains a symbol that is not a space.
    fun passwordHasSpecialCharacter(value: String) =
        value.any { !it.isLetterOrDigit() && !it.isWhitespace() }

    // Accepts a password only when every length and character rule passes.
    fun validPassword(value: String) = passwordHasValidLength(value) && passwordHasLetter(value) &&
            passwordHasNumber(value) && passwordHasSpecialCharacter(value)

    // Stops a password from containing the account username.
    fun passwordExcludesUsername(value: String, accountUsername: String): Boolean =
        username(accountUsername).let { it.isNotEmpty() && !value.contains(it, ignoreCase = true) }

    // Applies the password rules and also rejects the account username inside it.
    fun validAccountPassword(value: String, accountUsername: String) =
        validPassword(value) && passwordExcludesUsername(value, accountUsername)

    // Accepts only a 4–6 digit private-entry PIN.
    fun validPin(value: String) = value.matches(Regex("[0-9]{4,6}"))
    // Checks whether cover input matches the saved discreet trigger phrase.
    fun validTrigger(value: String) = value.trim().length in 3..64
    // Creates the trigger hash that protects app security.
    fun triggerHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(
            value.lowercase(Locale.ROOT).filterNot(Char::isWhitespace).toByteArray(Charsets.UTF_8)
        )
        .joinToString("") { "%02x".format(it) }

    // Creates the legacy trigger hash that protects app security.
    fun legacyTriggerHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.trim().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    // Accepts real birth dates from today back to 130 years ago.
    fun validDob(value: String): Boolean = runCatching {
        val date = LocalDate.parse(value)
        date <= LocalDate.now() && date >= LocalDate.now().minusYears(130)
    }.getOrDefault(false)
}

// Count five failures without a timed cooldown; keep DeviceStore's reset and SessionAccess's exit threshold aligned.
data class GateState(val failures: Int = 0) {
    // Returns true when the failed-PIN limit has locked private entry.
    fun failed(): GateState =
        GateState(if (failures in 0..3) failures + 1 else if (failures == 4) 5 else 1)
}

// One minute without interaction starts a warning; accessibility settings can extend its fifteen-second default.
object IdlePolicy {
    // Calculates the remaining failed-entry cooldown for older saved records.
    fun countdown(elapsedMs: Long, warningDurationMs: Long = 15_000L): Int? {
        val lockAt = 60_000L + warningDurationMs.coerceIn(15_000L, Int.MAX_VALUE.toLong())
        return when {
            elapsedMs < 60_000L -> null
            elapsedMs >= lockAt -> 0
            else -> ((lockAt - elapsedMs + 999L) / 1000L).toInt()
        }
    }
}
