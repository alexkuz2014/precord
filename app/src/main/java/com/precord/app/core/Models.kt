package com.precord.app.core

import java.io.File
import java.util.Locale

enum class ClipFormat(val label: String, val ext: String, val mime: String) {
    WAV("WAV", "wav", "audio/wav"),
    M4A("M4A", "m4a", "audio/mp4"),
    OPUS("OPUS", "ogg", "audio/ogg");

    companion object {
        fun fromFile(f: File): ClipFormat = entries.firstOrNull { it.ext == f.extension.lowercase() } ?: WAV
    }
}

data class PrecordSettings(
    val bufferSec: Int = 300,
    val sampleRate: Int = 48_000,
    val format: ClipFormat = ClipFormat.WAV,
    val normalize: Boolean = true,
    val gainDb: Int = 0,
    val gateEnabled: Boolean = false,
    val gateDb: Int = -45,
    val shakeToSave: Boolean = false,
    val volumeCombo: Boolean = false,
    val autoExport: Boolean = false,
    val rawMic: Boolean = false,
    val denoise: Boolean = false,
    val voiceBoost: Boolean = false,
    val echoCancel: Boolean = false,
    val indicator: Boolean = false,
    val autoRearm: Boolean = true,
) {
    val windowMs: Long get() = bufferSec * 1000L

    /** Exact RAM the ring buffer can grow to: seconds x rate x 2 bytes (mono PCM16). */
    val ramBytes: Long get() = bufferSec.toLong() * sampleRate * 2

    /** Opus only supports 8/12/16/24/48 kHz. */
    fun coerced(): PrecordSettings =
        if (format == ClipFormat.OPUS && sampleRate == 44_100) copy(format = ClipFormat.M4A) else this
}

data class Clip(
    val file: File,
    val title: String,
    val tags: List<String>,
    val durationMs: Long,
    val sizeBytes: Long,
    val createdAt: Long,
    val sampleRate: Int,
    val transcript: String = "",
) {
    val format: ClipFormat get() = ClipFormat.fromFile(file)
}

/** Localised unit labels; refreshed from resources by [Fmt.init] whenever the locale changes. */
data class Units(
    val s: String = "s", val m: String = "m", val h: String = "h",
    val khz: String = "kHz", val b: String = "B", val kb: String = "KB", val mb: String = "MB", val gb: String = "GB",
    val ramLine: String = "%1\$s @ %2\$s MONO = ~%3\$s RAM",
)

object Fmt {
    @Volatile var units = Units()

    fun init(r: android.content.res.Resources) {
        units = Units(
            r.getString(com.precord.app.R.string.unit_s), r.getString(com.precord.app.R.string.unit_m), r.getString(com.precord.app.R.string.unit_h),
            r.getString(com.precord.app.R.string.unit_khz), r.getString(com.precord.app.R.string.unit_b), r.getString(com.precord.app.R.string.unit_kb),
            r.getString(com.precord.app.R.string.unit_mb), r.getString(com.precord.app.R.string.unit_gb), r.getString(com.precord.app.R.string.ram_line),
        )
    }

    /** 03:42 or 1:03:42 */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%02d:%02d", m, sec)
    }

    /** 03:42.3 */
    fun clockTenths(ms: Long): String {
        val t = (ms / 100).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d.%d", t / 600, (t / 10) % 60, t % 10)
    }

    fun bytes(b: Long): String = with(units) {
        when {
            b >= 1_000_000_000 -> String.format(Locale.US, "%.2f %s", b / 1e9, gb)
            b >= 1_000_000 -> String.format(Locale.US, "%.1f %s", b / 1e6, mb)
            b >= 1_000 -> String.format(Locale.US, "%.0f %s", b / 1e3, kb)
            else -> "$b ${this.b}"
        }
    }

    fun duration(sec: Int): String = with(units) {
        when {
            sec < 60 -> "$sec$s"
            sec % 60 == 0 && sec < 3600 -> "${sec / 60}$m"
            sec % 3600 == 0 -> "${sec / 3600}$h"
            else -> "${sec / 60}$m${(sec % 60).toString().padStart(2, '0')}$s"
        }
    }

    fun rate(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000} ${units.khz}" else String.format(Locale.US, "%.1f %s", hz / 1000.0, units.khz)

    /** `5 min @ 48 kHz MONO = ~28.8 MB RAM` (localised template) */
    fun ramLine(s: PrecordSettings): String =
        String.format(units.ramLine, duration(s.bufferSec), rate(s.sampleRate), bytes(s.ramBytes))
}
