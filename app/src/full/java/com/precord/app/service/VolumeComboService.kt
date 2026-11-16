package com.precord.app.service

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.precord.app.core.Monitor
import com.precord.app.precord

/**
 * Optional trigger. Android gives apps no other way to see hardware keys with the screen off, so this
 * accessibility service filters key events only: Volume Up + Volume Down pressed within 400 ms while
 * the buffer is armed (and the toggle is on) saves a replay. It never reads screen content.
 */
class VolumeComboService : AccessibilityService() {
    private var upAt = 0L
    private var downAt = 0L
    private var lastFire = 0L

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        val now = SystemClock.elapsedRealtime()
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> upAt = now
            KeyEvent.KEYCODE_VOLUME_DOWN -> downAt = now
            else -> return false
        }
        if (!precord.settings.value.volumeCombo || !Monitor.state.value.running) return false
        if (kotlin.math.abs(upAt - downAt) <= 400 && now - lastFire > 3000) {
            lastFire = now
            upAt = 0; downAt = 0
            ServiceController.save(this)
            return true // swallow the second key so the volume does not change
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}
