package com.olive.app.ui.session

import androidx.compose.runtime.*
import com.google.firebase.functions.FirebaseFunctionsException
import com.olive.app.R
import com.olive.app.data.*
import com.olive.app.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

// Private presentation state is cleared on exit; captured local writes finish without reopening a private screen.
internal class SessionSafetyPlans(private val runtime:SessionRuntime) {
    private val store=SafetyPlanStore(runtime.application,runtime.localMode)
    private val repository by lazy { SafetyPlanRepository() }
    private val writes=Mutex()
    private val sync=Mutex()
    private var activeOwner:String?=null
    private var lease:Any?=null
    private var generation=0
    var plan by mutableStateOf<SafetyPlan?>(null);private set
    var error by mutableStateOf<Int?>(null);private set
    var saving by mutableIntStateOf(0);private set
    var consultations by mutableStateOf<List<SafetyConsultation>>(emptyList());private set
    var sharedPlans by mutableStateOf<List<SharedSafetyPlan>>(emptyList());private set
    var available by mutableStateOf(true);private set
    private fun authorized()=runtime.enrollment!=null &&
        ((runtime.state.accountRole in setOf(AccountRole.USER,AccountRole.SAFE_CONTACT) && runtime.state.screen==SessionScreen.Safe && runtime.state.page==SafePage.SafetyPlan) ||
            (runtime.state.accountRole==AccountRole.STAFF && runtime.state.screen==SessionScreen.Portal))
    private fun current(actor:String,owner:String,token:Int)=authorized() && runtime.coverAccountId==actor && activeOwner==owner && generation==token
    fun close(screenLease:Any?=null) { if(screenLease!=null && lease!==screenLease) return;lease=null;generation++;activeOwner=null;plan=null;error=null;saving=0;if(screenLease==null) consultations=emptyList() }
    suspend fun open(ownerUid:String,ownerName:String,defaults:Map<String,String>,screenLease:Any) {
        if(!authorized() || (runtime.state.accountRole==AccountRole.USER && ownerUid!=runtime.coverAccountId)) return
        val actor=runtime.coverAccountId ?: return
        lease=screenLease;generation++;val token=generation;activeOwner=ownerUid;plan=null;error=null;saving=0
        try {
            val value=withContext(Dispatchers.IO) { store.ensure(actor,ownerUid,ownerName,defaults) }
            if(!runtime.localMode && value.pending) SafetyPlanSyncWorker.enqueue(runtime.application,actor,ownerUid)
            if(current(actor,ownerUid,token)) plan=value
            refresh()
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(_:Exception) { if(current(actor,ownerUid,token)) error=R.string.safety_storage_error }
    }
    fun edit(key:String,value:String) {
        val old=plan ?: return
        if(!authorized() || runtime.state.accountRole==AccountRole.SAFE_CONTACT || key !in SAFETY_FIELD_KEYS || value.length>4000) return
        val field=old.fields[key] ?: return
        if(field.value==value) return
        val edited=field.edit(value)
        plan=old.copy(fields=old.fields+(key to edited))
        persist { saved -> saved.copy(fields=saved.fields+(key to edited)) }
    }
    fun resolve(key:String,keepMine:Boolean) {
        val old=plan ?: return
        if(runtime.state.accountRole==AccountRole.SAFE_CONTACT) return
        val field=old.fields[key] ?: return
        val revision=field.conflictRevision ?: return
        val edited=if(keepMine) field.copy(revision=revision,conflictRevision=null,conflictValue=null).edit(field.value)
            else SafetyField(field.conflictValue.orEmpty(),revision)
        plan=old.copy(fields=old.fields+(key to edited))
        persist { saved -> saved.copy(fields=saved.fields+(key to edited)) }
    }
    fun consult(stop:Boolean=false) {
        if(runtime.localMode) { error=R.string.safety_account_required;return }
        val old=plan ?: return
        if(runtime.state.accountRole!=AccountRole.USER || (!stop && old.hasConflicts)) return
        val action=if(stop) "stop" else "request"
        plan=old.copy(shareAction=action)
        persist { it.copy(shareAction=action) }
    }
    fun shareWithContacts(friends:List<Friend>) {
        if(!authorized() || runtime.state.accountRole!=AccountRole.USER || runtime.localMode || friends.size>10) return
        val draft=plan ?: return
        val actor=runtime.coverAccountId ?: return
        val token=generation
        saving++;error=null
        runtime.scope.launch {
            try {
                repository.sync(draft)
                val updated=repository.shareContacts(friends)
                if(current(actor,actor,token)) {
                    plan=updated
                    error=null
                    withContext(Dispatchers.IO) { store.write(actor,actor,updated) }
                }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { if(current(actor,actor,token)) error=R.string.safety_sync_error }
            finally { if(current(actor,actor,token)) saving=(saving-1).coerceAtLeast(0) }
        }
    }
    fun refreshSharedPlans() {
        if(!authorized() || runtime.state.accountRole!=AccountRole.SAFE_CONTACT || runtime.localMode) return
        val actor=runtime.coverAccountId;val token=generation
        runtime.scope.launch {
            try {
                val result=repository.sharedWithMe()
                if(actor!=null && runtime.coverAccountId==actor && authorized() && generation==token) {
                    sharedPlans=result
                    error=null
                }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { if(actor!=null && runtime.coverAccountId==actor && authorized()) error=R.string.safety_sync_error }
        }
    }
    private fun persist(change:(SafetyPlan)->SafetyPlan) {
        if(!authorized()) return
        val actor=runtime.coverAccountId ?: return
        val owner=activeOwner ?: return
        val token=generation
        saving++;error=null
        runtime.scope.launch {
            try {
                withContext(Dispatchers.IO) { writes.withLock { store.update(actor,owner,change) } }
                if(!runtime.localMode) SafetyPlanSyncWorker.enqueue(runtime.application,actor,owner)
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { if(current(actor,owner,token)) error=R.string.safety_storage_error }
            finally { if(current(actor,owner,token)) saving=(saving-1).coerceAtLeast(0) }
        }
    }
    fun retrySave() {
        val draft=plan ?: return
        persist { saved -> saved.copy(fields=draft.fields,shareAction=draft.shareAction) }
    }
    suspend fun refresh() {
        if(error==R.string.safety_storage_error) return
        if(!authorized() || saving>0 || !sync.tryLock()) return
        val actor=runtime.coverAccountId ?: run { sync.unlock();return }
        val owner=activeOwner ?: run { sync.unlock();return }
        val token=generation
        try {
            if(!runtime.localMode && hasSafetyConnection(runtime.application)) {
                withTimeout(35.seconds) { withContext(Dispatchers.IO) { syncSafetyPlan(store,repository,actor,owner) } }
            }
            val saved=withContext(Dispatchers.IO) { store.read(actor,owner) }
            if(current(actor,owner,token) && saving==0) { plan=saved;error=null }
        } catch(_:TimeoutCancellationException) { /* Retain the local draft; the durable queue retries when connected. */ }
        catch(cancelled:CancellationException) { throw cancelled }
        catch(failure:FirebaseFunctionsException) {
            if(failure.code==FirebaseFunctionsException.Code.PERMISSION_DENIED && actor!=owner) {
                withContext(Dispatchers.IO) { store.remove(actor,owner) }
                if(current(actor,owner,token)) { plan=null;error=R.string.safety_access_revoked }
            }
        } catch(_:Exception) { /* Like Notes, connection failures leave saved edits queued without a warning banner. */ }
        finally { sync.unlock() }
    }
    suspend fun refreshConsultations() {
        if(!authorized() || runtime.state.accountRole!=AccountRole.STAFF || runtime.localMode) return
        val actor=runtime.coverAccountId ?: return
        try {
            val cached=withContext(Dispatchers.IO) { store.all(actor) }
            if(authorized() && actor==runtime.coverAccountId) consultations=cached.map { SafetyConsultation(it.ownerUid,it.ownerName) }
            if(!hasSafetyConnection(runtime.application)) return
            val result=repository.consultations()
            withContext(Dispatchers.IO) { cached.filter { old -> result.second.none { it.ownerUid==old.ownerUid } }.forEach { store.remove(actor,it.ownerUid) } }
            if(authorized() && actor==runtime.coverAccountId) { available=result.first;consultations=result.second;error=null }
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(_:Exception) { if(authorized() && actor==runtime.coverAccountId) error=R.string.safety_sync_error }
    }
    fun changeAvailability(value:Boolean) {
        if(!authorized() || runtime.state.accountRole!=AccountRole.STAFF || runtime.localMode) return
        val actor=runtime.coverAccountId
        runtime.scope.launch {
            try { repository.available(value);if(authorized() && actor==runtime.coverAccountId) available=value }
            catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { if(authorized() && actor==runtime.coverAccountId) error=R.string.safety_sync_error }
        }
    }
}
