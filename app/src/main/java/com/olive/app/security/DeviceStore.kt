// Contains the device store code for this part of Olive.
package com.olive.app.security

import com.olive.app.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// This is the private locker on the phone: PIN verifier, cached profile, and failed-attempt history all stay encrypted.
class DeviceStore(
    private val vault: EncryptedVault,
    activityVault: EncryptedVault,
    private val phoneSecure: () -> Boolean = { true }
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

    private fun enrollment(uid: String, entry: JSONObject) = DeviceEnrollment(
        uid,
        entry.optString("triggerHash"),
        AccountRole.fromStored(entry.optString("role")),
        SafeEntryMethod.fromStored(entry.optString("entryMethod")),
        entry.has("pinVerifier")
    )

    internal fun deviceLockGrant(uid: String): JSONObject {
        val entry = vault.read().getJSONObject("enrollments").getJSONObject(uid)
        check(SafeEntryMethod.fromStored(entry.optString("entryMethod")) == SafeEntryMethod.DEVICE_LOCK)
        return entry.getJSONObject("deviceLockGrant")
    }

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

    // Removes PIN.
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
            check(enrollment(uid, saved).allowsAppPin(phoneSecure()))
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

    fun profile(uid: String): UserProfile =
        UserProfile.fromJson(vault.read().getJSONObject("profiles").getJSONObject(uid))

    fun unlockSnapshot(uid: String): Triple<UserProfile, List<FailedAttempt>, Boolean> {
        val root = vault.read()
        val profile = UserProfile.fromJson(root.getJSONObject("profiles").getJSONObject(uid))
        val attempts = attemptsFrom(root, uid)
        val pending = root.optJSONObject("pendingSync")?.optBoolean(uid) ?: false
        return Triple(profile, attempts, pending)
    }

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

    // Updates trigger.
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

    fun pendingSync(uid: String) =
        vault.read().optJSONObject("pendingSync")?.optBoolean(uid) ?: false

    fun markSynced(uid: String) {
        vault.update { it.child("pendingSync").put(uid, false) }
    }

    fun attempts(uid: String): List<FailedAttempt> {
        return attemptsFrom(vault.read(), uid)
    }

    private fun attemptsFrom(root: JSONObject, uid: String): List<FailedAttempt> {
        val entries = root.optJSONObject("attempts")?.optJSONArray(uid) ?: JSONArray()
        return (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            FailedAttempt(entry.getString("id"), entry.getLong("timestamp"))
        }.asReversed()
    }

    // Delegate activity storage so PIN/account state remains isolated from history merging.
    fun recordSettings(uid: String, event: SettingsEvent) = histories.recordSettings(uid, event)
    // Sets tings history.
    fun settingsHistory(uid: String) = histories.settingsHistory(uid)
    // Records trigger.
    fun recordTrigger(uid: String, event: TriggerEvent) = histories.recordTrigger(uid, event)
    fun triggerHistory(uid: String) = histories.triggerHistory(uid)
    // Records trigger attempt.
    fun recordTriggerAttempt(uid: String, id: String, accepted: Boolean) =
        histories.recordTriggerAttempt(uid, id, accepted)

    // Records checkout.
    fun recordCheckout(uid: String, event: CheckoutEvent) = histories.recordCheckout(uid, event)
    // Checks out history.
    fun checkoutHistory(uid: String) = histories.checkoutHistory(uid)
    // Saves private entry.
    fun savePrivateEntry(uid: String, entry: PrivateEntry) = histories.savePrivateEntry(uid, entry)
    fun privateEntries(uid: String) = histories.privateEntries(uid)
    fun purgeExpiredPrivateEntries(uid: String, now: Long = System.currentTimeMillis()) =
        histories.purgeExpiredPrivateEntries(uid, now)

    fun pendingHistory(uid: String) = histories.pendingHistory(uid)
    fun markHistorySynced(uid: String, record: HistoryRecord) =
        histories.markHistorySynced(uid, record)

    fun mergeHistory(uid: String, records: List<HistoryRecord>) =
        histories.mergeHistory(uid, records)

    fun forgetDevice() {
        vault.update { it.remove("activeUid") }
    }
}
