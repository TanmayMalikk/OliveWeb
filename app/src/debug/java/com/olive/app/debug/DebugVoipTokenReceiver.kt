package com.olive.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.olive.app.data.messaging.VoipPushTokenRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DebugVoipTokenReceiver : BroadcastReceiver() {
    /**
     * Handles the receive event.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param intent Android intent containing the action and optional extras for this component.
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ROTATE) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                VoipPushTokenRegistration.rotateCurrentToken(context.applicationContext)
                Log.i(VoipPushTokenRegistration.TAG, "Debug token rotation completed")
            } catch (error: Exception) {
                Log.e(VoipPushTokenRegistration.TAG, "Debug token rotation failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_ROTATE = "com.olive.app.debug.ROTATE_VOIP_TOKEN"
    }
}
