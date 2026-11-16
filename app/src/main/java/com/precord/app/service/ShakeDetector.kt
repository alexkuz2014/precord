package com.precord.app.service

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/** Three hard jolts within 1.2 s (>~2.7 g) trigger [onShake]; 4 s cool-down. */
class ShakeDetector(private val sm: SensorManager, private val onShake: () -> Unit) : SensorEventListener {
    private val hits = ArrayDeque<Long>()
    private var lastFire = 0L
    private var lastHit = 0L
    private var active = false

    fun start() {
        if (active) return
        val s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        active = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!active) return
        sm.unregisterListener(this)
        active = false
        hits.clear()
    }

    override fun onSensorChanged(e: SensorEvent) {
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
        if (g < 2.7f) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastHit < 150) return // one physical shake = one hit
        lastHit = now
        hits.addLast(now)
        while (hits.isNotEmpty() && now - hits.first() > 1200) hits.removeFirst()
        if (hits.size >= 3 && now - lastFire > 4000) {
            lastFire = now
            hits.clear()
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
