package com.olive.app.ui.session

import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.job
import kotlin.time.Duration.Companion.milliseconds

// Owns serialized operation state, cancellation and failure messages.
internal class SessionOperations(private val runtime:SessionRuntime) {
    fun runOperation(failure: String, block: suspend (Long) -> Unit) {
        with(runtime) {
            with(state) {
                val token=epoch
                busy=true; message=null
                operationJob=scope.launch {
                    val runningJob=coroutineContext.job
                    var finalFailure: String? = null
                    try { withTimeout(25_000.milliseconds) { block(token) } }
                    catch(_: TimeoutCancellationException) { if(epoch == token) finalFailure="The operation timed out. Check your connection and retry; your account may already exist." }
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
