package com.olive.app.data.messaging

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.auth.FirebaseAuth
import com.olive.app.data.account.AccountIdentity
import kotlinx.coroutines.tasks.await

data class FriendInvite(val code: String, val link: String, val expiresAt: Long)

class FriendInviteRepository(private val expectedUid: String? = null) {
    private val functions get() = FirebaseFunctions.getInstance("northamerica-northeast1")

    suspend fun create(refresh: Boolean = false): FriendInvite {
        val uid = currentUid()
        val data = functions.getHttpsCallable("friendInviteCreate")
            .call(mapOf("refresh" to refresh)).await().data as Map<*, *>
        check(currentUid() == uid) { "Your account changed. Open Invite again." }
        return FriendInvite(data["code"] as String, data["link"] as String, (data["expiresAt"] as Number).toLong())
    }

    suspend fun claim(code: String): String {
        val uid = currentUid()
        val user = checkNotNull(FirebaseAuth.getInstance().currentUser)
        val username = checkNotNull(user.email).substringBefore('@')
        MessagingRepository().registerUser(AccountIdentity(uid, username))
        val data = functions.getHttpsCallable("friendInviteClaim")
            .call(mapOf("code" to code.trim())).await().data as Map<*, *>
        check(currentUid() == uid) { "Your account changed. Reopen Messages." }
        return data["username"] as String
    }

    private fun currentUid(): String {
        val uid = checkNotNull(FirebaseAuth.getInstance().currentUser?.uid) { "Sign in first." }
        check(expectedUid == null || uid == expectedUid) { "Sign in again to reconnect this account." }
        return uid
    }
}

// Internal key sharing still resolves known participants, after server authorization.
internal suspend fun authorizeMessagingPeer(username: String) {
    FirebaseFunctions.getInstance("northamerica-northeast1").getHttpsCallable("messagingAuthorizePeer")
        .call(mapOf("username" to username.trim().lowercase())).await()
}
