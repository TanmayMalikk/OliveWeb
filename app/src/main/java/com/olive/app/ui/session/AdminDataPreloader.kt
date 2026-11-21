package com.olive.app.ui.session

import android.os.SystemClock
import androidx.compose.runtime.*
import com.olive.app.R
import com.olive.app.data.account.*
import com.olive.app.ui.users.shared.navigation.SessionScreen
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import java.util.concurrent.ConcurrentHashMap

internal data class AdminPreloaderUiState(
    val foregroundLoads: Set<String> = emptySet(),
    val accountsCache: List<AdminAccountSummary>? = null,
    val staffCache: List<AdminAccountSummary>? = null,
    val loadingAccounts: Boolean = false,
    val loadingStaff: Boolean = false,
    val accountsError: Int? = null,
    val staffError: Int? = null,
)

// In-memory admin data belongs to one authenticated session, including legitimately empty results.
internal class AdminDataPreloader(private val runtime: SessionRuntime) {
    private enum class Dataset { Summary, Accounts, Staff }
    private val jobs = ConcurrentHashMap<Dataset, Job>()
    private val refreshedAt = ConcurrentHashMap<Dataset, Long>()
    private var owner: Pair<String, Long>? = null
    private val uiStateFlow = MutableStateFlow(AdminPreloaderUiState())
    val uiState: StateFlow<AdminPreloaderUiState> = uiStateFlow.asStateFlow()
    private var uiStateSnapshot by mutableStateOf(uiStateFlow.value)

    /**
     * Atomically updates the UI state flow and updates the local state snapshot.
     *
     * @param transform State reducer transforming current state.
     */
    private inline fun updateUiState(transform: (AdminPreloaderUiState) -> AdminPreloaderUiState) {
        uiStateSnapshot = uiStateFlow.updateAndGet(transform)
    }

    private var foregroundLoads: Set<String>
        get() = uiStateSnapshot.foregroundLoads
        set(value) = updateUiState { it.copy(foregroundLoads = value) }
    private var accountsCache: List<AdminAccountSummary>?
        get() = uiStateSnapshot.accountsCache
        set(value) = updateUiState { it.copy(accountsCache = value) }
    private var staffCache: List<AdminAccountSummary>?
        get() = uiStateSnapshot.staffCache
        set(value) = updateUiState { it.copy(staffCache = value) }
    var loadingAccounts: Boolean
        get() = uiStateSnapshot.loadingAccounts
        private set(value) = updateUiState { it.copy(loadingAccounts = value) }
    var loadingStaff: Boolean
        get() = uiStateSnapshot.loadingStaff
        private set(value) = updateUiState { it.copy(loadingStaff = value) }
    var accountsError: Int?
        get() = uiStateSnapshot.accountsError
        private set(value) = updateUiState { it.copy(accountsError = value) }
    var staffError: Int?
        get() = uiStateSnapshot.staffError
        private set(value) = updateUiState { it.copy(staffError = value) }
    /**
     * Returns the session.
     *
     * @return The resulting session.
     */
    private fun session() = runtime.coverAccountId?.let { it to runtime.epoch }
    /**
     * Computes authorized.
     *
     * @param token Authentication, push notification, or session token.
     * @return The resulting authorized.
     */
    private fun authorized(token: Pair<String, Long>) = session() == token &&
        runtime.state.accountRole == AccountRole.ADMIN && runtime.state.screen == SessionScreen.Portal
    /**
     * Returns the owns cache.
     *
     * @return The resulting owns cache.
     */
    private fun ownsCache() = owner == session() && runtime.state.accountRole == AccountRole.ADMIN
    val accounts get() = if (ownsCache() || owner == null && runtime.state.accountRole == AccountRole.ADMIN) accountsCache ?: runtime.state.adminAccounts else emptyList()
    val staff get() = if (ownsCache() || owner == null && runtime.state.accountRole == AccountRole.ADMIN) staffCache ?: runtime.state.adminAccounts.filter { it.role == AccountRole.STAFF } else emptyList()
    val accountsReady get() = ownsCache() && accountsCache != null
    val staffReady get() = ownsCache() && staffCache != null
    val showAccountsSkeleton get() = loadingAccounts && (!accountsReady || Dataset.Accounts.name in foregroundLoads)
    val showStaffSkeleton get() = loadingStaff && (!staffReady || Dataset.Staff.name in foregroundLoads)

    /**
     * Clears the admin data preloader.
     */
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

    /**
     * Computes preload.
     */
    fun preload() {
        summary()
        accounts()
        staff()
    }
    /**
     * Computes summary.
     *
     * @param force Whether to apply force.
     * @return The resulting summary.
     */
    fun summary(force: Boolean = false) = load(Dataset.Summary, force)
    /**
     * Computes accounts.
     *
     * @param force Whether to apply force.
     * @param showLoading Whether to show loading.
     */
    fun accounts(force: Boolean = false, showLoading: Boolean = false) = load(Dataset.Accounts, force, showLoading)
    /**
     * Computes staff.
     *
     * @param force Whether to apply force.
     * @param showLoading Whether to show loading.
     */
    fun staff(force: Boolean = false, showLoading: Boolean = false) = load(Dataset.Staff, force, showLoading)

    /**
     * A mutation replaces the directory immediately; workload totals refresh independently.
     *
     * @param rows Collection of rows values to process.
     * @param counts Counts supplied to [adopt].
     */
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
        foregroundLoads = foregroundLoads - Dataset.Accounts.name
        accountsError = null
        refreshedAt[Dataset.Accounts] = SystemClock.elapsedRealtime()
        refreshedAt[Dataset.Summary] = SystemClock.elapsedRealtime()
        jobs.remove(Dataset.Staff)?.cancel()
        staff(force = true)
    }

    /**
     * Loads the admin data preloader.
     *
     * @param dataset Dataset supplied to [load].
     * @param force Whether to apply force.
     * @param showLoading Whether to show loading.
     */
    private fun load(dataset: Dataset, force: Boolean, showLoading: Boolean = false) {
        val token = session() ?: return
        val repository = runtime.adminRepository ?: return
        if (!authorized(token)) return
        if (owner != token) { clear(); owner = token }
        if (jobs[dataset]?.isActive == true) {
            if (showLoading) foregroundLoads = foregroundLoads + dataset.name
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && refreshedAt[dataset]?.let { now - it < 90_000 } == true) return
        foregroundLoads = if (showLoading) foregroundLoads + dataset.name else foregroundLoads - dataset.name
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
                    foregroundLoads = foregroundLoads - dataset.name
                    if (dataset == Dataset.Accounts) loadingAccounts = false
                    if (dataset == Dataset.Staff) loadingStaff = false
                }
            }
        }
        jobs[dataset] = request
        request.start()
    }
}
