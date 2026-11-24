package com.olive.app.security

/** True only while Android temporarily owns the foreground for an approved app operation. */
internal fun trustedSystemHandoffActive(
    deviceCredential: Boolean,
    passkeyCredential: Boolean,
    runtimePermission: Boolean,
    externalFilePicker: Boolean,
    callPermission: Boolean,
    safeSystemActivity: Boolean,
): Boolean = deviceCredential || passkeyCredential || runtimePermission || externalFilePicker ||
    callPermission || safeSystemActivity

/** Holds a credential result only until the private activity is visible again. */
internal class LifecycleResultGate<T>(private val discard: (T) -> Unit = {}) {
    private var pending: T? = null

    fun offer(value: T, resumed: Boolean): T? {
        cancel()
        return if (resumed) value else null.also { pending = value }
    }

    fun resume(): T? = pending.also { pending = null }

    fun cancel() {
        pending?.let(discard)
        pending = null
    }
}
