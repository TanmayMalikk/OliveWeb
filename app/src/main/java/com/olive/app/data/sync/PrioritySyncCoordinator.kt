// Signed-in accounts: funnels marker, push, start, and resume events into one unique worker chain.
package com.olive.app.data.sync

import android.content.Context
import com.olive.app.data.ChatSubscription

internal class PrioritySyncCoordinator(
    context: Context,
    private val repository: SyncInvalidationRepository = SyncInvalidationRepository(),
) {
    private val application = context.applicationContext
    private var activeUid: String? = null
    private var subscription: ChatSubscription? = null
    private var lastUrgentRevision = 0L

    fun start(uid: String) {
        if (activeUid == uid && subscription != null) {
            PriorityReconciliationWorker.enqueue(application, uid)
            return
        }
        stop()
        activeUid = uid
        PriorityReconciliationWorker.enqueue(application, uid)
        subscription = repository.observe(
            uid,
            onUpdate = { revisions ->
                if (activeUid == uid) {
                    val urgent = revisions.urgent > lastUrgentRevision
                    lastUrgentRevision = maxOf(lastUrgentRevision, revisions.urgent)
                    PriorityReconciliationWorker.enqueue(
                        application,
                        uid,
                        revision = revisions.revision,
                        expedited = urgent,
                    )
                }
            },
            onError = { if (activeUid == uid) PriorityReconciliationWorker.enqueue(application, uid) },
        )
    }

    fun request(uid: String) {
        if (activeUid == uid) PriorityReconciliationWorker.enqueue(application, uid)
    }

    fun stop() {
        subscription?.close()
        subscription = null
        activeUid = null
        lastUrgentRevision = 0L
    }
}

