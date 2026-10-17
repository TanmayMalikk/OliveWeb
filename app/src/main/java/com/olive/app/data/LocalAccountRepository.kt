package com.olive.app.data

import com.olive.app.security.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

// Handy for emulator testing: same account flow, but nothing leaves this installation.
class LocalAccountRepository(private val vault: EncryptedVault) : AccountRepository {
    private fun key(username: String, role: AccountRole) = "${role.name}:${InputPolicy.username(username)}"
    override suspend fun create(username: String, password: String, role: AccountRole) = withContext(Dispatchers.IO) {
        require(InputPolicy.validUsername(username)) { "Invalid username" }
        require(InputPolicy.validAccountPassword(password,username)) { "Invalid password" }
        val identity = AccountIdentity(UUID.randomUUID().toString(), InputPolicy.username(username))
        val verifier = SecretVerifier.create(password)
        vault.update { root ->
            val accounts = root.child("accounts")
            check(!accounts.has(key(username,role))) { "Account unavailable" }
            accounts.put(key(username,role), JSONObject().put("uid",identity.uid).put("passwordVerifier",verifier))
        }
        identity
    }
    override suspend fun authenticate(username: String, password: String, role: AccountRole) = withContext(Dispatchers.IO) {
        val normalized = InputPolicy.username(username)
        val accounts=vault.read().optJSONObject("accounts")
        val account = accounts?.optJSONObject(key(username,role)) ?: if(role==AccountRole.USER) accounts?.optJSONObject(normalized) else null
        // Perform the KDF for unknown usernames too, avoiding a cheap username timing oracle.
        val verifier = account?.getString("passwordVerifier") ?: SecretVerifier.create("unavailable-account")
        check(SecretVerifier.matches(password, verifier) && account != null) { "Sign-in failed" }
        AccountIdentity(account.getString("uid"), normalized)
    }
    override suspend fun profile(identity: AccountIdentity) = withContext(Dispatchers.IO) {
        vault.read().optJSONObject("profiles")?.optJSONObject(identity.uid)?.let(UserProfile::fromJson)
    }
    override suspend fun saveProfile(identity: AccountIdentity, profile: UserProfile) = withContext(Dispatchers.IO) {
        vault.update { it.child("profiles").put(identity.uid,profile.toJson()) }
    }
    override suspend fun saveHistoryRecord(identity: AccountIdentity, record: HistoryRecord) = Unit
    override suspend fun historyRecords(identity: AccountIdentity) = emptyList<HistoryRecord>()
    override suspend fun changePassword(identity: AccountIdentity, currentPassword: String, newPassword: String) = withContext(Dispatchers.IO) {
        require(InputPolicy.validAccountPassword(newPassword,identity.username)) { "Invalid password" }
        vault.update { root ->
            val records=root.getJSONObject("accounts")
            val account=AccountRole.entries.asSequence().mapNotNull { records.optJSONObject(key(identity.username,it)) }
                .firstOrNull { it.optString("uid")==identity.uid } ?: records.getJSONObject(identity.username)
            check(account.getString("uid")==identity.uid && SecretVerifier.matches(currentPassword,account.getString("passwordVerifier"))) { "Current password is incorrect" }
            account.put("passwordVerifier",SecretVerifier.create(newPassword))
        }
    }
    override fun authenticatedUid(): String? = null
    override fun authenticatedUsername(): String? = null
    override fun signOut() = Unit
}
