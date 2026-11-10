// Signed-in accounts: validates the authoritative Firebase account before other protected data is refreshed.
package com.olive.app.data.sync

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

internal class AccountAccessReconciler(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : PriorityDomainReconciler {
    override suspend fun reconcile(uid: String) {
        val user = auth.currentUser ?: error("Authentication is unavailable")
        check(user.uid == uid) { "Signed-in account changed" }
        user.getIdToken(true).await()
    }
}

