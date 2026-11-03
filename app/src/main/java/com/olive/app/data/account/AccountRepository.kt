// All account roles: loads and saves account profiles.
package com.olive.app.data

// The UI talks to this one doorway whether accounts are local test accounts or real Firebase accounts.
interface AccountRepository {
    // Creates a new account with the selected role.
    suspend fun create(username: String, password: String, role: AccountRole): AccountIdentity
    // Signs in with the supplied username and password.
    suspend fun authenticate(username: String, password: String, role: AccountRole): AccountIdentity
    // Loads the signed-in account profile from this repository.
    suspend fun profile(identity: AccountIdentity): UserProfile?
    // Saves the account profile in the selected account repository.
    suspend fun saveProfile(identity: AccountIdentity, profile: UserProfile)
    // Adds one account activity record to storage.
    suspend fun saveHistoryRecord(identity: AccountIdentity, record: HistoryRecord)
    // Loads the private activity history saved for this account.
    suspend fun historyRecords(identity: AccountIdentity): List<HistoryRecord>
    // Re-encrypts this account after its password changes.
    suspend fun changePassword(
        identity: AccountIdentity,
        currentPassword: String,
        newPassword: String
    )

    // Confirms that the supplied password belongs to this account ID.
    fun authenticatedUid(): String?
    // Returns the normalized username of the signed-in account.
    fun authenticatedUsername(): String?
    // Signs out the current account and clears its repository session.
    fun signOut()
}
