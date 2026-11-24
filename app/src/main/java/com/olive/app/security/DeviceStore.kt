// All signed-in roles: stores device state.
package com.olive.app.security

import android.content.Context
import android.content.SharedPreferences
import com.olive.app.R
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.news.NewsArticle
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
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.FailedAttempt
import com.olive.app.data.account.UserProfile
import com.olive.app.data.account.FieldChange
import com.olive.app.data.account.HistoryRecord
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.SettingsEvent
import com.olive.app.data.account.TriggerEvent
import com.olive.app.data.messaging.PrivateContactNote


// This is the private locker on the phone: PIN verifier, cached profile, and failed-attempt history all stay encrypted.
class DeviceStore(
    private val vault: EncryptedVault,
    activityVault: EncryptedVault,
) {
    private val histories = DeviceActivityStore(activityVault)

    /**
     * Startup needs the enrollment and profile together; decrypt the vault once instead of once per value.
     *
     * @return The resulting [DeviceSession], or `null` if unavailable.
     */
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

    /**
     * Loads the account currently linked to this phone.
     *
     * @return The resulting [DeviceEnrollment], or `null` if unavailable.
     */
    fun current(): DeviceEnrollment? {
        val root = vault.read()
        val uid = root.optString("activeUid")
        val entry = root.optJSONObject("enrollments")?.optJSONObject(uid) ?: return null
        return enrollment(uid, entry)
    }

    /**
     * A package update can leave Firebase signed in while the local active pointer is missing; relink only a saved enrollment.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting [DeviceEnrollment], or `null` if unavailable.
     */
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

    /**
     * Creates a PIN-based device enrollment and saves the protected account key.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @param profile Account profile details.
     * @param pin User-entered PIN string.
     * @return The resulting [DeviceEnrollment] instance.
     */
    fun enroll(identity: AccountIdentity, profile: UserProfile, pin: String): DeviceEnrollment {
        require(InputPolicy.validPin(pin))
        val verifier = SecretVerifier.createPin(pin)
        vault.update { root ->
            root.child("enrollments").put(
                identity.uid, JSONObject()
                    .put("pinVerifier", verifier).put("triggerHash", profile.triggerHash)
                    .put("pinLength", pin.length)
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
        return DeviceEnrollment(identity.uid, profile.triggerHash, profile.role, pinLength = pin.length)
    }

    /**
     * Rebuilds one device enrollment from its saved JSON fields.
     *
     * @param uid Firebase UID of the target user account.
     * @param entry Entry supplied to [enrollment].
     * @return The resulting enrollment.
     */
    private fun enrollment(uid: String, entry: JSONObject) = DeviceEnrollment(
        uid,
        entry.optString("triggerHash"),
        AccountRole.fromStored(entry.optString("role")),
        SafeEntryMethod.fromStored(entry.optString("entryMethod")),
        entry.has("pinVerifier"),
        entry.optInt("pinLength").takeIf { it in 4..6 && entry.has("pinVerifier") },
    )

    /**
     * Returns the short-lived proof that Android device authentication succeeded.
     *
     * @param uid Firebase UID of the target user account.
     * @return JSON object representing device lock grant.
     */
    internal fun deviceLockGrant(uid: String): JSONObject {
        val entry = vault.read().getJSONObject("enrollments").getJSONObject(uid)
        check(SafeEntryMethod.fromStored(entry.optString("entryMethod")) == SafeEntryMethod.DEVICE_LOCK)
        return entry.getJSONObject("deviceLockGrant")
    }

    /**
     * Creates a device-lock enrollment while keeping an optional backup app PIN.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @param profile Account profile details.
     * @param grant Grant supplied to [enrollDeviceLock].
     * @return The resulting [DeviceEnrollment] for enroll device lock.
     */
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

    /**
     * Preserve the other configured method when changing the preference or replacing a PIN.
     *
     * @param expected Expected supplied to [updateSafeEntry].
     * @param method Optional method supplied to [updateSafeEntry].
     * @param pin User-entered PIN string, or null when not provided.
     * @param grant Optional grant supplied to [updateSafeEntry].
     * @return The resulting [DeviceEnrollment] for update safe entry.
     */
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
            val stored = enrollment(expected.uid, entry)
            // Verification may have recorded the length of a legacy PIN since this enrollment was loaded.
            val comparable = if (expected.pinLength == null) stored.copy(pinLength = null) else stored
            check(comparable == expected && expected.role == AccountRole.USER)
            if (pin != null) entry.put("pinVerifier", verifier).put("pinLength", pin.length)
            grant?.let { entry.put("deviceLockGrant", it) }
            val chosen = method ?: expected.entryMethod
            check(if (chosen == SafeEntryMethod.APP_PIN) entry.has("pinVerifier") else entry.has("deviceLockGrant"))
            entry.put("entryMethod", chosen.name).put("failures", 0)
            updated = enrollment(expected.uid, entry)
        }
        return checkNotNull(updated)
    }

    /**
     * Safe contacts, staff, and admins use account login only, so their local session never stores a device PIN.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @param profile Account profile details.
     * @return The resulting [DeviceEnrollment] for enroll without PIN.
     */
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

    /**
     * Deletes the account's backup PIN while leaving its device-lock enrollment unchanged.
     *
     * @param uid Firebase UID of the target user account.
     */
    fun removePin(uid: String) {
        vault.update { root ->
            root.optJSONObject("enrollments")?.optJSONObject(uid)?.apply {
                remove("pinVerifier")
                remove("pinLength")
                remove("lockedUntil")
                remove("pinLockouts")
                put("failures", 0)
            }
        }
    }

    /**
     * Verify only the active User enrollment and count failures here; SessionAccess handles routing after the result.
     *
     * @param uid Firebase UID of the target user account.
     * @param pin User-entered PIN string.
     * @param now Current epoch timestamp in milliseconds.
     * @return The resulting [PinResult] instance.
     */
    fun verify(uid: String, pin: String, now: Long = System.currentTimeMillis()): PinResult {
        require(InputPolicy.validPin(pin))
        var result: PinResult = PinResult.Rejected(0)
        vault.update { root ->
            require(root.optString("activeUid") == uid) { "PIN account is no longer active" }
            val saved = root.getJSONObject("enrollments").getJSONObject(uid)
            require(AccountRole.fromStored(saved.optString("role")) == AccountRole.USER) { "This account does not use a PIN" }
            check(enrollment(uid, saved).allowsAppPin())
            if (saved.optLong("lockedUntil") > now) {
                result = PinResult.Rejected(5)
                return@update
            }
            val verifier = saved.getString("pinVerifier")
            val accepted = SecretVerifier.matches(pin, verifier)
            val next = if (accepted) GateState() else GateState(saved.optInt("failures")).failed()
            if (accepted && SecretVerifier.pinNeedsUpgrade(verifier)) saved.put(
                "pinVerifier",
                SecretVerifier.createPin(pin)
            )
            saved.put("failures", if (next.failures >= 5) 0 else next.failures)
            if (accepted) {
                saved.put("pinLength", pin.length)
                saved.remove("lockedUntil")
                saved.remove("pinLockouts")
            } else if (next.failures >= 5) {
                val previousLockouts = saved.optInt("pinLockouts").coerceIn(0, 5)
                saved.put("lockedUntil", now + (30_000L shl previousLockouts).coerceAtMost(900_000L))
                saved.put("pinLockouts", (previousLockouts + 1).coerceAtMost(5))
            }
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

    /**
     * Reads the private profile saved for this account on the phone.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting [UserProfile] instance.
     */
    fun profile(uid: String): UserProfile =
        UserProfile.fromJson(vault.read().getJSONObject("profiles").getJSONObject(uid))

    /**
     * Loads the profile, failed PIN attempts, and pending-sync state together.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting [Triple] for unlock snapshot.
     */
    fun unlockSnapshot(uid: String): Triple<UserProfile, List<FailedAttempt>, Boolean> {
        val root = vault.read()
        val profile = UserProfile.fromJson(root.getJSONObject("profiles").getJSONObject(uid))
        val attempts = attemptsFrom(root, uid)
        val pending = root.optJSONObject("pendingSync")?.optBoolean(uid) ?: false
        return Triple(profile, attempts, pending)
    }

    /**
     * Replaces the phone's saved copy of an account profile.
     *
     * @param uid Firebase UID of the target user account.
     * @param profile Account profile details.
     * @param pending Whether to apply pending.
     * @param audit Optional audit supplied to [cacheProfile].
     */
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

    /**
     * Saves the discreet trigger phrase and whether matching should ignore letter case.
     *
     * @param uid Firebase UID of the target user account.
     * @param profile Account profile details.
     * @param pending Whether to apply pending.
     * @param audit Optional audit supplied to [updateTrigger].
     */
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

    /**
     * Reports whether this profile still needs to be uploaded.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting pending sync.
     */
    fun pendingSync(uid: String) =
        vault.read().optJSONObject("pendingSync")?.optBoolean(uid) ?: false

    /**
     * Returns the chats this account archived on this phone.
     *
     * @param uid Firebase UID of the target user account.
     * @return List of resulting archived conversation IDs items.
     */
    fun archivedConversationIds(uid: String): Set<String> {
        val rows = vault.read().optJSONObject("archivedConversations")?.optJSONArray(uid)
            ?: return emptySet()
        return (0 until rows.length()).mapNotNull { rows.optString(it).takeIf(String::isNotBlank) }
            .toSet()
    }

    /**
     * Adds or removes one chat from the phone's archived list.
     *
     * @param uid Firebase UID of the target user account.
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param archived Whether to apply archived.
     */
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

    /**
     * Returns completed chats this account chose to show in its regular chat list on this phone.
     *
     * @param uid Firebase UID of the target user account.
     * @return List of resulting restored closed conversation IDs items.
     */
    fun restoredClosedConversationIds(uid: String): Set<String> {
        val rows = vault.read().optJSONObject("restoredClosedConversations")?.optJSONArray(uid)
            ?: return emptySet()
        return (0 until rows.length()).mapNotNull { rows.optString(it).takeIf(String::isNotBlank) }
            .toSet()
    }

    /**
     * Retains conversation preferences.
     *
     * @param uid Firebase UID of the target user account.
     * @param visibleIds Collection of visible IDs values to process.
     */
    internal fun retainConversationPreferences(uid: String, visibleIds: Set<String>) {
        vault.update { root ->
            for (key in listOf("archivedConversations", "restoredClosedConversations")) {
                val accounts = root.optJSONObject(key) ?: continue
                val entries = accounts.optJSONArray(uid) ?: continue
                val retained = (0 until entries.length()).map(entries::optString).filter { it in visibleIds }
                if (retained.size != entries.length()) accounts.put(uid, JSONArray(retained))
            }
        }
    }

    /**
     * Changes only where a completed chat is displayed; it never reopens its completed ticket.
     *
     * @param uid Firebase UID of the target user account.
     * @param conversationId Unique identifier of the conversation or support ticket.
     * @param restored Whether to apply restored.
     */
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

    /**
     * Loads the private contact notes needed by app security.
     *
     * @param uid Firebase UID of the target user account.
     * @return Map containing the resulting private contact notes entries.
     */
    fun privateContactNotes(uid: String): Map<String, PrivateContactNote> {
        val rows = vault.read().optJSONObject("privateContactNotes")?.optJSONObject(uid)
            ?: return emptyMap()
        return rows.keys().asSequence().mapNotNull { targetUid ->
            val row = rows.optJSONObject(targetUid) ?: return@mapNotNull null
            val body = row.optString("body").takeIf(String::isNotBlank) ?: return@mapNotNull null
            targetUid to PrivateContactNote(targetUid, row.optString("username"), body)
        }.toMap()
    }

    /**
     * Saves the private contact note so app security can load it again.
     *
     * @param uid Firebase UID of the target user account.
     * @param targetUid Firebase UID of the target account.
     * @param username Account username.
     * @param text Text string to display, parse, or send.
     */
    fun savePrivateContactNote(uid: String, targetUid: String, username: String, text: String) {
        val body = text.trim()
        require(targetUid.isNotBlank() && targetUid != uid && targetUid.length <= 160)
        require(username.isNotBlank() && username.length <= 60)
        require(body.length <= 20_000)
        vault.update { root ->
            val notes = root.child("privateContactNotes").child(uid)
            if (body.isBlank()) notes.remove(targetUid)
            else notes.put(targetUid, JSONObject().put("username", username).put("body", body))
        }
    }

    /**
     * Saves when this phone last downloaded the account's history.
     *
     * @param uid Firebase UID of the target user account.
     */
    fun markSynced(uid: String) {
        vault.update { it.child("pendingSync").put(uid, false) }
    }

    /**
     * Loads the failed private-entry attempts for this account.
     *
     * @param uid Firebase UID of the target user account.
     * @return List of resulting attempts items.
     */
    fun attempts(uid: String): List<FailedAttempt> {
        return attemptsFrom(vault.read(), uid)
    }

    /**
     * Parses failed PIN attempts from the encrypted file.
     *
     * @param root Root JSON object or directory being read or updated.
     * @param uid Firebase UID of the target user account.
     * @return List of resulting attempts from items.
     */
    private fun attemptsFrom(root: JSONObject, uid: String): List<FailedAttempt> {
        val entries = root.optJSONObject("attempts")?.optJSONArray(uid) ?: JSONArray()
        return (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            FailedAttempt(entry.getString("id"), entry.getLong("timestamp"))
        }.asReversed()
    }

    /**
     * Delegate activity storage so PIN/account state remains isolated from history merging.
     *
     * @param uid Firebase UID of the target user account.
     * @param event Sensor, UI, or lifecycle event being processed.
     */
    fun recordSettings(uid: String, event: SettingsEvent) = histories.recordSettings(uid, event)
    /**
     * Adds one account-setting change to private activity history.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting settings history.
     */
    fun settingsHistory(uid: String) = histories.settingsHistory(uid)
    /**
     * Records a discreet trigger change without saving the trigger text.
     *
     * @param uid Firebase UID of the target user account.
     * @param event Sensor, UI, or lifecycle event being processed.
     */
    fun recordTrigger(uid: String, event: TriggerEvent) = histories.recordTrigger(uid, event)
    /**
     * Loads the saved discreet-trigger history.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting trigger history.
     */
    fun triggerHistory(uid: String) = histories.triggerHistory(uid)
    /**
     * Records when a cover app accepted or rejected a trigger attempt.
     *
     * @param uid Firebase UID of the target user account.
     * @param id ID supplied to [recordTriggerAttempt].
     * @param accepted Whether to apply accepted.
     */
    fun recordTriggerAttempt(uid: String, id: String, accepted: Boolean) =
        histories.recordTriggerAttempt(uid, id, accepted)

    /**
     * Encrypts and saves one private note or incident entry.
     *
     * @param uid Firebase UID of the target user account.
     * @param entry Entry supplied to [savePrivateEntry].
     */
    fun savePrivateEntry(uid: String, entry: PrivateEntry) = histories.savePrivateEntry(uid, entry)
    /**
     * Loads private notes and incidents that have not expired.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting private entries.
     */
    fun privateEntries(uid: String) = histories.privateEntries(uid)
    /**
     * Permanently removes private entries whose trash timer has ended.
     *
     * @param uid Firebase UID of the target user account.
     * @param now Current epoch timestamp in milliseconds.
     * @return The resulting purge expired private entries.
     */
    fun purgeExpiredPrivateEntries(uid: String, now: Long = System.currentTimeMillis()) =
        histories.purgeExpiredPrivateEntries(uid, now)

    /**
     * Loads history records that still need cloud sync.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting pending history.
     */
    fun pendingHistory(uid: String) = histories.pendingHistory(uid)
    /**
     * Marks uploaded history records as synced on this phone.
     *
     * @param uid Firebase UID of the target user account.
     * @param record Record supplied to [markHistorySynced].
     */
    fun markHistorySynced(uid: String, record: HistoryRecord) =
        histories.markHistorySynced(uid, record)

    /**
     * Merges downloaded history with this phone's encrypted copy.
     *
     * @param uid Firebase UID of the target user account.
     * @param records Collection of records values to process.
     */
    fun mergeHistory(uid: String, records: List<HistoryRecord>) =
        histories.mergeHistory(uid, records)

    /**
     * Removes this account's saved enrollment and private phone data.
     *
     * @param expectedUid Optional expected user ID supplied to [forgetDevice].
     */
    fun forgetDevice(expectedUid: String? = null) {
        vault.update {
            if (expectedUid == null || it.optString("activeUid") == expectedUid) it.remove("activeUid")
        }
    }
}

// All signed-in roles: checks security rules.

// Passwords and PINs get checked through a slow salted verifier, never saved as readable text.
object SecretVerifier {
    private const val PASSWORD_ITERATIONS = 600_000
    private const val PIN_ITERATIONS = 120_000
    /**
     * Creates a salted PIN verifier without storing the PIN itself.
     *
     * @param secret Secret supplied to [create].
     * @return The resulting create string.
     */
    fun create(secret: String): String = encode(secret, PASSWORD_ITERATIONS)

    /**
     * The PIN stays in the Keystore-encrypted phone vault; fewer rounds make local unlock quick.
     *
     * @param pin User-entered PIN string.
     * @return The resulting create PIN string.
     */
    fun createPin(pin: String): String = encode(pin, PIN_ITERATIONS)
    /**
     * Creates trigger.
     *
     * @param normalized Normalized supplied to [createTrigger].
     * @return The resulting create trigger string.
     */
    fun createTrigger(normalized: String): String = encode(normalized, PASSWORD_ITERATIONS)
    /**
     * Detects older PIN records that need stronger hashing after a valid sign-in.
     *
     * @param encoded Encoded supplied to [pinNeedsUpgrade].
     * @return The resulting PIN needs upgrade.
     */
    fun pinNeedsUpgrade(encoded: String) =
        encoded.substringBefore(':').toIntOrNull()?.let { it > PIN_ITERATIONS } ?: false

    /**
     * Formats a PIN or trigger verifier for encrypted storage.
     *
     * @param secret Secret supplied to [encode].
     * @param iterations Iterations supplied to [encode].
     * @return The resulting encode string.
     */
    private fun encode(secret: String, iterations: Int): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "$iterations:${Base64.getEncoder().encodeToString(salt)}:${
            Base64.getEncoder().encodeToString(derive(secret, salt, iterations))
        }"
    }

    /**
     * Checks entered text against a saved verifier in constant time.
     *
     * @param secret Secret supplied to [matches].
     * @param encoded Encoded supplied to [matches].
     * @return `true` if matches, or `false` otherwise.
     */
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

    /**
     * Creates the derive that protects app security.
     *
     * @param secret Secret supplied to [derive].
     * @param salt Raw salt byte array.
     * @param iterations Iterations supplied to [derive].
     * @return The resulting [ByteArray] instance.
     */
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
    /**
     * Trims and lowercases a username before the app checks or stores it.
     *
     * @param value Input value to store, parse, or format.
     * @return The trimmed, lowercase username.
     */
    fun username(value: String) = value.trim().lowercase(Locale.ROOT)
    /**
     * Makes sure the username contains at least one letter.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting username has letter.
     */
    fun usernameHasLetter(value: String) = value.any { it in 'a'..'z' || it in 'A'..'Z' }
    /**
     * Accepts only a new username with 8–24 letters, numbers, or underscores.
     *
     * @param value Input value to store, parse, or format.
     * @return True when the normalized username has 8–24 allowed characters and includes a letter.
     */
    fun validUsername(value: String) =
        username(value).matches(Regex("[a-z0-9_]{8,24}")) && usernameHasLetter(value)

    /**
     * Existing accounts created under the former four-character minimum remain able to sign in.
     *
     * @param value Input value to store, parse, or format.
     * @return True when the normalized username has 4–24 allowed characters.
     */
    fun validExistingUsername(value: String) = username(value).matches(Regex("[a-z0-9_]{4,24}"))
    private val emailRegex = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")

    /**
     * Normalizes an optional contact email address before validation or storage.
     *
     * @param value The email address to trim and lowercase.
     * @return The trimmed, lowercase email address.
     */
    fun normalizedEmail(value: String) = value.trim().lowercase(Locale.ROOT)

    /**
     * Accepts a real contact email while rejecting empty strings and internal aliases.
     *
     * @param value The contact email address to validate.
     * @return True when the normalized value is a valid contact email and is not an internal alias.
     */
    fun validEmail(value: String): Boolean {
        val normalized = normalizedEmail(value)
        return normalized.length in 1..254 &&
            emailRegex.matches(normalized) &&
            !normalized.endsWith(".olive.invalid")
    }
    /**
     * Keeps passwords between 8 and 24 characters.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting password has valid length.
     */
    fun passwordHasValidLength(value: String) = value.length in 8..24
    /**
     * Makes sure the password contains at least one letter.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting password has letter.
     */
    fun passwordHasLetter(value: String) = value.any(Char::isLetter)
    /**
     * Makes sure the password contains at least one number.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting password has number.
     */
    fun passwordHasNumber(value: String) = value.any(Char::isDigit)
    /**
     * Makes sure the password contains a symbol that is not a space.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting password has special character.
     */
    fun passwordHasSpecialCharacter(value: String) =
        value.any { !it.isLetterOrDigit() && !it.isWhitespace() }

    /**
     * Accepts a password only when every length and character rule passes.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting valid password.
     */
    fun validPassword(value: String) = passwordHasValidLength(value) && passwordHasLetter(value) &&
            passwordHasNumber(value) && passwordHasSpecialCharacter(value)

    /**
     * Stops a password from containing the account username.
     *
     * @param value Input value to store, parse, or format.
     * @param accountUsername Account username supplied to [passwordExcludesUsername].
     * @return `true` if password excludes username, or `false` otherwise.
     */
    fun passwordExcludesUsername(value: String, accountUsername: String): Boolean =
        username(accountUsername).let { it.isNotEmpty() && !value.contains(it, ignoreCase = true) }

    /**
     * Applies the password rules and also rejects the account username inside it.
     *
     * @param value Input value to store, parse, or format.
     * @param accountUsername Account username supplied to [validAccountPassword].
     * @return The resulting valid account password.
     */
    fun validAccountPassword(value: String, accountUsername: String) =
        validPassword(value) && passwordExcludesUsername(value, accountUsername)

    /**
     * Accepts only a 4–6 digit private-entry PIN.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting valid PIN.
     */
    fun validPin(value: String) = value.matches(Regex("[0-9]{4,6}"))
    /**
     * Checks whether text is an acceptable length for a discreet trigger phrase.
     *
     * @param value The proposed trigger phrase before trimming.
     * @return True when the trimmed trigger text is between 3 and 64 characters long.
     */
    fun validTrigger(value: String) = value.trim().length in 3..64
    /**
     * Creates the trigger hash that protects app security.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting trigger hash string.
     */
    fun triggerHash(value: String): String = SecretVerifier.createTrigger(normalizeTrigger(value))

    /**
     * Computes matches trigger.
     *
     * @param value Input value to store, parse, or format.
     * @param stored Stored supplied to [matchesTrigger].
     * @return `true` if matches trigger, or `false` otherwise.
     */
    fun matchesTrigger(value: String, stored: String): Boolean {
        if (!validTrigger(value) || stored.isBlank()) return false
        return if (':' in stored) SecretVerifier.matches(normalizeTrigger(value), stored)
        else unsaltedTriggerHash(value) == stored || legacyTriggerHash(value) == stored
    }

    /**
     * Normalizes trigger.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting normalize trigger.
     */
    private fun normalizeTrigger(value: String) = value.lowercase(Locale.ROOT).filterNot(Char::isWhitespace)

    /**
     * Computes unsalted trigger hash.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting unsalted trigger hash string.
     */
    private fun unsaltedTriggerHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(
            normalizeTrigger(value).toByteArray(Charsets.UTF_8)
        )
        .joinToString("") { "%02x".format(it) }

    /**
     * Creates the legacy trigger hash that protects app security.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting legacy trigger hash string.
     */
    fun legacyTriggerHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.trim().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /**
     * Accepts real birth dates from today back to 130 years ago.
     *
     * @param value Input value to store, parse, or format.
     * @return `true` if valid date of birth, or `false` otherwise.
     */
    fun validDob(value: String): Boolean = runCatching {
        val date = LocalDate.parse(value)
        date <= LocalDate.now() && date >= LocalDate.now().minusYears(130)
    }.getOrDefault(false)
}

// Five failures trigger the cooldown in DeviceStore and close the private screen.
data class GateState(val failures: Int = 0) {
    /**
     * Returns true when the failed-PIN limit has locked private entry.
     *
     * @return The resulting [GateState] instance.
     */
    fun failed(): GateState =
        GateState(if (failures in 0..3) failures + 1 else if (failures == 4) 5 else 1)
}

object IdlePolicy {
    const val DEFAULT_TIMEOUT_SECONDS = 60
    const val MAX_TIMEOUT_SECONDS = 420
    val timeoutOptions = (1..7).map { it * 60 }

    /**
     * Normalizes timeout.
     *
     * @param seconds Seconds supplied to [normalizeTimeout].
     * @return The computed normalize timeout numeric value.
     */
    fun normalizeTimeout(seconds: Int): Int =
        timeoutOptions.last { it <= seconds.coerceIn(DEFAULT_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS) }

    /**
     * Returns up to three ascending idle timeout options (in seconds) strictly larger than [currentSeconds]
     * so users can extend their timeout directly from the inactivity warning dialog.
     *
     * @param currentSeconds Currently configured idle timeout duration in seconds.
     * @return Ascending list of available extension durations, or empty when already at [MAX_TIMEOUT_SECONDS].
     */
    fun extendOptions(currentSeconds: Int): List<Int> {
        val normalized = normalizeTimeout(currentSeconds)
        val larger = timeoutOptions.filter { it > normalized }
        if (larger.isEmpty()) return emptyList()
        return listOf(larger.first(), 300, MAX_TIMEOUT_SECONDS)
            .filter { it > normalized && it in timeoutOptions }
            .distinct()
    }

    /**
     * Computes countdown.
     *
     * @param elapsedMs Duration or timestamp for elapsed, in milliseconds.
     * @param warningDurationMs Duration or timestamp for warning duration, in milliseconds.
     * @param idleDurationMs Duration or timestamp for idle duration, in milliseconds.
     * @return The computed countdown number, or `null` if unavailable.
     */
    fun countdown(elapsedMs: Long, warningDurationMs: Long = 15_000L, idleDurationMs: Long = 60_000L): Int? {
        val lockAt = idleDurationMs.coerceIn(DEFAULT_TIMEOUT_SECONDS * 1_000L, MAX_TIMEOUT_SECONDS * 1_000L)
        val warningAt = lockAt - warningDurationMs.coerceIn(15_000L, lockAt)
        return when {
            elapsedMs < warningAt -> null
            elapsedMs >= lockAt -> 0
            else -> ((lockAt - elapsedMs + 999L) / 1000L).toInt()
        }
    }
}

// All signed-in roles: defines PIN and device-lock entry choices.

enum class SafeEntryMethod {
    APP_PIN, DEVICE_LOCK;

    companion object {
        /**
         * Older enrollments always used a separate app PIN.
         *
         * @param value Input value to store, parse, or format.
         * @return The resulting [SafeEntryMethod] for from stored.
         */
        fun fromStored(value: String): SafeEntryMethod =
            entries.firstOrNull { it.name == value } ?: APP_PIN

        /**
         * Recommends an app PIN when another person may know the phone's lock code.
         *
         * @param abuserKnowsDevicePin Whether to apply abuser knows device PIN.
         * @return The resulting recommended.
         */
        fun recommended(abuserKnowsDevicePin: Boolean) =
            if (abuserKnowsDevicePin) APP_PIN else DEVICE_LOCK
    }
}

/**
 * A saved app PIN is an optional second route even when device lock stays preferred.
 *
 * @receiver The local device enrollment whose app-PIN configuration is checked.
 * @return `true` if allows app PIN, or `false` otherwise.
 */
internal fun DeviceEnrollment.allowsAppPin(): Boolean = hasAppPin

// All signed-in roles: defines PIN and device-lock settings.

data class DeviceEnrollment(
    val uid: String, val triggerHash: String, val role: AccountRole = AccountRole.USER,
    val entryMethod: SafeEntryMethod = SafeEntryMethod.APP_PIN, val hasAppPin: Boolean = true,
    val pinLength: Int? = null,
)

data class DeviceSession(val enrollment: DeviceEnrollment, val profile: UserProfile)
sealed interface PinResult {
    data object Accepted : PinResult
    data class Rejected(val failures: Int) : PinResult
}

// Device-local choices are isolated by account; never inherit another account's setting.
internal object ScreenCapturePreferences {
    /**
     * Stores the screen capture preferences.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @return The resulting [SharedPreferences] instance.
     */
    fun store(context: Context): SharedPreferences =
        context.getSharedPreferences("olive_capture_privacy", Context.MODE_PRIVATE)

    /**
     * Computes enabled.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param uid Firebase UID of the target user account, or null when not provided.
     * @return `true` if enabled, or `false` otherwise.
     */
    fun enabled(context: Context, uid: String?): Boolean =
        if (uid.isNullOrBlank()) context.resources.getBoolean(R.bool.screen_capture_protection_enabled)
        else store(context).getBoolean("blocked_$uid", context.resources.getBoolean(R.bool.screen_capture_protection_enabled))

    /**
     * Sets enabled.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param uid Firebase UID of the target user account.
     * @param enabled Whether this feature or control is currently enabled.
     */
    fun setEnabled(context: Context, uid: String, enabled: Boolean) {
        require(uid.isNotBlank())
        store(context).edit().putBoolean("blocked_$uid", enabled).apply()
    }
}

// Persists account-isolated state for the cover applications.
class CoverStateStore(private val vault: EncryptedVault) {
    /**
     * Restores the saved news feed for the given account.
     *
     * @param uid Firebase UID of the target user account.
     * @return List of resulting restore items.
     */
    fun restore(uid: String): List<NewsArticle> = news(uid)

    /**
     * Reads cached news articles from the encrypted local vault.
     *
     * @param uid Firebase UID of the target user account.
     * @return List of resulting news items.
     */
    fun news(uid: String): List<NewsArticle> {
        val item = vault.read().optJSONObject("accounts")?.optJSONObject(uid) ?: return emptyList()
        val rows = item.optJSONArray("news") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { index ->
            runCatching { NewsArticle.fromJson(rows.getJSONObject(index)) }.getOrNull()
        }
    }

    /**
     * Saves cached news articles into the encrypted local vault.
     *
     * @param uid Firebase UID of the target user account.
     * @param articles Collection of articles values to process.
     */
    fun saveNews(uid: String, articles: List<NewsArticle>) {
        vault.update { root ->
            root.child("accounts").child(uid).put(
                "news",
                JSONArray().also { rows -> articles.forEach { rows.put(it.toJson()) } },
            )
        }
    }
}
