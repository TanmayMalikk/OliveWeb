package com.olive.app.security

import com.olive.app.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class DeviceEnrollment(val uid: String, val triggerHash: String)
sealed interface PinResult {
    data object Accepted : PinResult
    data class Rejected(val failures: Int) : PinResult
}

// This is the private locker on the phone: PIN verifier, cached profile, and failed-attempt history all stay encrypted.
class DeviceStore(private val vault: EncryptedVault, private val activityVault: EncryptedVault) {
    fun current(): DeviceEnrollment? {
        val root = vault.read()
        val uid = root.optString("activeUid")
        val entry = root.optJSONObject("enrollments")?.optJSONObject(uid) ?: return null
        return DeviceEnrollment(uid,entry.getString("triggerHash"))
    }
    fun enroll(identity: AccountIdentity, profile: UserProfile, pin: String): DeviceEnrollment {
        require(InputPolicy.validPin(pin))
        val verifier = SecretVerifier.createPin(pin)
        vault.update { root ->
            root.child("enrollments").put(identity.uid,JSONObject()
                .put("pinVerifier",verifier).put("triggerHash",profile.triggerHash)
                .put("failures",0))
            root.child("profiles").put(identity.uid,profile.toJson())
            root.put("activeUid",identity.uid)
        }
        recordSettings(identity.uid,SettingsEvent(area="Onboarding",action="Created profile",
            changes=listOf(
                FieldChange("Full name","Empty",profile.fullName),
                FieldChange("Date of birth","Empty",profile.dob),
                FieldChange("Language","Empty",profile.language),
                FieldChange("City","Empty",profile.address.city),
                FieldChange("Cover app","Empty",profile.coverType.title),
                FieldChange("Trigger word","Not set","Set"),
            )))
        return DeviceEnrollment(identity.uid,profile.triggerHash)
    }
    // PIN attempt counting lives here; change the screen response in SessionViewModel.submitPin too.
    fun verify(uid: String, pin: String, now: Long = System.currentTimeMillis()): PinResult {
        var result: PinResult = PinResult.Rejected(0)
        vault.update { root ->
            val saved=root.getJSONObject("enrollments").getJSONObject(uid)
            val verifier=saved.getString("pinVerifier")
            val accepted=SecretVerifier.matches(pin,verifier)
            val next=if(accepted) GateState() else GateState(saved.optInt("failures")).failed()
            if(accepted && SecretVerifier.pinNeedsUpgrade(verifier)) saved.put("pinVerifier",SecretVerifier.createPin(pin))
            saved.put("failures", if (next.failures >= 5) 0 else next.failures)
            // Ignore and remove cooldowns written by earlier app versions.
            saved.remove("lockedUntil")
            if(!accepted) {
                val logs = root.child("attempts")
                val entries = logs.optJSONArray(uid) ?: JSONArray().also { logs.put(uid,it) }
                entries.put(JSONObject().put("id",UUID.randomUUID().toString()).put("timestamp",now))
            }
            result=if(accepted) PinResult.Accepted else PinResult.Rejected(next.failures)
        }
        return result
    }
    fun changePin(uid: String, currentPin: String, newPin: String): PinResult {
        require(InputPolicy.validPin(newPin))
        val result=verify(uid,currentPin)
        if(result is PinResult.Accepted) vault.update { root ->
            root.getJSONObject("enrollments").getJSONObject(uid).put("pinVerifier",SecretVerifier.createPin(newPin))
        }
        return result
    }
    fun profile(uid: String): UserProfile = UserProfile.fromJson(vault.read().getJSONObject("profiles").getJSONObject(uid))
    fun unlockSnapshot(uid: String): Triple<UserProfile,List<FailedAttempt>,Boolean> {
        val root=vault.read()
        val profile=UserProfile.fromJson(root.getJSONObject("profiles").getJSONObject(uid))
        val attempts=attemptsFrom(root,uid)
        val pending=root.optJSONObject("pendingSync")?.optBoolean(uid) ?: false
        return Triple(profile,attempts,pending)
    }
    fun cacheProfile(uid: String, profile: UserProfile, pending: Boolean = false, audit: SettingsEvent? = null) {
        vault.update { root ->
            root.child("profiles").put(uid,profile.toJson())
            root.child("pendingSync").put(uid,pending)
        }
        audit?.let { recordSettings(uid,it) }
    }
    fun updateTrigger(uid: String, profile: UserProfile, pending: Boolean, audit: SettingsEvent? = null) {
        vault.update { root ->
            root.getJSONObject("enrollments").getJSONObject(uid).put("triggerHash", profile.triggerHash)
            root.child("profiles").put(uid,profile.toJson())
            root.child("pendingSync").put(uid,pending)
        }
        audit?.let { recordSettings(uid,it) }
    }
    fun pendingSync(uid: String) = vault.read().optJSONObject("pendingSync")?.optBoolean(uid) ?: false
    fun markSynced(uid: String) { vault.update { it.child("pendingSync").put(uid,false) } }
    fun attempts(uid: String): List<FailedAttempt> {
        return attemptsFrom(vault.read(),uid)
    }
    private fun attemptsFrom(root: JSONObject, uid: String): List<FailedAttempt> {
        val entries = root.optJSONObject("attempts")?.optJSONArray(uid) ?: JSONArray()
        return (0 until entries.length()).map { entries.getJSONObject(it).let { FailedAttempt(it.getString("id"),it.getLong("timestamp")) } }.asReversed()
    }
    // These histories stay encrypted on this device under the current account ID.
    fun recordSettings(uid: String, event: SettingsEvent) { activityVault.update { append(it,uid,"settingsHistory",event.toJson()) } }
    fun settingsHistory(uid: String): List<SettingsEvent> = rows(uid,"settingsHistory").map(SettingsEvent::fromJson)
    fun recordTrigger(uid: String, event: TriggerEvent) { activityVault.update { append(it,uid,"triggerHistory",event.toJson()) } }
    fun triggerHistory(uid: String): List<TriggerEvent> = rows(uid,"triggerHistory").map(TriggerEvent::fromJson).map { event ->
        // Hide old first-try Accepted rows too; the trigger itself still stays in history.
        if(event.lastResult=="Accepted" && event.pinAttempts==1) event.copy(pinAttempts=0,lastResult="No PIN attempt") else event
    }
    fun recordTriggerAttempt(uid: String, id: String, accepted: Boolean) {
        activityVault.update { root ->
            val events=root.optJSONObject("triggerHistory")?.optJSONArray(uid) ?: return@update
            for(index in 0 until events.length()) {
                val item=events.getJSONObject(index)
                if(item.optString("id")==id) {
                    // A clean first-try unlock is routine; only show Accepted after this trigger already had a wrong PIN.
                    if(accepted && item.optInt("pinAttempts")==0) break
                    item.put("pinAttempts",item.optInt("pinAttempts")+1)
                    item.put("lastResult",if(accepted) "Accepted" else "Incorrect PIN")
                    root.child("historySynced").child(uid).put("triggers/$id",false)
                    break
                }
            }
        }
    }
    fun recordCheckout(uid: String, event: CheckoutEvent) { activityVault.update { append(it,uid,"checkoutHistory",event.toJson()) } }
    fun checkoutHistory(uid: String): List<CheckoutEvent> = rows(uid,"checkoutHistory").map(CheckoutEvent::fromJson)
    fun pendingHistory(uid: String): List<HistoryRecord> {
        val root=activityVault.read()
        val synced=root.optJSONObject("historySynced")?.optJSONObject(uid)
        return listOf("settings" to "settingsHistory","triggers" to "triggerHistory","checkouts" to "checkoutHistory")
            .flatMap { (category,section) ->
                val rows=root.optJSONObject(section)?.optJSONArray(uid) ?: JSONArray()
                (0 until rows.length()).map { rows.getJSONObject(it) }.filter { synced?.optBoolean("$category/${it.getString("id")}") != true }
                    .map { HistoryRecord(category,it.getString("id"),it.toString()) }
            }
    }
    fun markHistorySynced(uid: String, record: HistoryRecord) {
        activityVault.update { root ->
            val section=when(record.category) { "settings" -> "settingsHistory"; "triggers" -> "triggerHistory"; else -> "checkoutHistory" }
            val rows=root.optJSONObject(section)?.optJSONArray(uid) ?: return@update
            val current=(0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("id")==record.id }
            if(current?.toString()==record.json)
                root.child("historySynced").child(uid).put("${record.category}/${record.id}",true)
        }
    }
    fun mergeHistory(uid: String, records: List<HistoryRecord>) {
        activityVault.update { root ->
            val sections=mapOf("settings" to "settingsHistory","triggers" to "triggerHistory","checkouts" to "checkoutHistory")
            records.forEach { record ->
                val section=sections[record.category] ?: return@forEach
                val byAccount=root.child(section)
                val rows=byAccount.optJSONArray(uid) ?: JSONArray().also { byAccount.put(uid,it) }
                val incoming=JSONObject(record.json)
                val existing=(0 until rows.length()).firstOrNull { rows.getJSONObject(it).optString("id")==record.id }
                val localIsNewer=record.category=="triggers" && existing!=null &&
                    rows.getJSONObject(existing).optInt("pinAttempts")>incoming.optInt("pinAttempts")
                if(existing==null) rows.put(incoming)
                else if(record.category=="triggers" && !localIsNewer) rows.put(existing,incoming)
                if(!localIsNewer) root.child("historySynced").child(uid).put("${record.category}/${record.id}",true)
            }
        }
    }
    private fun append(root: JSONObject, uid: String, section: String, value: JSONObject) {
        val byAccount=root.child(section)
        val entries=byAccount.optJSONArray(uid) ?: JSONArray().also { byAccount.put(uid,it) }
        entries.put(value)
        val category=when(section) { "settingsHistory" -> "settings"; "triggerHistory" -> "triggers"; else -> "checkouts" }
        root.child("historySynced").child(uid).put("$category/${value.getString("id")}",false)
    }
    private fun rows(uid: String, section: String): List<JSONObject> {
        val entries=activityVault.read().optJSONObject(section)?.optJSONArray(uid) ?: JSONArray()
        return (0 until entries.length()).map { entries.getJSONObject(it) }.asReversed()
    }
    fun forgetDevice() { vault.update { it.remove("activeUid") } }
}
