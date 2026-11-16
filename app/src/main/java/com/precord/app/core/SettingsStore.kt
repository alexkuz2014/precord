package com.precord.app.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** SharedPreferences-backed settings exposed as a hot [StateFlow]. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("precord_settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<PrecordSettings> = _flow.asStateFlow()
    val value: PrecordSettings get() = _flow.value

    private fun load(): PrecordSettings {
        val d = PrecordSettings()
        return PrecordSettings(
            bufferSec = prefs.getInt("bufferSec", d.bufferSec),
            sampleRate = prefs.getInt("sampleRate", d.sampleRate),
            format = runCatching { ClipFormat.valueOf(prefs.getString("format", d.format.name)!!) }.getOrDefault(d.format),
            normalize = prefs.getBoolean("normalize", d.normalize),
            gainDb = prefs.getInt("gainDb", d.gainDb),
            gateEnabled = prefs.getBoolean("gateEnabled", d.gateEnabled),
            gateDb = prefs.getInt("gateDb", d.gateDb),
            shakeToSave = prefs.getBoolean("shake", d.shakeToSave),
            volumeCombo = prefs.getBoolean("volumeCombo", d.volumeCombo),
            autoExport = prefs.getBoolean("autoExport", d.autoExport),
        ).coerced()
    }

    @Synchronized
    fun update(transform: (PrecordSettings) -> PrecordSettings) {
        val s = transform(_flow.value).coerced()
        prefs.edit()
            .putInt("bufferSec", s.bufferSec)
            .putInt("sampleRate", s.sampleRate)
            .putString("format", s.format.name)
            .putBoolean("normalize", s.normalize)
            .putInt("gainDb", s.gainDb)
            .putBoolean("gateEnabled", s.gateEnabled)
            .putInt("gateDb", s.gateDb)
            .putBoolean("shake", s.shakeToSave)
            .putBoolean("volumeCombo", s.volumeCombo)
            .putBoolean("autoExport", s.autoExport)
            .apply()
        _flow.value = s
    }
}
