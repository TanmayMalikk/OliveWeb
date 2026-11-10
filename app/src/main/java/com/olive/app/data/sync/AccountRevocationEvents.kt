// Signed-in accounts: closes an active private session after authoritative account revocation.
package com.olive.app.data.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal object AccountRevocationEvents {
    private val mutableEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val events = mutableEvents.asSharedFlow()
    fun publish(uid: String) { mutableEvents.tryEmit(uid) }
}

