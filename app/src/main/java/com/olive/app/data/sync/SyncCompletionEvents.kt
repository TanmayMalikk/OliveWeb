// Signed-in accounts: lets active screens reload encrypted local results without adding backend listeners.
package com.olive.app.data.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal object SyncCompletionEvents {
    private val mutableEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = mutableEvents.asSharedFlow()
    fun publish(uid: String) { mutableEvents.tryEmit(uid) }
}

