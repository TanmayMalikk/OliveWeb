package com.olive.app.ui.session

import android.os.SystemClock
import androidx.compose.runtime.*
import com.olive.app.R
import com.olive.app.data.account.*
import com.olive.app.ui.users.shared.navigation.SessionScreen
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

// In-memory admin data belongs to one authenticated session, including legitimately empty results.
internal class AdminDataPreloader(private val runtime: SessionRuntime) {
    private enum class Dataset { Summary, Accounts, Staff }
    private val jobs = ConcurrentHashMap<Dataset, Job>()
    private val refreshedAt = ConcurrentHashMap<Dataset, Long>()
    private var owner: Pair<String, Long>? = null
    private var foregroundLoads by mutableStateOf(emptySet<Dataset>())
    private var accountsCache by mutableStateOf<List<AdminAccountSummary>?>(null)
    private var staffCache by mutableStateOf<List<AdminAccountSummary>?>(null)
    var loadingAccounts by mutableStateOf(false)
        private set
    var loadingStaff by mutableStateOf(false)
        private set
    var accountsError by mutableStateOf<Int?>(null)
        private set
    var staffError by mutableStateOf<Int?>(null)
        private set
    private fun session() = runtime.coverAccountId?.let { it to runtime.epoch }
    private fun authorized(token: Pair<String, Long>) = session() == token &&
        runtime.state.accountRole == AccountRole.ADMIN && runtime.state.screen == SessionScreen.Portal
    private fun ownsCache() = owner == session() && runtime.state.accountRole == AccountRole.ADMIN
    val accounts get() = if (ownsCache() || owner == null && runtime.state.accountRole == AccountRole.ADMIN) accountsCache ?: runtime.state.adminAccounts else emptyList()
    val staff get() = if (ownsCache() || owner == null && runtime.state.accountRole == AccountRole.ADMIN) staffCache ?: runtime.state.adminAccounts.filter { it.role == AccountRole.STAFF } else emptyList()
    val accountsReady get() = ownsCache() && accountsCache != null
    val staffReady get() = ownsCache() && staffCache != null
    val showAccountsSkeleton get() = loadingAccounts && (!accountsReady || Dataset.Accounts in foregroundLoads)
    val showStaffSkeleton get() = loadingStaff && (!staffReady || Dataset.Staff in foregroundLoads)

    fun clear() {
        jobs.values.toList().forEach(Job::cancel)
        jobs.clear()
        refreshedAt.clear()
        owner = null
        foregroundLoads = emptySet()
        accountsCache = null
        staffCache = null
        loadingAccounts = false
        loadingStaff = false
        accountsError = null
        staffError = null
        runtime.state.adminAccounts = emptyList()
        runtime.state.setAdminSummary("", AccountCounts())
    }

    fun preload() {
        summary()
        accounts()
        staff()
    }
    fun summary(force: Boolean = false) = load(Dataset.Summary, force)
    fun accounts(force: Boolean = false, showLoading: Boolean = false) = load(Dataset.Accounts, force, showLoading)
    fun staff(force: Boolean = false, showLoading: Boolean = false) = load(Dataset.Staff, force, showLoading)

    // A mutation replaces the directory immediately; workload totals refresh independently.
    fun adopt(rows: List<AdminAccountSummary>, counts: AccountCounts) {
        val token = session() ?: return
        if (!authorized(token)) return
        if (owner != token) clear()
        owner = token
        jobs.remove(Dataset.Accounts)?.cancel()
        jobs.remove(Dataset.Summary)?.cancel()
        accountsCache = rows
        runtime.state.adminAccounts = rows
        runtime.state.setAdminSummary(token.first, counts)
        loadingAccounts = false
        foregroundLoads = foregroundLoads - Dataset.Accounts
        accountsError = null
        refreshedAt[Dataset.Accounts] = SystemClock.elapsedRealtime()
        refreshedAt[Dataset.Summary] = SystemClock.elapsedRealtime()
        jobs.remove(Dataset.Staff)?.cancel()
        staff(force = true)
    }

    private fun load(dataset: Dataset, force: Boolean, showLoading: Boolean = false) {
        val token = session() ?: return
        val repository = runtime.adminRepository ?: return
        if (!authorized(token)) return
        if (owner != token) { clear(); owner = token }
        if (jobs[dataset]?.isActive == true) {
            if (showLoading) foregroundLoads = foregroundLoads + dataset
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && refreshedAt[dataset]?.let { now - it < 90_000 } == true) return
        foregroundLoads = if (showLoading) foregroundLoads + dataset else foregroundLoads - dataset
        when (dataset) {
            Dataset.Accounts -> { loadingAccounts = true; accountsError = null }
            Dataset.Staff -> { loadingStaff = true; staffError = null }
            else -> Unit
        }
        val request = runtime.scope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(25_000) {
                    when (dataset) {
                        Dataset.Summary -> repository.summary().let {
                            if (authorized(token)) runtime.state.setAdminSummary(token.first, it)
                        }
                        Dataset.Accounts -> repository.accounts().let {
                            if (authorized(token)) { accountsCache = it; runtime.state.adminAccounts = it }
                        }
                        Dataset.Staff -> repository.staffAccounts().let {
                            if (authorized(token)) staffCache = it
                        }
                    }
                }
                if (authorized(token)) refreshedAt[dataset] = SystemClock.elapsedRealtime()
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (authorized(token)) when (dataset) {
                    Dataset.Accounts -> accountsError = R.string.ui_unable_to_load_organization_accounts
                    Dataset.Staff -> staffError = R.string.ui_unable_to_load_organization_accounts
                    Dataset.Summary -> runtime.state.message = runtime.application.getString(R.string.ui_unable_to_load_organization_accounts)
                }
            } finally {
                if (jobs[dataset] === coroutineContext.job) {
                    jobs.remove(dataset)
                    foregroundLoads = foregroundLoads - dataset
                    if (dataset == Dataset.Accounts) loadingAccounts = false
                    if (dataset == Dataset.Staff) loadingStaff = false
                }
            }
        }
        jobs[dataset] = request
        request.start()
    }
}
