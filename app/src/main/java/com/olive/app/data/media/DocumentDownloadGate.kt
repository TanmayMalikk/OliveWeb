// Approved viewers: serializes document opens so downloads cannot overwrite each other.
package com.olive.app.data.media

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Prevents two opens of the same document from racing over its decrypted output. */
internal class DocumentDownloadGate {
    private val locks=ConcurrentHashMap<String,Mutex>()

    suspend fun <T> withDocumentLock(documentId:String,block:suspend ()->T):T =
        locks.computeIfAbsent(documentId) { Mutex() }.withLock { block() }
}
