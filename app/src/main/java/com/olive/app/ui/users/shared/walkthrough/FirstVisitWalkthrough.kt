// Safe-app users and safe contacts: tracks whether the account has already seen its first-login walkthrough.
package com.olive.app.ui.users.shared.walkthrough

import android.content.Context
import com.olive.app.data.network.sha256Hex

/**
 * Persists whether an account has already been shown the automatic full walkthrough upon its
 * initial login to the Safe App.
 *
 * @param context Android context owning app preferences.
 * @param accountId Account identifier scoped cleanly via SHA-256 hash.
 */
internal class WalkthroughVisits(context: Context, accountId: String) {
    private val preferences = context.getSharedPreferences("olive_walkthrough_visits", Context.MODE_PRIVATE)
    private val initialTourKey = sha256Hex("walkthrough:initial:$accountId")

    /**
     * Returns `true` if this account has already opened the automatic first-login walkthrough.
     */
    fun hasSeenInitialTour(): Boolean = preferences.getBoolean(initialTourKey, false)

    /**
     * Records that the automatic first-login walkthrough has been opened for this account.
     */
    fun markInitialTourSeen() {
        preferences.edit().putBoolean(initialTourKey, true).apply()
    }
}
