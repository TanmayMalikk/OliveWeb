package com.olive.app.data.work

/** Shared minimum retry delay for user-initiated encrypted delivery and upload work. */
internal object DurableWorkPolicy {
    const val INITIAL_BACKOFF_SECONDS = 10L
}
