// Admins: holds the live user locations shown on the organization map.
package com.olive.app.ui.users.admin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olive.app.data.AccountRole
import com.olive.app.data.AdminLocationRepository
import com.olive.app.data.ChatSubscription
import com.olive.app.data.MapLocationPoint

internal data class AdminLocationMapState(
    val points: List<MapLocationPoint> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** Opens one admin-only Firebase listener and closes it when the map is left. */
internal class AdminLocationMapController(
    private val repository: AdminLocationRepository?,
) {
    var state by mutableStateOf(AdminLocationMapState())
        private set
    private var subscription: ChatSubscription? = null

    // Starts the organization feed only for an authenticated administrator.
    fun open(role: AccountRole) {
        if (role != AccountRole.ADMIN || repository == null || subscription != null) return
        state = AdminLocationMapState(loading = true)
        subscription = repository.observe(
            onUpdate = { points ->
                state = AdminLocationMapState(points = points)
            },
            onError = { message ->
                state = state.copy(loading = false, error = message)
            },
        )
    }

    // Stops Firebase updates and clears sensitive map data from memory.
    fun close() {
        subscription?.close()
        subscription = null
        state = AdminLocationMapState()
    }
}
