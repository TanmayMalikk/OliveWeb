package com.olive.app.debug

/** Chooses the next explicit user action without holding credential material. */
internal object PasskeyPrfProbeFlow {
    fun nextAction(hasPendingAssertion: Boolean): PasskeyPrfProbeAction =
        if (hasPendingAssertion) {
            PasskeyPrfProbeAction.REQUEST_PRF_ASSERTION
        } else {
            PasskeyPrfProbeAction.CREATE_PASSKEY
        }
}

internal enum class PasskeyPrfProbeAction {
    CREATE_PASSKEY,
    REQUEST_PRF_ASSERTION,
}
