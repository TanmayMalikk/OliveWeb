package com.olive.app.ui.covers

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Amplitude is a hardware request; devices without amplitude control use their default strength.
 *
 * @param context Android context used to access system services, resources, or storage.
 * @return `true` if perform emergency vibration, or `false` otherwise.
 */
@Suppress("DEPRECATION")
internal fun performEmergencyVibration(context: Context): Boolean {
    val vibrator = if (Build.VERSION.SDK_INT >= 31)
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    else context.getSystemService(Vibrator::class.java)
    if (vibrator == null || !vibrator.hasVibrator()) return false
    val amplitude = if (vibrator.hasAmplitudeControl()) 179 else VibrationEffect.DEFAULT_AMPLITUDE
    val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    vibrator.vibrate(VibrationEffect.createOneShot(120, amplitude), attributes)
    return true
}
