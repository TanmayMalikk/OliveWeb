package com.olive.app.ui.session

import android.content.Context
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.functions.FirebaseFunctions
import com.olive.app.R
import com.olive.app.data.location.AdminLocationRepository
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.messaging.ChatSubscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

internal data class EmergencyAlert(
    val id: String, val ownerUid: String, val ownerName: String,
    val createdAt: Long, val mediaId: String, val resolvedAt: Long = 0L,
)

// Owns the remote emergency operations; Compose only observes results and sends actions.
internal class EmergencyResponseController(private val context: Context) {
    fun observeAlerts(
        onUpdate: (List<EmergencyAlert>) -> Unit,
        onError: (String?) -> Unit,
    ): ChatSubscription {
        val listener = FirebaseFirestore.getInstance().collection("emergencyReports")
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, failure ->
                if (failure != null) onError(failure.localizedMessage)
                if (snapshot != null && !snapshot.metadata.isFromCache) {
                    onUpdate(snapshot.documents.mapNotNull { doc ->
                        val owner = doc.getString("ownerUid") ?: return@mapNotNull null
                        EmergencyAlert(
                            doc.id, owner, doc.getString("ownerName") ?: owner,
                            doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L,
                            doc.getString("mediaId").orEmpty(),
                            doc.getTimestamp("resolvedAt")?.toDate()?.time ?: 0L,
                        )
                    }.sortedByDescending { it.createdAt })
                }
            }
        return ChatSubscription { listener.remove() }
    }

    fun observeLocations(
        onUpdate: (List<MapLocationPoint>) -> Unit,
        onError: (String) -> Unit,
    ): ChatSubscription = AdminLocationRepository().observe(onUpdate, onError)

    suspend fun setResolved(accountId: String?, alertId: String, resolved: Boolean) {
        val user = FirebaseAuth.getInstance().currentUser
        check(accountId != null && user?.uid == accountId) { "Sign in again to resolve this request." }
        user.getIdToken(false).await()
        try {
            FirebaseAppCheck.getInstance().getAppCheckToken(false).await()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            throw IllegalStateException(context.getString(R.string.emergency_report_device_verification_failed), failure)
        }
        check(FirebaseAuth.getInstance().currentUser?.uid == accountId) { "Sign in again to resolve this request." }
        FirebaseFunctions.getInstance("northamerica-northeast1")
            .getHttpsCallable(if (resolved) "emergencyResolve" else "emergencyReopen")
            .call(mapOf("id" to alertId)).await()
    }
}
