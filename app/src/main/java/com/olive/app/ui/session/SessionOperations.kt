package com.olive.app.ui.session
import com.olive.app.R

import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.job
import kotlin.time.Duration.Companion.milliseconds

// Uses a 25-second timeout by default; Android authentication gets longer. Epoch/job checks reject canceled work.
internal class SessionOperations(private val runtime:SessionRuntime) {
    fun runOperation(failure: String, timeoutMillis: Long = 25_000, block: suspend (Long) -> Unit) {
        with(runtime) {
            with(state) {
                val token=epoch
                busy=true; message=null
                operationJob=scope.launch {
                    val runningJob=coroutineContext.job
                    var finalFailure: String? = null
                    try { withTimeout(timeoutMillis.milliseconds) { block(token) } }
                    catch(_: TimeoutCancellationException) { if(epoch == token) finalFailure=application.getString(R.string.the_operation_timed_out_check_your_connection_and_retry_your_account_may_already_exist) }
                    catch(e: CancellationException) { throw e }
                    catch(_: Exception) { if(epoch == token) finalFailure=failure }
                    finally {
                        if(operationJob===runningJob) {
                            operationJob=null;busy=false
                            finalFailure?.let { message=it }
                        }
                    }
                }
            }
        }
    }
}
