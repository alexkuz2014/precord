package com.precord.app.core

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder

data class EncodeOutcome(val file: File, val durationMs: Long, val fellBackFrom: ClipFormat? = null)

/**
 * Turns a slice of the Rust ring buffer into a file.
 *  - WAV  : written entirely in Rust (streamed, zero extra RAM).
 *  - M4A / OGG-Opus : Rust hands out DSP-processed (normalised) PCM in 4096-sample chunks,
 *    which are encoded by the platform MediaCodec and muxed with MediaMuxer.
 */
object ClipEncoder {

    fun encode(engine: ReplayEngine, settings: PrecordSettings, target: File, lastMs: Long): EncodeOutcome {
        val format = ClipFormat.fromFile(target)
        if (format == ClipFormat.WAV) return wav(engine, settings, target, lastMs)
        val plan = engine.plan(lastMs, settings.normalize) ?: error("Buffer is empty")
        return try {
            compressed(engine, plan, format, target)
            EncodeOutcome(target, plan.durationMs)
        } catch (t: Throwable) {
            // Device without that encoder (or codec failure): keep the audio, fall back to lossless.
            target.delete()
            val fallback = File(target.parentFile, target.nameWithoutExtension + ".wav")
            val out = wav(engine, settings, fallback, lastMs)
            out.copy(fellBackFrom = format)
        }
    }

    private fun wav(engine: ReplayEngine, settings: PrecordSettings, target: File, lastMs: Long): EncodeOutcome {
        val r = engine.saveWav(target.path, lastMs, settings.normalize) ?: error("Could not write WAV (buffer empty or disk full)")
        return EncodeOutcome(target, r.durationMs)
    }

    private fun compressed(engine: ReplayEngine, plan: SavePlan, format: ClipFormat, target: File) {
        val aac = format == ClipFormat.M4A
        val mime = if (aac) MediaFormat.MIMETYPE_AUDIO_AAC else MediaFormat.MIMETYPE_AUDIO_OPUS
        val rate = plan.sampleRate
        val mf = MediaFormat.createAudioFormat(mime, rate, 1).apply {
            val low = rate <= 16_000
            setInteger(MediaFormat.KEY_BIT_RATE, if (aac) (if (low) 48_000 else 96_000) else (if (low) 24_000 else 40_000))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            if (aac) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        val codec = MediaCodec.createEncoderByType(mime)
        val muxer = MediaMuxer(
            target.path,
            if (aac) MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 else MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG,
        )
        var muxerStarted = false
        try {
            codec.configure(mf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val chunk = ShortArray(4096)
            var pos = 0L
            var inputDone = false
            var track = -1
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        val n = minOf(plan.count - pos, chunk.size.toLong(), (buf.capacity() / 2).toLong()).toInt()
                        val pts = pos * 1_000_000L / rate
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            check(engine.readPcm(plan, pos, chunk, n)) { "buffer overrun" }
                            buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(chunk, 0, n)
                            codec.queueInputBuffer(i, 0, n * 2, pts, 0)
                            pos += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    o >= 0 -> {
                        val ob = codec.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            muxer.writeSampleData(track, ob, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }
}
