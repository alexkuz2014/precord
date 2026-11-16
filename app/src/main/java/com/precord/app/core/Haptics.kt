package com.precord.app.core

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object Haptics {
    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31)
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)

    /** Heavy double-knock: the "clip captured" confirmation, perceivable even in a pocket. */
    fun thud(context: Context) {
        val v = vibrator(context) ?: return
        if (!v.hasVibrator()) return
        val effect = if (v.hasAmplitudeControl())
            VibrationEffect.createWaveform(longArrayOf(0, 35, 45, 70), intArrayOf(0, 255, 0, 200), -1)
        else VibrationEffect.createWaveform(longArrayOf(0, 35, 45, 70), -1)
        v.vibrate(effect)
    }

    /** Short crisp tick, used for discrete steps (service-side confirmations). */
    fun tick(context: Context) {
        val v = vibrator(context) ?: return
        if (v.hasVibrator()) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }
}
