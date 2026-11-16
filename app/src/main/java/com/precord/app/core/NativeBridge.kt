package com.precord.app.core

/** Raw JNI surface of libprecord_core.so (see rust-core/src/jni_bridge.rs). */
object NativeBridge {
    init {
        System.loadLibrary("precord_core")
    }

    external fun nativeCreate(sampleRate: Int, windowMs: Long): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeWrite(handle: Long, pcm: ShortArray, len: Int)
    external fun nativeSetDsp(handle: Long, gainDb: Float, gateEnabled: Boolean, gateDb: Float, highpass: Boolean, voice: Boolean)
    external fun nativeLevels(handle: Long, out: FloatArray)
    external fun nativeScope(handle: Long, out: FloatArray)
    external fun nativeBufferInfo(handle: Long, out: LongArray)
    external fun nativeSaveWav(handle: Long, path: String, lastMs: Long, normalize: Boolean, out: LongArray): Int
    external fun nativePlan(handle: Long, lastMs: Long, normalize: Boolean, out: LongArray): Int
    external fun nativeReadPcm(handle: Long, start: Long, dst: ShortArray, count: Int, gainBits: Int): Int
    external fun nativeWavInfo(path: String, out: LongArray): Int
    external fun nativeWavPeaks(path: String, out: FloatArray): Int
    external fun nativeTrimWav(src: String, dst: String, startMs: Long, endMs: Long): Int
}
