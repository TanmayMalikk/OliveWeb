package com.olive.app.data

import com.olive.app.security.*

// The UI talks to this one doorway whether accounts are local test accounts or real Firebase accounts.
interface AccountRepository {
    suspend fun create(username: String, password: String, role: AccountRole): AccountIdentity
    suspend fun authenticate(username: String, password: String, role: AccountRole): AccountIdentity
    suspend fun profile(identity: AccountIdentity): UserProfile?
    suspend fun saveProfile(identity: AccountIdentity, profile: UserProfile)
    suspend fun saveHistoryRecord(identity: AccountIdentity, record: HistoryRecord)
    suspend fun historyRecords(identity: AccountIdentity): List<HistoryRecord>
    suspend fun changePassword(identity: AccountIdentity, currentPassword: String, newPassword: String)
    fun authenticatedUid(): String?
    fun authenticatedUsername(): String?
    fun signOut()
}
