// Refreshes cached access for the domains managed by priority sync.
package com.olive.app.data.sync

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.ChatCacheStore
import com.olive.app.data.safety.DocumentIndexStore
import com.olive.app.data.safety.DocumentListSource
import com.olive.app.data.messaging.MessagingRepository
import com.olive.app.data.safety.SafetyAccessIndexStore
import com.olive.app.data.safety.SafetyDocumentRepository
import com.olive.app.data.safety.SafetyPlanRepository
import com.olive.app.data.safety.SafetyPlanStore
import com.olive.app.data.media.MediaRepository
import com.olive.app.data.media.OfflineMediaCache
import kotlinx.coroutines.tasks.await

// Signed-in accounts: validates the authoritative Firebase account before other protected data is refreshed.
internal class AccountAccessReconciler(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : PriorityDomainReconciler {
    override suspend fun reconcile(uid: String) {
        val user = auth.currentUser ?: error("Authentication is unavailable")
        check(user.uid == uid) { "Signed-in account changed" }
        user.getIdToken(true).await()
    }
}

// Chat participants: refreshes the encrypted conversation index and purges revoked room access.
internal class ConversationAccessReconciler(
    context: Context,
    private val repository: MessagingRepository = MessagingRepository(),
) : PriorityDomainReconciler {
    private val cache = ChatCacheStore(context.applicationContext)

    override suspend fun reconcile(uid: String) {
        val conversations = repository.listConversations(uid)
        check(FirebaseAuth.getInstance().currentUser?.uid == uid) { "Signed-in account changed" }
        cache.replaceIndex(uid, conversations, System.currentTimeMillis())
    }
}

// Authorized media viewers: refreshes metadata and purges revoked local ciphertext.
internal class DocumentAccessReconciler(
    context: Context,
) : PriorityDomainReconciler {
    private val application = context.applicationContext

    override suspend fun reconcile(uid: String) {
        val snapshot = SafetyDocumentRepository(media = MediaRepository()).listFor(uid)
        check(snapshot.source == DocumentListSource.SERVER)
        check(FirebaseAuth.getInstance().currentUser?.uid == uid) { "Signed-in account changed" }
        val syncedAt = snapshot.lastSyncedAt ?: System.currentTimeMillis()
        DocumentIndexStore(application).replace(uid, snapshot.documents, syncedAt)
        OfflineMediaCache(application).reconcile(uid, snapshot.documents)
    }
}

// Safety-plan roles: refreshes authorized plans into the existing encrypted plan store.
internal class SafetyPlanReconciler(
    context: Context,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val repository: SafetyPlanRepository = SafetyPlanRepository(),
) : PriorityDomainReconciler {
    private val store = SafetyPlanStore(context.applicationContext, false)
    private val accessIndex = SafetyAccessIndexStore(context.applicationContext)

    override suspend fun reconcile(uid: String) {
        val user = auth.currentUser ?: error("Authentication is unavailable")
        check(user.uid == uid) { "Signed-in account changed" }
        val role = AccountRole.fromStored(user.getIdToken(false).await().claims["role"] as? String ?: "")
        val owners = when (role) {
            AccountRole.USER -> listOf(uid)
            AccountRole.SAFE_CONTACT -> repository.sharedWithMe().also {
                accessIndex.replaceShared(uid, it)
            }.map { it.ownerUid }
            AccountRole.STAFF, AccountRole.ADMIN -> repository.consultations().also { result ->
                accessIndex.replaceConsultations(uid, result.first, result.second)
            }.second.map { it.ownerUid }
        }.distinct()
        check(auth.currentUser?.uid == uid) { "Signed-in account changed" }
        store.all(uid).filterNot { it.ownerUid in owners }.forEach { store.remove(uid, it.ownerUid) }
        for (owner in owners) {
            val remote = repository.get(owner) ?: continue
            check(auth.currentUser?.uid == uid) { "Signed-in account changed" }
            store.mergeRemote(uid, remote)
        }
    }
}
