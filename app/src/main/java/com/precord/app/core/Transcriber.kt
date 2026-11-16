package com.precord.app.core

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
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

/** State of the offline speech pack for one language. */
enum class PackState { INSTALLED, DOWNLOADING, DOWNLOADABLE, UNSUPPORTED, UNKNOWN }

/**
 * Speech-to-text of a saved clip with the phone's recognizer (Android 13+). Default is the *on-device*
 * engine (no internet); its language packs can be checked and downloaded from here. Optionally the
 * system's online recognizer can be used when a language has no offline pack.
 */
object Transcriber {
    private const val SEGMENT_SEC = 45

    /** Languages offered in Settings ("" = follow the system language). */
    val LANGUAGES = listOf("", "en-US", "ru-RU", "uk-UA", "de-DE", "es-ES", "fr-FR", "pl-PL")

    fun tag(settings: PrecordSettings): String = settings.sttLang.ifBlank { Locale.getDefault().toLanguageTag() }

    fun isSupported(context: Context): Boolean = Build.VERSION.SDK_INT >= 33 &&
        (SpeechRecognizer.isOnDeviceRecognitionAvailable(context) || SpeechRecognizer.isRecognitionAvailable(context))

    fun hasOnDevice(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun intentFor(tag: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)

    private fun matches(list: List<String>, tag: String): Boolean {
        val lang = tag.substringBefore('-').lowercase()
        return list.any { it.equals(tag, true) } || list.any { it.substringBefore('-').lowercase() == lang }
    }

    /** Asks the on-device recognizer whether [tag] is installed / downloadable. */
    suspend fun packState(context: Context, tag: String): PackState {
        if (!hasOnDevice(context)) return PackState.UNSUPPORTED
        if (Build.VERSION.SDK_INT < 33) return PackState.UNSUPPORTED
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(8000L) {
                suspendCancellableCoroutine { cont ->
                    val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    fun finish(s: PackState) { runCatching { r.destroy() }; if (cont.isActive) cont.resume(s) }
                    cont.invokeOnCancellation { runCatching { r.destroy() } }
                    r.checkRecognitionSupport(intentFor(tag), context.mainExecutor, object : RecognitionSupportCallback {
                        override fun onSupportResult(s: RecognitionSupport) = finish(
                            when {
                                matches(s.installedOnDeviceLanguages, tag) -> PackState.INSTALLED
                                matches(s.pendingOnDeviceLanguages, tag) -> PackState.DOWNLOADING
                                matches(s.supportedOnDeviceLanguages, tag) -> PackState.DOWNLOADABLE
                                else -> PackState.UNSUPPORTED
                            },
                        )

                        override fun onError(error: Int) = finish(PackState.UNKNOWN)
                    })
                }
            } ?: PackState.UNKNOWN
        }
    }

    /**
     * Starts the download of the offline pack for [tag]. Android 14+: suspends until it finished and
     * reports progress (true = installed). Android 13: the system shows its own download prompt and
     * this returns false immediately ("scheduled").
     */
    suspend fun downloadPack(context: Context, tag: String, onProgress: (Int) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < 33 || !hasOnDevice(context)) return false
        return withContext(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= 34) {
                withTimeoutOrNull(10 * 60_000L) {
                    suspendCancellableCoroutine { cont ->
                        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                        fun finish(ok: Boolean) { runCatching { r.destroy() }; if (cont.isActive) cont.resume(ok) }
                        cont.invokeOnCancellation { runCatching { r.destroy() } }
                        r.triggerModelDownload(intentFor(tag), context.mainExecutor, object : ModelDownloadListener {
                            override fun onProgress(completedPercent: Int) = onProgress(completedPercent)
                            override fun onSuccess() = finish(true)
                            override fun onScheduled() = finish(false)
                            override fun onError(error: Int) = finish(false)
                        })
                    }
                } ?: false
            } else {
                val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                runCatching { r.triggerModelDownload(intentFor(tag)) }
                runCatching { r.destroy() }
                false
            }
        }
    }

    suspend fun transcribe(context: Context, clip: File, tag: String, online: Boolean, onProgress: (done: Int, total: Int) -> Unit): String {
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
                val text = recognize(context, chunk, rate, tag, online)
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

    private suspend fun recognize(context: Context, pcm: ByteArray, rate: Int, tag: String, online: Boolean): String {
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writer = thread(name = "precord-stt-pipe") {
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(pcm) } }
        }
        val intent = intentFor(tag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, rate)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        if (!online) intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        try {
            return withContext(Dispatchers.Main) {
                withTimeoutOrNull(120_000L) { runRecognizer(context, intent, online) }
                    ?: throw TranscribeException(false, "timeout")
            }
        } finally {
            runCatching { readSide.close() }
            writer.join(1000)
        }
    }

    private suspend fun runRecognizer(context: Context, intent: Intent, online: Boolean): String = suspendCancellableCoroutine { cont ->
        val r = if (online) SpeechRecognizer.createSpeechRecognizer(context) else SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
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
