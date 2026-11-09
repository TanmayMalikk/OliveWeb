// All signed-in roles: stores device state.
package com.olive.app.security

import com.olive.app.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.util.Base64
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import com.olive.app.data.AccountRole
import com.olive.app.data.UserProfile


// This is the private locker on the phone: PIN verifier, cached profile, and failed-attempt history all stay encrypted.
class DeviceStore(
    private val vault: EncryptedVault,
    activityVault: EncryptedVault,
) {
    private val histories = DeviceActivityStore(activityVault)

    // Startup needs the enrollment and profile together; decrypt the vault once instead of once per value.
    fun currentSession(): DeviceSession? {
        val root = vault.read()
        val uid = root.optString("activeUid")
        val entry = root.optJSONObject("enrollments")?.optJSONObject(uid) ?: return null
        val savedProfile = root.optJSONObject("profiles")?.optJSONObject(uid) ?: return null
        return DeviceSession(
            enrollment(uid, entry),
            UserProfile.fromJson(savedProfile),
        )
    }

    // Loads the account currently linked to this phone.
    fun current(): DeviceEnrollment? {
        val root = vault.read()
        val uid = root.optString("activeUid")
        val entry = root.optJSONObject("enrollments")?.optJSONObject(uid) ?: return null
        return enrollment(uid, entry)
    }

    // A package update can leave Firebase signed in while the local active pointer is missing; relink only a saved enrollment.
    fun restoreActive(uid: String): DeviceEnrollment? {
        var restored: DeviceEnrollment? = null
        vault.update { root ->
            val entry = root.optJSONObject("enrollments")?.optJSONObject(uid) ?: return@update
            if (root.optJSONObject("profiles")?.optJSONObject(uid) == null) return@update
            root.put("activeUid", uid)
            restored = enrollment(uid, entry)
        }
        return restored
    }

    // Creates a PIN-based device enrollment and saves the protected account key.
    fun enroll(identity: AccountIdentity, profile: UserProfile, pin: String): DeviceEnrollment {
        require(InputPolicy.validPin(pin))
        val verifier = SecretVerifier.createPin(pin)
        vault.update { root ->
            root.child("enrollments").put(
                identity.uid, JSONObject()
                    .put("pinVerifier", verifier).put("triggerHash", profile.triggerHash)
                    .put("role", profile.role.name)
                    .put("failures", 0)
            )
            root.child("profiles").put(identity.uid, profile.toJson())
            root.put("activeUid", identity.uid)
        }
        recordSettings(
            identity.uid, SettingsEvent(
                area = "Onboarding", action = "Created profile",
                changes = buildList {
                    add(FieldChange("Account type", "Empty", profile.role.title))
                    add(FieldChange("Full name", "Empty", profile.fullName))
                    add(FieldChange("Date of birth", "Empty", profile.dob))
                    add(FieldChange("Language", "Empty", profile.language))
                    add(FieldChange("City", "Empty", profile.address.city))
                    if (profile.role == AccountRole.USER) {
                        add(FieldChange("Cover app", "Empty", profile.coverType.title))
                        add(FieldChange("Trigger word", "Not set", "Set"))
                    }
                })
        )
        return DeviceEnrollment(identity.uid, profile.triggerHash, profile.role)
    }

    // Rebuilds one device enrollment from its saved JSON fields.
    private fun enrollment(uid: String, entry: JSONObject) = DeviceEnrollment(
        uid,
        entry.optString("triggerHash"),
        AccountRole.fromStored(entry.optString("role")),
        SafeEntryMethod.fromStored(entry.optString("entryMethod")),
        entry.has("pinVerifier")
    )

    // Returns the short-lived proof that Android device authentication succeeded.
    internal fun deviceLockGrant(uid: String): JSONObject {
        val entry = vault.read().getJSONObject("enrollments").getJSONObject(uid)
        check(SafeEntryMethod.fromStored(entry.optString("entryMethod")) == SafeEntryMethod.DEVICE_LOCK)
        return entry.getJSONObject("deviceLockGrant")
    }

    // Creates a device-lock enrollment while keeping an optional backup app PIN.
    internal fun enrollDeviceLock(
        identity: AccountIdentity,
        profile: UserProfile,
        grant: JSONObject
    ): DeviceEnrollment {
        require(profile.role == AccountRole.USER)
        vault.update { root ->
            root.child("enrollments").put(
                identity.uid, JSONObject()
                    .put("triggerHash", profile.triggerHash).put("role", profile.role.name)
                    .put("entryMethod", SafeEntryMethod.DEVICE_LOCK.name)
                    .put("deviceLockGrant", grant).put("failures", 0)
            )
            root.child("profiles").put(identity.uid, profile.toJson())
            root.put("activeUid", identity.uid)
        }
        return DeviceEnrollment(
            identity.uid,
            profile.triggerHash,
            profile.role,
            SafeEntryMethod.DEVICE_LOCK,
            false
        )
    }

    // Preserve the other configured method when changing the preference or replacing a PIN.
    internal fun updateSafeEntry(
        expected: DeviceEnrollment,
        method: SafeEntryMethod?,
        pin: String?,
        grant: JSONObject?
    ): DeviceEnrollment {
        require(pin == null || InputPolicy.validPin(pin))
        val verifier = pin?.let(SecretVerifier::createPin)
        var updated: DeviceEnrollment? = null
        vault.update { root ->
            check(root.optString("activeUid") == expected.uid)
            val entry = root.getJSONObject("enrollments").getJSONObject(expected.uid)
            check(enrollment(expected.uid, entry) == expected && expected.role == AccountRole.USER)
            verifier?.let { entry.put("pinVerifier", it) }
            grant?.let { entry.put("deviceLockGrant", it) }
            val chosen = method ?: expected.entryMethod
            check(if (chosen == SafeEntryMethod.APP_PIN) entry.has("pinVerifier") else entry.has("deviceLockGrant"))
            entry.put("entryMethod", chosen.name).put("failures", 0)
            updated = enrollment(expected.uid, entry)
        }
        return checkNotNull(updated)
    }

    // Safe contacts, staff, and admins use account login only, so their local session never stores a device PIN.
    fun enrollWithoutPin(identity: AccountIdentity, profile: UserProfile): DeviceEnrollment {
        require(profile.role != AccountRole.USER)
        vault.update { root ->
            root.child("enrollments").put(
                identity.uid, JSONObject()
                    .put("triggerHash", "").put("role", profile.role.name).put("failures", 0)
            )
            root.child("profiles").put(identity.uid, profile.toJson())
            root.put("activeUid", identity.uid)
        }
        return DeviceEnrollment(identity.uid, "", profile.role, hasAppPin = false)
    }

    // Deletes the account's backup PIN while leaving its device-lock enrollment unchanged.
    fun removePin(uid: String) {
        vault.update { root ->
            root.optJSONObject("enrollments")?.optJSONObject(uid)?.apply {
                remove("pinVerifier")
                remove("lockedUntil")
                put("failures", 0)
            }
        }
    }

    // Verify only the active User enrollment and count failures here; SessionAccess handles routing after the result.
    fun verify(uid: String, pin: String, now: Long = System.currentTimeMillis()): PinResult {
        require(InputPolicy.validPin(pin))
        var result: PinResult = PinResult.Rejected(0)
        vault.update { root ->
            require(root.optString("activeUid") == uid) { "PIN account is no longer active" }
            val saved = root.getJSONObject("enrollments").getJSONObject(uid)
            require(AccountRole.fromStored(saved.optString("role")) == AccountRole.USER) { "This account does not use a PIN" }
            check(enrollment(uid, saved).allowsAppPin())
            val verifier = saved.getString("pinVerifier")
            val accepted = SecretVerifier.matches(pin, verifier)
            val next = if (accepted) GateState() else GateState(saved.optInt("failures")).failed()
            if (accepted && SecretVerifier.pinNeedsUpgrade(verifier)) saved.put(
                "pinVerifier",
                SecretVerifier.createPin(pin)
            )
            saved.put("failures", if (next.failures >= 5) 0 else next.failures)
            // Ignore and remove cooldowns written by earlier app versions.
            saved.remove("lockedUntil")
            // Record the failed attempt without storing the PIN that was entered.
            if (!accepted) {
                val logs = root.child("attempts")
                val entries = logs.optJSONArray(uid) ?: JSONArray().also { logs.put(uid, it) }
                entries.put(
                    JSONObject().put("id", UUID.randomUUID().toString()).put("timestamp", now)
                )
            }
            result = if (accepted) PinResult.Accepted else PinResult.Rejected(next.failures)
        }
        return result
    }

    // Reads the private profile saved for this account on the phone.
    fun profile(uid: String): UserProfile =
        UserProfile.fromJson(vault.read().getJSONObject("profiles").getJSONObject(uid))

    // Loads the profile, failed PIN attempts, and pending-sync state together.
    fun unlockSnapshot(uid: String): Triple<UserProfile, List<FailedAttempt>, Boolean> {
        val root = vault.read()
        val profile = UserProfile.fromJson(root.getJSONObject("profiles").getJSONObject(uid))
        val attempts = attemptsFrom(root, uid)
        val pending = root.optJSONObject("pendingSync")?.optBoolean(uid) ?: false
        return Triple(profile, attempts, pending)
    }

    // Replaces the phone's saved copy of an account profile.
    fun cacheProfile(
        uid: String,
        profile: UserProfile,
        pending: Boolean = false,
        audit: SettingsEvent? = null
    ) {
        vault.update { root ->
            root.child("profiles").put(uid, profile.toJson())
            root.child("pendingSync").put(uid, pending)
        }
        audit?.let { recordSettings(uid, it) }
    }

    // Saves the discreet trigger phrase and whether matching should ignore letter case.
    fun updateTrigger(
        uid: String,
        profile: UserProfile,
        pending: Boolean,
        audit: SettingsEvent? = null
    ) {
        vault.update { root ->
            root.getJSONObject("enrollments").getJSONObject(uid)
                .put("triggerHash", profile.triggerHash)
            root.child("profiles").put(uid, profile.toJson())
            root.child("pendingSync").put(uid, pending)
        }
        audit?.let { recordSettings(uid, it) }
    }

    // Reports whether this profile still needs to be uploaded.
    fun pendingSync(uid: String) =
        vault.read().optJSONObject("pendingSync")?.optBoolean(uid) ?: false

    // Returns the chats this account archived on this phone.
    fun archivedConversationIds(uid: String): Set<String> {
        val rows = vault.read().optJSONObject("archivedConversations")?.optJSONArray(uid)
            ?: return emptySet()
        return (0 until rows.length()).mapNotNull { rows.optString(it).takeIf(String::isNotBlank) }
            .toSet()
    }

    // Adds or removes one chat from the phone's archived list.
    fun setConversationArchived(uid: String, conversationId: String, archived: Boolean) {
        require(conversationId.isNotBlank() && conversationId.length <= 160)
        vault.update { root ->
            val all = root.child("archivedConversations")
            val current = all.optJSONArray(uid) ?: JSONArray()
            val values = (0 until current.length()).mapNotNull {
                current.optString(it).takeIf(String::isNotBlank)
            }.toMutableSet()
            if (archived) values += conversationId else values -= conversationId
            all.put(uid, JSONArray(values.sorted()))
        }
    }

    // Returns completed chats this account chose to show in its regular chat list on this phone.
    fun restoredClosedConversationIds(uid: String): Set<String> {
        val rows = vault.read().optJSONObject("restoredClosedConversations")?.optJSONArray(uid)
            ?: return emptySet()
        return (0 until rows.length()).mapNotNull { rows.optString(it).takeIf(String::isNotBlank) }
            .toSet()
    }

    // Changes only where a completed chat is displayed; it never reopens its completed ticket.
    fun setClosedConversationRestored(uid: String, conversationId: String, restored: Boolean) {
        require(conversationId.isNotBlank() && conversationId.length <= 160)
        vault.update { root ->
            val all = root.child("restoredClosedConversations")
            val current = all.optJSONArray(uid) ?: JSONArray()
            val values = (0 until current.length()).mapNotNull {
                current.optString(it).takeIf(String::isNotBlank)
            }.toMutableSet()
            if (restored) values += conversationId else values -= conversationId
            all.put(uid, JSONArray(values.sorted()))
        }
    }

    // Loads the private contact notes needed by app security.
    fun privateContactNotes(uid: String): Map<String, PrivateContactNote> {
        val rows = vault.read().optJSONObject("privateContactNotes")?.optJSONObject(uid)
            ?: return emptyMap()
        return rows.keys().asSequence().mapNotNull { targetUid ->
            val row = rows.optJSONObject(targetUid) ?: return@mapNotNull null
            val body = row.optString("body").takeIf(String::isNotBlank) ?: return@mapNotNull null
            targetUid to PrivateContactNote(targetUid, row.optString("username"), body)
        }.toMap()
    }

    // Saves the private contact note so app security can load it again.
    fun appendPrivateContactNote(uid: String, targetUid: String, username: String, text: String) {
        val addition = text.trim()
        require(targetUid.isNotBlank() && targetUid != uid && targetUid.length <= 160)
        require(username.isNotBlank() && username.length <= 60)
        require(addition.isNotBlank() && addition.length <= 2_000)
        vault.update { root ->
            val notes = root.child("privateContactNotes").child(uid)
            val previous = notes.optJSONObject(targetUid)?.optString("body").orEmpty()
            val combined = if (previous.isBlank()) addition else "$previous\n$addition"
            require(combined.length <= 20_000) { "This private note is full." }
            notes.put(
                targetUid,
                JSONObject().put("username", username).put("body", combined)
            )
        }
    }

    // Saves when this phone last downloaded the account's history.
    fun markSynced(uid: String) {
        vault.update { it.child("pendingSync").put(uid, false) }
    }

    // Loads the failed private-entry attempts for this account.
    fun attempts(uid: String): List<FailedAttempt> {
        return attemptsFrom(vault.read(), uid)
    }

    // Parses failed PIN attempts from the encrypted file.
    private fun attemptsFrom(root: JSONObject, uid: String): List<FailedAttempt> {
        val entries = root.optJSONObject("attempts")?.optJSONArray(uid) ?: JSONArray()
        return (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            FailedAttempt(entry.getString("id"), entry.getLong("timestamp"))
        }.asReversed()
    }

    // Delegate activity storage so PIN/account state remains isolated from history merging.
    fun recordSettings(uid: String, event: SettingsEvent) = histories.recordSettings(uid, event)
    // Adds one account-setting change to private activity history.
    fun settingsHistory(uid: String) = histories.settingsHistory(uid)
    // Records a discreet trigger change without saving the trigger text.
    fun recordTrigger(uid: String, event: TriggerEvent) = histories.recordTrigger(uid, event)
    // Loads the saved discreet-trigger history.
    fun triggerHistory(uid: String) = histories.triggerHistory(uid)
    // Records when a cover app accepted or rejected a trigger attempt.
    fun recordTriggerAttempt(uid: String, id: String, accepted: Boolean) =
        histories.recordTriggerAttempt(uid, id, accepted)

    // Encrypts and saves one private note or incident entry.
    fun savePrivateEntry(uid: String, entry: PrivateEntry) = histories.savePrivateEntry(uid, entry)
    // Loads private notes and incidents that have not expired.
    fun privateEntries(uid: String) = histories.privateEntries(uid)
    // Permanently removes private entries whose trash timer has ended.
    fun purgeExpiredPrivateEntries(uid: String, now: Long = System.currentTimeMillis()) =
        histories.purgeExpiredPrivateEntries(uid, now)

    // Loads history records that still need cloud sync.
    fun pendingHistory(uid: String) = histories.pendingHistory(uid)
    // Marks uploaded history records as synced on this phone.
    fun markHistorySynced(uid: String, record: HistoryRecord) =
        histories.markHistorySynced(uid, record)

    // Merges downloaded history with this phone's encrypted copy.
    fun mergeHistory(uid: String, records: List<HistoryRecord>) =
        histories.mergeHistory(uid, records)

    // Removes this account's saved enrollment and private phone data.
    fun forgetDevice() {
        vault.update { it.remove("activeUid") }
    }
}

// All signed-in roles: checks security rules.

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

// All signed-in roles: defines PIN and device-lock entry choices.

enum class SafeEntryMethod {
    APP_PIN, DEVICE_LOCK;

    companion object {
        // Older enrollments always used a separate app PIN.
        fun fromStored(value: String): SafeEntryMethod =
            entries.firstOrNull { it.name == value } ?: APP_PIN

        // Recommends an app PIN when another person may know the phone's lock code.
        fun recommended(abuserKnowsDevicePin: Boolean) =
            if (abuserKnowsDevicePin) APP_PIN else DEVICE_LOCK
    }
}

// A saved app PIN is an optional second route even when device lock stays preferred.
internal fun DeviceEnrollment.allowsAppPin(): Boolean = hasAppPin

// All signed-in roles: defines PIN and device-lock settings.

data class DeviceEnrollment(
    val uid: String, val triggerHash: String, val role: AccountRole = AccountRole.USER,
    val entryMethod: SafeEntryMethod = SafeEntryMethod.APP_PIN, val hasAppPin: Boolean = true
)

data class DeviceSession(val enrollment: DeviceEnrollment, val profile: UserProfile)
sealed interface PinResult {
    data object Accepted : PinResult
    data class Rejected(val failures: Int) : PinResult
}
