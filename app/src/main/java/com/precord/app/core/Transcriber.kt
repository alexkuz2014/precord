package com.precord.app.core

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TranscribeException(val languagePackMissing: Boolean, message: String) : Exception(message)

/**
 * Speech-to-text of a saved clip with the phone's *on-device* recognizer (Android 13+, no internet, no extra
 * library). The clip is decoded to PCM, cut into ~45 s pieces and streamed into the recognizer through a pipe.
 * Quality and language coverage depend on the system speech pack; speakers are not separated.
 */
object Transcriber {
    private const val SEGMENT_SEC = 45

    fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    suspend fun transcribe(context: Context, clip: File, onProgress: (done: Int, total: Int) -> Unit): String {
        val pcm = File(context.cacheDir, "transcribe.pcm")
        try {
            val rate = withContext(Dispatchers.IO) { ClipEditor.decodeToPcm(clip, pcm) }
            val bytesPerSeg = rate * 2 * SEGMENT_SEC
            val total = ((pcm.length() + bytesPerSeg - 1) / bytesPerSeg).toInt().coerceAtLeast(1)
            val out = StringBuilder()
            for (i in 0 until total) {
                onProgress(i, total)
                val chunk = withContext(Dispatchers.IO) {
                    RandomAccessFile(pcm, "r").use { f ->
                        f.seek(i.toLong() * bytesPerSeg)
                        val n = minOf(bytesPerSeg.toLong(), f.length() - i.toLong() * bytesPerSeg).toInt()
                        ByteArray(n).also { f.readFully(it) }
                    }
                }
                val text = recognize(context, chunk, rate)
                if (text.isNotBlank()) {
                    if (out.isNotEmpty()) out.append(' ')
                    out.append(text.trim())
                }
            }
            onProgress(total, total)
            return out.toString()
        } finally {
            pcm.delete()
        }
    }

    private suspend fun recognize(context: Context, pcm: ByteArray, rate: Int): String {
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writer = thread(name = "precord-stt-pipe") {
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(pcm) } }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, rate)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        try {
            return withContext(Dispatchers.Main) {
                withTimeoutOrNull(120_000L) { runRecognizer(context, intent) }
                    ?: throw TranscribeException(false, "timeout")
            }
        } finally {
            runCatching { readSide.close() }
            writer.join(1000)
        }
    }

    private suspend fun runRecognizer(context: Context, intent: Intent): String = suspendCancellableCoroutine { cont ->
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        fun done(block: () -> Unit) {
            runCatching { r.destroy() }
            if (cont.isActive) block()
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                done { cont.resume(text) }
            }

            override fun onError(error: Int) = done {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> cont.resume("")
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        cont.resumeWithException(TranscribeException(true, "language pack missing ($error)"))
                    else -> cont.resumeWithException(TranscribeException(false, "recognizer error $error"))
                }
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        cont.invokeOnCancellation { runCatching { r.destroy() } }
        r.startListening(intent)
    }
}

/** Decodes any saved clip to mono PCM16 (little endian) in [out]; returns the sample rate. */
internal fun ClipEditor.decodeToPcm(file: File, out: File): Int {
    if (ClipFormat.fromFile(file) == ClipFormat.WAV) {
        val info = LongArray(2)
        check(NativeBridge.nativeWavInfo(file.path, info) == 0) { "bad wav" }
        RandomAccessFile(file, "r").use { f ->
            f.seek(44) // Precord writes canonical 44-byte headers
            out.outputStream().use { o ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = f.read(buf); if (n <= 0) break; o.write(buf, 0, n) }
            }
        }
        return info[0].toInt()
    }
    return decodeCompressedToPcm(file, out)
}
