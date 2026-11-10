// Safety-plan roles: refreshes authorized plans into the existing encrypted plan store.
package com.olive.app.data.sync

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.olive.app.data.AccountRole
import com.olive.app.data.SafetyPlan
import com.olive.app.data.SafetyAccessIndexStore
import com.olive.app.data.SafetyPlanRepository
import com.olive.app.data.SafetyPlanStore
import com.olive.app.data.mergeSafetyPlan
import kotlinx.coroutines.tasks.await

internal class SafetyPlanReconciler(
    context: Context,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val repository: SafetyPlanRepository = SafetyPlanRepository(),
) : PriorityDomainReconciler {
    private val store = SafetyPlanStore(context.applicationContext, false)
    private val accessIndex = SafetyAccessIndexStore(context.applicationContext)

    override suspend fun reconcile(uid: String) {
        val user = auth.currentUser ?: error("Authentication is unavailable")
        check(user.uid == uid) { "Signed-in account changed" }
        val role = AccountRole.fromStored(user.getIdToken(false).await().claims["role"] as? String ?: "")
        val owners = when (role) {
            AccountRole.USER -> listOf(uid)
            AccountRole.SAFE_CONTACT -> repository.sharedWithMe().also {
                accessIndex.replaceShared(uid, it)
            }.map { it.ownerUid }
            AccountRole.STAFF, AccountRole.ADMIN -> repository.consultations().also { result ->
                accessIndex.replaceConsultations(uid, result.first, result.second)
            }.second.map { it.ownerUid }
        }.distinct()
        val remote = owners.map { repository.get(it) }
        store.all(uid).filterNot { it.ownerUid in owners }.forEach { store.remove(uid, it.ownerUid) }
        remote.forEach { plan -> store.write(uid, plan.ownerUid, merge(plan, store.read(uid, plan.ownerUid))) }
    }

    private fun merge(remote: SafetyPlan, local: SafetyPlan?): SafetyPlan {
        if (local == null || !local.pending) return remote
        val combined = local.copy(fields = remote.fields + local.fields)
        return mergeSafetyPlan(combined, combined, remote)
    }
}

