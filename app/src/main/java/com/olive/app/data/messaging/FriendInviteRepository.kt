package com.olive.app.data.messaging

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.olive.app.data.account.AccountIdentity
import kotlinx.coroutines.tasks.await

data class FriendInvite(val code: String, val link: String, val expiresAt: Long)

class FriendInviteRepository(
    private val expectedUid: String? = null,
    private val functions: () -> FirebaseFunctions = { FirebaseFunctions.getInstance("northamerica-northeast1") },
) {

    /**
     * Requests a shareable friend invitation for the signed-in account.
     *
     * @param refresh True to replace the current invitation before it expires or is claimed.
     * @return The invitation code, download link, and expiration timestamp supplied by Firebase.
     */
    suspend fun create(refresh: Boolean = false): FriendInvite {
        val uid = currentUid()
        val data = functions().getHttpsCallable("friendInviteCreate")
            .call(mapOf("refresh" to refresh)).await().data as Map<*, *>
        check(currentUid() == uid) { "Your account changed. Open Invite again." }
        return FriendInvite(data["code"] as String, data["link"] as String, (data["expiresAt"] as Number).toLong())
    }

    /**
     * Registers the signed-in account's messaging identity and claims a friend invitation.
     *
     * @param code The invitation code from the other person. Leading and trailing spaces are ignored.
     * @return The username of the person who created the invitation.
     */
    suspend fun claim(code: String): String {
        val uid = currentUid()
        val user = checkNotNull(FirebaseAuth.getInstance().currentUser)
        val username = checkNotNull(user.email).substringBefore('@')
        MessagingRepository().registerUser(AccountIdentity(uid, username))
        val data = functions().getHttpsCallable("friendInviteClaim")
            .call(mapOf("code" to code.trim())).await().data as Map<*, *>
        check(currentUid() == uid) { "Your account changed. Reopen Messages." }
        return data["username"] as String
    }

    /**
     * Watches the invitation for the account this repository was created for.
     *
     * @param code The invitation code currently shown to the account owner.
     * @param onUnavailable Runs when Firebase reports that the code changed or was claimed.
     * @return A subscription to close when observation ends, or an empty subscription if no account was supplied.
     */
    fun observeUnavailable(code: String, onUnavailable: () -> Unit): ChatSubscription {
        val uid = expectedUid ?: return ChatSubscription {}
        val listener = FirebaseFirestore.getInstance().collection("friendInviteOwners").document(uid)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null &&
                    (snapshot.getString("code") != code || snapshot.getString("claimedBy").orEmpty().isNotBlank())) {
                    onUnavailable()
                }
            }
        return ChatSubscription { listener.remove() }
    }

    /**
     * Checks that Firebase is signed into the account expected by this repository.
     *
     * @return The authenticated account's Firebase UID.
     * @throws IllegalStateException If no account is signed in or the authenticated account differs from the expected one.
     */
    private fun currentUid(): String {
        val uid = checkNotNull(FirebaseAuth.getInstance().currentUser?.uid) { "Sign in first." }
        check(expectedUid == null || uid == expectedUid) { "Sign in again to reconnect this account." }
        return uid
    }
}

/**
 * Requests server authorization before resolving a known participant's messaging keys.
 *
 * @param username The participant's username. The request uses its trimmed, lowercase form.
 */
internal suspend fun authorizeMessagingPeer(username: String) {
    FirebaseFunctions.getInstance("northamerica-northeast1").getHttpsCallable("messagingAuthorizePeer")
        .call(mapOf("username" to username.trim().lowercase())).await()
}
