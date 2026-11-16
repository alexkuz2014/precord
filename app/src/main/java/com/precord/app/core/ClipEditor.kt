package com.precord.app.core

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Waveform extraction and lossless start/end trimming for saved clips. Blocking: call off the main thread. */
object ClipEditor {

    /** Peak amplitude (0..1) for [buckets] equal time slices. */
    fun peaks(file: File, buckets: Int): FloatArray {
        if (ClipFormat.fromFile(file) == ClipFormat.WAV) {
            val out = FloatArray(buckets)
            if (NativeBridge.nativeWavPeaks(file.path, out) == 0) return out
        }
        return runCatching { decodePeaks(file, buckets) }.getOrDefault(FloatArray(buckets))
    }

    /** Cuts [startMs, endMs) into [dst]; WAV is trimmed in Rust, M4A/OGG are re-muxed without re-encoding. */
    fun trim(src: File, dst: File, startMs: Long, endMs: Long): Boolean {
        if (endMs <= startMs) return false
        val ok = runCatching {
            if (ClipFormat.fromFile(src) == ClipFormat.WAV) {
                NativeBridge.nativeTrimWav(src.path, dst.path, startMs, endMs) == 0
            } else {
                remux(src, dst, startMs, endMs)
                true
            }
        }.getOrDefault(false)
        if (!ok) dst.delete()
        return ok
    }

    internal fun audioTrack(ex: MediaExtractor): Int =
        (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: error("no audio track")

    private fun remux(src: File, dst: File, startMs: Long, endMs: Long) {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            ex.setDataSource(src.path)
            val t = audioTrack(ex)
            ex.selectTrack(t)
            val fmt = ex.getTrackFormat(t)
            val outFmt = if (ClipFormat.fromFile(dst) == ClipFormat.M4A) MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            else MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
            muxer = MediaMuxer(dst.path, outFmt)
            val track = muxer.addTrack(fmt)
            muxer.start()
            started = true
            val startUs = startMs * 1000
            val endUs = endMs * 1000
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buf = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val ts = ex.sampleTime
                if (ts > endUs) break
                if (ts >= startUs) {
                    info.set(0, size, ts - startUs, ex.sampleFlags)
                    muxer.writeSampleData(track, buf, info)
                }
                ex.advance()
            }
        } finally {
            ex.release()
            if (started) runCatching { muxer?.stop() }
            muxer?.release()
        }
    }

    /** MediaCodec decode of M4A / Ogg-Opus to mono PCM16 LE. Returns the sample rate. */
    internal fun decodeCompressedToPcm(file: File, out: File): Int {
        val ex = MediaExtractor()
        ex.setDataSource(file.path)
        val t = audioTrack(ex)
        ex.selectTrack(t)
        val fmt = ex.getTrackFormat(t)
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        try {
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            out.outputStream().buffered().use { os ->
                while (!outDone) {
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        ch = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (o >= 0) {
                        val bb = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        bb.position(info.offset); bb.limit(info.offset + info.size)
                        val sb = bb.asShortBuffer()
                        val frame = ByteArray(2)
                        var i = 0
                        while (i < sb.remaining()) {
                            val v = sb.get(i).toInt()
                            frame[0] = (v and 0xFF).toByte(); frame[1] = ((v shr 8) and 0xFF).toByte()
                            os.write(frame)
                            i += ch // keep the first channel only
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
        return rate
    }

    private fun decodePeaks(file: File, buckets: Int): FloatArray {
        val out = FloatArray(buckets)
        val ex = MediaExtractor()
        ex.setDataSource(file.path)
        val t = audioTrack(ex)
        ex.selectTrack(t)
        val fmt = ex.getTrackFormat(t)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val durUs = (if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 1L).coerceAtLeast(1L)
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        val codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val b = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val sb = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val shorts = info.size / 2
                    if (shorts > 0) {
                        sb.position(info.offset / 2)
                        val bufDurUs = shorts.toLong() * 1_000_000L / (rate.toLong() * ch)
                        val slices = 8
                        for (k in 0 until slices) {
                            var peak = 0
                            val from = shorts * k / slices
                            val to = shorts * (k + 1) / slices
                            for (j in from until to) peak = maxOf(peak, kotlin.math.abs(sb.get(info.offset / 2 + j).toInt()))
                            val ts = info.presentationTimeUs + bufDurUs * k / slices
                            val bi = (ts * buckets / durUs).toInt().coerceIn(0, buckets - 1)
                            val v = peak / 32768f
                            if (v > out[bi]) out[bi] = v
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
        return out
    }
}
