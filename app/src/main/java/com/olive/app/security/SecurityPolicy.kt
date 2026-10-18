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
    fun create(secret: String): String = encode(secret,PASSWORD_ITERATIONS)
    // The PIN stays in the Keystore-encrypted phone vault; fewer rounds make local unlock quick.
    fun createPin(pin: String): String = encode(pin,PIN_ITERATIONS)
    fun pinNeedsUpgrade(encoded: String) = encoded.substringBefore(':').toIntOrNull()?.let { it > PIN_ITERATIONS } ?: false
    private fun encode(secret: String, iterations: Int): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "$iterations:${Base64.getEncoder().encodeToString(salt)}:${Base64.getEncoder().encodeToString(derive(secret, salt, iterations))}"
    }
    fun matches(secret: String, encoded: String): Boolean = runCatching {
        val parts = encoded.split(':')
        require(parts.size == 3)
        val iterations = parts[0].toInt()
        require(iterations in 100_000..1_000_000)
        MessageDigest.isEqual(Base64.getDecoder().decode(parts[2]), derive(secret, Base64.getDecoder().decode(parts[1]), iterations))
    }.getOrDefault(false)
    private fun derive(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        val chars = secret.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword(); chars.fill('\u0000') }
    }
}

// Change username, password, PIN, trigger, or DOB limits here, then update the matching screen message and test.
object InputPolicy {
    fun username(value: String) = value.trim().lowercase(Locale.ROOT)
    fun usernameHasLetter(value: String) = value.any { it in 'a'..'z' || it in 'A'..'Z' }
    fun validUsername(value: String) = username(value).matches(Regex("[a-z0-9_]{8,24}")) && usernameHasLetter(value)
    // Existing accounts created under the former four-character minimum remain able to sign in.
    fun validExistingUsername(value: String) = username(value).matches(Regex("[a-z0-9_]{4,24}"))
    fun passwordHasValidLength(value: String) = value.length in 8..24
    fun passwordHasLetter(value: String) = value.any(Char::isLetter)
    fun passwordHasNumber(value: String) = value.any(Char::isDigit)
    fun passwordHasSpecialCharacter(value: String) = value.any { !it.isLetterOrDigit() && !it.isWhitespace() }
    fun validPassword(value: String) = passwordHasValidLength(value) && passwordHasLetter(value) &&
        passwordHasNumber(value) && passwordHasSpecialCharacter(value)
    fun passwordExcludesUsername(value: String, accountUsername: String): Boolean =
        username(accountUsername).let { it.isNotEmpty() && !value.contains(it,ignoreCase=true) }
    fun validAccountPassword(value: String, accountUsername: String) =
        validPassword(value) && passwordExcludesUsername(value,accountUsername)
    fun validPin(value: String) = value.matches(Regex("[0-9]{4,6}"))
    fun validTrigger(value: String) = value.trim().length in 3..64
    fun triggerHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.lowercase(Locale.ROOT).filterNot(Char::isWhitespace).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun legacyTriggerHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.trim().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun validDob(value: String): Boolean = runCatching {
        val date = LocalDate.parse(value)
        date <= LocalDate.now() && date >= LocalDate.now().minusYears(130)
    }.getOrDefault(false)
}

// Count five failures without a timed cooldown; keep DeviceStore's reset and SessionAccess's exit threshold aligned.
data class GateState(val failures: Int = 0) {
    fun failed(): GateState = GateState(if (failures in 0..3) failures + 1 else if (failures == 4) 5 else 1)
}

// One minute without interaction starts a warning; accessibility settings can extend its fifteen-second default.
object IdlePolicy {
    fun countdown(elapsedMs: Long,warningDurationMs:Long=15_000L): Int? {
        val lockAt=60_000L+warningDurationMs.coerceIn(15_000L,Int.MAX_VALUE.toLong())
        return when {
            elapsedMs < 60_000L -> null
            elapsedMs >= lockAt -> 0
            else -> ((lockAt-elapsedMs+999L)/1000L).toInt()
        }
    }
}
