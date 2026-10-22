package com.olive.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctionsException
import com.olive.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

internal fun hasSafetyConnection(context:Context):Boolean {
    val manager=context.getSystemService(ConnectivityManager::class.java)
    val capabilities=manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

// Each upload reads the durable encrypted queue. Replacing a worker is safe because mutations are idempotent.
class SafetyPlanSyncWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        if(!BuildConfig.FIREBASE_CONFIGURED) return Result.success()
        val actor=inputData.getString("actor") ?: return Result.failure()
        val owner=inputData.getString("owner") ?: return Result.failure()
        if(FirebaseAuth.getInstance().currentUser?.uid!=actor) return Result.success()
        if(!hasSafetyConnection(applicationContext)) return Result.retry()
        return try {
            withTimeout(40.seconds) { syncSafetyPlan(SafetyPlanStore(applicationContext,false),SafetyPlanRepository(),actor,owner) }
            if(SafetyPlanStore(applicationContext,false).read(actor,owner)?.pending==true) Result.retry() else Result.success()
        } catch(_:TimeoutCancellationException) { Result.retry() }
        catch(cancelled:CancellationException) { throw cancelled }
        catch(error:FirebaseFunctionsException) {
            when {
                error.code==FirebaseFunctionsException.Code.PERMISSION_DENIED && actor!=owner -> {
                    SafetyPlanStore(applicationContext,false).remove(actor,owner);Result.success()
                }
                error.code in setOf(FirebaseFunctionsException.Code.INVALID_ARGUMENT,FirebaseFunctionsException.Code.NOT_FOUND) -> Result.failure()
                else -> Result.retry()
            }
        } catch(_:Exception) { Result.retry() }
    }
    companion object {
        fun enqueue(context:Context,actor:String,owner:String) {
            if(!BuildConfig.FIREBASE_CONFIGURED) return
            val request=OneTimeWorkRequestBuilder<SafetyPlanSyncWorker>().setInputData(workDataOf("actor" to actor,"owner" to owner))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(1,TimeUnit.SECONDS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("olive_safety_${actor}_$owner",ExistingWorkPolicy.REPLACE,request)
        }
    }
}

internal suspend fun syncSafetyPlan(store:SafetyPlanStore,repository:SafetyPlanRepository,actor:String,owner:String) {
    val sent=store.read(actor,owner) ?: return
    val remote=repository.sync(sent)
    val merged=store.update(actor,owner) { mergeSafetyPlan(it,sent,remote) }
    val action=merged.shareAction
    if(actor==owner && action.isNotEmpty() && (action=="stop" || !merged.hasEdits)) {
        val result=repository.consult(action)
        store.update(actor,owner) { current ->
            val completed=action=="stop" || result.staffUid.isNotBlank()
            current.copy(staffUid=result.staffUid,staffName=result.staffName,consultationRequested=result.consultationRequested,
                shareAction=if(current.shareAction==action && completed) "" else current.shareAction)
        }
    }
}
