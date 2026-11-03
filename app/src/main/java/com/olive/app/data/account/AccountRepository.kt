// All account roles: loads and saves account profiles.
package com.olive.app.data

// The UI talks to this one doorway whether accounts are local test accounts or real Firebase accounts.
interface AccountRepository {
    // Account: returns the create.
    suspend fun create(username: String, password: String, role: AccountRole): AccountIdentity
    // Authenticates the account for account.
    suspend fun authenticate(username: String, password: String, role: AccountRole): AccountIdentity
    // Account: returns the profile.
    suspend fun profile(identity: AccountIdentity): UserProfile?
    // Saves profile for account.
    suspend fun saveProfile(identity: AccountIdentity, profile: UserProfile)
    // Account: Saves history record.
    suspend fun saveHistoryRecord(identity: AccountIdentity, record: HistoryRecord)
    // returns the history records for account.
    suspend fun historyRecords(identity: AccountIdentity): List<HistoryRecord>
    // Account: Updates password.
    suspend fun changePassword(
        identity: AccountIdentity,
        currentPassword: String,
        newPassword: String
    )

    // Authenticates the accountd uid for account.
    fun authenticatedUid(): String?
    // Account: Authenticates the accountd username.
    fun authenticatedUsername(): String?
    // Signs out the current account for account.
    fun signOut()
}
