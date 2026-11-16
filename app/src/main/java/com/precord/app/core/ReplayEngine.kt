package com.precord.app.core

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** A planned save: which samples of the ring buffer, and the normalisation gain. */
class SavePlan(val start: Long, val count: Long, val gainBits: Int, val sampleRate: Int) {
    val durationMs: Long get() = count * 1000L / sampleRate
}

data class SaveResult(val samples: Long, val durationMs: Long)

/**
 * Safe Kotlin facade over one native engine instance. All calls take a shared lock so
 * [close] can never free the native buffer under a running capture/save call.
 */
class ReplayEngine private constructor(
    private var handle: Long,
    val sampleRate: Int,
    val windowMs: Long,
) : AutoCloseable {

    private val lock = ReentrantReadWriteLock()
    private val levelsOut = FloatArray(2)
    private val infoOut = LongArray(2)

    companion object {
        /** Returns null when the buffer cannot be allocated (not enough memory). */
        fun create(sampleRate: Int, windowMs: Long): ReplayEngine? {
            val h = NativeBridge.nativeCreate(sampleRate, windowMs)
            return if (h == 0L) null else ReplayEngine(h, sampleRate, windowMs)
        }
    }

    private inline fun <T> withHandle(default: T, block: (Long) -> T): T = lock.read {
        if (handle == 0L) default else block(handle)
    }

    fun write(pcm: ShortArray, len: Int) = withHandle(Unit) { NativeBridge.nativeWrite(it, pcm, len) }

    fun setDsp(gainDb: Float, gateEnabled: Boolean, gateDb: Float) =
        withHandle(Unit) { NativeBridge.nativeSetDsp(it, gainDb, gateEnabled, gateDb) }

    /** (rms, peak) linear 0..1 of the latest 20 ms. Not thread-safe across callers (UI ticker only). */
    fun levels(): Pair<Float, Float> = withHandle(0f to 0f) {
        NativeBridge.nativeLevels(it, levelsOut)
        levelsOut[0] to levelsOut[1]
    }

    fun scope(out: FloatArray) = withHandle(Unit) { NativeBridge.nativeScope(it, out) }

    /** Buffered milliseconds (<= windowMs). */
    fun bufferedMs(): Long = withHandle(0L) {
        NativeBridge.nativeBufferInfo(it, infoOut)
        infoOut[0] * 1000L / sampleRate
    }

    fun saveWav(path: String, lastMs: Long, normalize: Boolean): SaveResult? = withHandle(null) {
        val out = LongArray(2)
        if (NativeBridge.nativeSaveWav(it, path, lastMs, normalize, out) == 0) SaveResult(out[0], out[1]) else null
    }

    fun plan(lastMs: Long, normalize: Boolean): SavePlan? = withHandle(null) {
        val out = LongArray(3)
        if (NativeBridge.nativePlan(it, lastMs, normalize, out) == 0)
            SavePlan(out[0], out[1], out[2].toInt(), sampleRate)
        else null
    }

    /** Reads [count] samples at [offset] into [dst] (gain applied). Returns false if overwritten. */
    fun readPcm(plan: SavePlan, offset: Long, dst: ShortArray, count: Int): Boolean = withHandle(false) {
        NativeBridge.nativeReadPcm(it, plan.start + offset, dst, count, plan.gainBits) == count
    }

    override fun close() {
        lock.write {
            if (handle != 0L) {
                NativeBridge.nativeDestroy(handle)
                handle = 0L
            }
        }
    }
}
