// Admins: reads the live locations that every signed-in user publishes for the organization map.
package com.olive.app.data

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/** Listens to the admin-only location feed in Firebase Realtime Database. */
internal class AdminLocationRepository(
    private val databaseProvider: () -> FirebaseDatabase = LocationDatabase::instance,
) {
    // Keeps the admin map updated whenever a user's phone publishes a new location.
    fun observe(
        onUpdate: (List<MapLocationPoint>) -> Unit,
        onError: (String) -> Unit,
    ): ChatSubscription {
        val reference = databaseProvider().reference.child(ADMIN_LOCATION_PATH)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val points = snapshot.children.mapNotNull(::locationPoint).sortedBy { it.name }
                onUpdate(points)
            }

            override fun onCancelled(error: DatabaseError) {
                onError(error.message)
            }
        }
        reference.addValueEventListener(listener)
        return ChatSubscription { reference.removeEventListener(listener) }
    }

    // Rejects incomplete or invalid Firebase rows before they reach the map.
    private fun locationPoint(snapshot: DataSnapshot): MapLocationPoint? {
        val uid = snapshot.key?.takeIf(String::isNotBlank) ?: return null
        val latitude = snapshot.child("latitude").getValue(Double::class.java) ?: return null
        val longitude = snapshot.child("longitude").getValue(Double::class.java) ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        val updatedAt = snapshot.child("updatedAt").getValue(Long::class.java) ?: return null
        val active = snapshot.child("active").getValue(Boolean::class.java) == true
        val name = snapshot.child("name").getValue(String::class.java).orEmpty().ifBlank { uid }
        val accuracy = snapshot.child("accuracy").getValue(Double::class.java)?.toFloat()
        return MapLocationPoint(
            uid = uid,
            name = name,
            latitude = latitude,
            longitude = longitude,
            updatedAt = updatedAt,
            live = active,
            accuracy = accuracy,
            lastKnown = !active,
        )
    }

    private companion object {
        const val ADMIN_LOCATION_PATH = "adminLiveLocations"
    }
}
