// Chat participants: refreshes the encrypted conversation index and purges revoked room access.
package com.olive.app.data.sync

import android.content.Context
import com.olive.app.data.ChatCacheStore
import com.olive.app.data.MessagingRepository

internal class ConversationAccessReconciler(
    context: Context,
    private val repository: MessagingRepository = MessagingRepository(),
) : PriorityDomainReconciler {
    private val cache = ChatCacheStore(context.applicationContext)

    override suspend fun reconcile(uid: String) {
        cache.replaceIndex(uid, repository.listConversations(uid), System.currentTimeMillis())
    }
}

