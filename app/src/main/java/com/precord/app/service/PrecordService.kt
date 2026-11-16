package com.precord.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import com.precord.app.core.MicMode
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.precord.app.MainActivity
import com.precord.app.R
import com.precord.app.core.ClipEncoder
import com.precord.app.core.ClipExporter
import com.precord.app.core.Fmt
import com.precord.app.core.Haptics
import com.precord.app.core.Monitor
import com.precord.app.core.PrecordSettings
import com.precord.app.core.ReplayEngine
import com.precord.app.core.SCOPE_POINTS
import com.precord.app.core.SaveEvent
import com.precord.app.precord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the microphone. AudioRecord -> (capture thread) -> Rust engine ring buffer.
 * Everything else (UI, tile, notification, shake, volume combo) just sends intents to this service.
 */
class PrecordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val saveMutex = Mutex()
    private val captureLock = Any()

    @Volatile private var engine: ReplayEngine? = null
    private var record: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var capturing = false
    private data class CaptureKey(val bufferSec: Int, val rate: Int, val mode: MicMode, val withRef: Boolean)
    private fun keyOf(s: PrecordSettings) = CaptureKey(s.bufferSec, s.sampleRate, s.micMode, s.micMode == MicMode.PLAYBACK && projection != null)

    private var captureKey: CaptureKey? = null // capture configuration currently running

    private var ticker: Job? = null
    private var settingsJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var shake: ShakeDetector? = null
    private var foregrounded = false
    private var lastNote: String? = null
    private var lastNoteAt = 0L
    private var projection: MediaProjection? = null
    private var refRecord: AudioRecord? = null
    private var refThread: Thread? = null
    private val effects = mutableListOf<android.media.audiofx.AudioEffect>()
    private var restarts = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        shake = ShakeDetector(getSystemService(Context.SENSOR_SERVICE) as SensorManager) { saveReplay(null) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ServiceController.ACTION_START, null -> startMonitoring(intent)
            ServiceController.ACTION_STOP -> stopMonitoring()
            ServiceController.ACTION_SAVE -> {
                if (engine == null) {
                    if (!foregrounded) stopSelf()
                } else {
                    val last = intent.getLongExtra(ServiceController.EXTRA_LAST_MS, -1L).takeIf { it > 0 }
                    saveReplay(last)
                }
            }
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- lifecycle

    private fun startMonitoring(intent: Intent?) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Monitor.update { it.copy(running = false, errorRes = R.string.err_permission, errorArg = null) }
            stopSelf()
            return
        }
        @Suppress("DEPRECATION")
        val projData: Intent? = intent?.getParcelableExtra(ServiceController.EXTRA_PROJ_DATA)
        if (!foregrounded || projData != null) {
            try {
                // The mediaProjection type may only be requested once the user accepted the capture dialog.
                val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    (if (projData != null || projection != null) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
                startForeground(NOTIF_ID, buildNotification(0, 1, precord.settings.value), types)
                foregrounded = true
            } catch (e: Exception) { // e.g. ForegroundServiceStartNotAllowedException when started from the background
                Monitor.update { it.copy(running = false, errorRes = R.string.err_background, errorArg = null) }
                stopSelf()
                return
            }
        }
        if (projData != null) {
            runCatching {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection?.stop()
                projection = mgr.getMediaProjection(intent.getIntExtra(ServiceController.EXTRA_PROJ_CODE, 0), projData)?.also {
                    it.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            // User ended the capture from the system chip: keep buffering, just without subtraction.
                            if (projection != null) {
                                projection = null
                                scope.launch { if (engine != null && !beginCapture(precord.settings.value)) stopMonitoring(keepError = true) }
                            }
                        }
                    }, Handler(Looper.getMainLooper()))
                }
            }
        }
        if (!beginCapture(precord.settings.value)) {
            stopMonitoring(keepError = true)
            return
        }
        precord.settings.wasArmed = true
        restarts = 0
        ticker?.cancel()
        ticker = scope.launch { tickLoop() }
        settingsJob?.cancel()
        settingsJob = scope.launch {
            precord.settings.flow.collect { applySettings(it) }
        }
    }

    /** [keepError]: leave the failure reason on screen (start failed) instead of clearing it. */
    private fun stopMonitoring(keepError: Boolean = false) {
        precord.settings.wasArmed = false
        ticker?.cancel()
        settingsJob?.cancel()
        setScreenOffTriggers(false)
        endCapture()
        projection?.let { p -> projection = null; runCatching { p.stop() } }
        Monitor.update { it.copy(needsCapture = false, cancelling = false) }
        Monitor.update { it.copy(running = false, bufferedMs = 0, rms = 0f, peak = 0f, scope = FloatArray(SCOPE_POINTS), errorRes = if (keepError) it.errorRes else null, errorArg = if (keepError) it.errorArg else null) }
        if (foregrounded) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregrounded = false
        }
        stopSelf()
    }

    /** Swiping Precord out of Recents must not stop the buffer: it lives in the foreground service. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        projection?.let { p -> projection = null; runCatching { p.stop() } }
        ticker?.cancel()
        settingsJob?.cancel()
        shake?.stop()
        endCapture()
        wakeLock?.let { if (it.isHeld) it.release() }
        Monitor.update { it.copy(running = false) }
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- capture

    /** Starts AudioRecord + the Rust ring buffer. Returns false (and publishes the reason) on failure. */
    private fun beginCapture(s: PrecordSettings): Boolean = synchronized(captureLock) {
        endCapture()
        val eng = ReplayEngine.create(s.sampleRate, s.windowMs)
        if (eng == null) {
            Monitor.update { it.copy(running = false, errorRes = R.string.err_memory, errorArg = Fmt.bytes(s.ramBytes)) }
            return false
        }
        val minBuf = AudioRecord.getMinBufferSize(s.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val block = s.sampleRate / 25 // 40 ms reads
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(audioSource(s))
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(s.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf * 2, block * 2 * 4))
                .build()
        } catch (e: Exception) {
            eng.close()
            Monitor.update { it.copy(running = false, errorRes = R.string.err_mic_unavailable, errorArg = null) }
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            eng.close()
            Monitor.update { it.copy(running = false, errorRes = R.string.err_mic_busy, errorArg = null) }
            return false
        }
        attachEffects(rec, s)
        eng.setDsp(s)
        engine = eng
        record = rec
        captureKey = keyOf(s)
        val ref = if (s.micMode == MicMode.PLAYBACK) openPlaybackCapture(s) else null
        refRecord = ref
        Monitor.update { it.copy(needsCapture = s.micMode == MicMode.PLAYBACK && ref == null, cancelling = ref != null) }
        val refRing = if (ref != null) ReferenceRing(s.sampleRate, s.sampleRate / 25) else null
        capturing = true
        rec.startRecording()
        ref?.startRecording()
        refThread = if (ref != null && refRing != null) Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val tmp = ShortArray(block)
            var failures = 0
            while (capturing) {
                val r = ref.read(tmp, 0, tmp.size)
                if (r > 0) { refRing.push(tmp, r); failures = 0 }
                else if (r < 0 && ++failures > 50) break // capture was revoked; the mic keeps buffering without subtraction
                else if (r == 0) Thread.sleep(5)
            }
        }, "precord-playback").also { it.start() } else null
        captureThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(block)
            val refBuf = ShortArray(block)
            while (capturing) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                // The Rust canceller needs a reference for every block; the FIFO pads gaps with silence.
                if (refRing != null) { refRing.pull(refBuf, n); eng.writeRef(buf, refBuf, n) } else eng.write(buf, n)
            }
            if (capturing && engine === eng) {
                // Read error (mic taken by the system / device switch): re-open the microphone instead of going deaf.
                scope.launch {
                    delay(1000)
                    if (engine === eng && ++restarts <= 5) {
                        if (!beginCapture(precord.settings.value)) stopMonitoring(keepError = true)
                    } else if (engine === eng) stopMonitoring(keepError = true)
                }
            }
        }, "precord-capture").also { it.start() }
        Monitor.update { it.copy(running = true, errorRes = null, errorArg = null, windowMs = s.windowMs, sampleRate = s.sampleRate, bufferedMs = 0) }
        return true
    }

    /** Platform noise suppressor / echo canceller (present on most, not all, phones). */
    private fun attachEffects(rec: AudioRecord, s: PrecordSettings) {
        if (s.micMode != MicMode.DENOISE) return
        runCatching {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true; effects += it }
        }
    }

    /** UNPROCESSED bypasses the phone's AGC / noise suppression where the device supports it. */
    private fun audioSource(s: PrecordSettings): Int {
        if (s.micMode == MicMode.RAW) {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") return MediaRecorder.AudioSource.UNPROCESSED
        }
        return MediaRecorder.AudioSource.MIC
    }

    /**
     * Second recorder that receives exactly what the phone is playing (media / games), at the mic's
     * sample rate. Rust uses it as the reference to subtract from the microphone.
     */
    private fun openPlaybackCapture(s: PrecordSettings): AudioRecord? {
        val p = projection ?: return null
        return runCatching {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(s.sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            val min = AudioRecord.getMinBufferSize(s.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord.Builder().setAudioPlaybackCaptureConfig(cfg).setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(min * 2, s.sampleRate / 25 * 2 * 8)).build()
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        }.getOrNull()
    }

    private fun endCapture() = synchronized(captureLock) {
        capturing = false
        runCatching { record?.stop() }
        captureThread?.join(1000)
        captureThread = null
        runCatching { refRecord?.stop() }
        refThread?.join(500)
        refThread = null
        refRecord?.release()
        refRecord = null
        effects.forEach { runCatching { it.release() } }
        effects.clear()
        record?.release()
        record = null
        engine?.close()
        engine = null
        captureKey = null
    }

    private fun applySettings(s: PrecordSettings) {
        val eng = engine ?: return
        eng.setDsp(s)
        setScreenOffTriggers(s.shakeToSave)
        if (captureKey != keyOf(s)) {
            // Different buffer length / sample rate needs a fresh ring (the old audio cannot be reinterpreted).
            if (!beginCapture(s)) stopMonitoring(keepError = true)
        }
    }

    /** The partial wake lock (battery cost) is only held while shake-to-save needs the sensor with the screen off. */
    private fun setScreenOffTriggers(shakeOn: Boolean) {
        if (shakeOn) {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "precord:shake").apply { setReferenceCounted(false) }
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire()
            shake?.start()
        } else {
            shake?.stop()
            wakeLock?.let { if (it.isHeld) it.release() }
        }
    }

    // ---------------------------------------------------------------- periodic UI / notification update

    private suspend fun tickLoop() {
        var n = 0
        val scopeBuf = FloatArray(SCOPE_POINTS)
        while (scope.isActive) {
            val eng = engine
            val visible = Monitor.uiVisible.value
            if (eng != null) {
                val buffered = eng.bufferedMs()
                if (visible) {
                    // UI on screen: 25 Hz meters + scope.
                    val (rms, peak) = eng.levels()
                    eng.scope(scopeBuf)
                    Monitor.update { it.copy(running = true, bufferedMs = buffered, windowMs = eng.windowMs, sampleRate = eng.sampleRate, rms = rms, peak = peak, scope = scopeBuf.copyOf()) }
                } else if (n % 5 == 0) {
                    // Background: 1 Hz bookkeeping only (tile subtitle, notification) - saves CPU and battery.
                    Monitor.update { it.copy(running = true, bufferedMs = buffered, windowMs = eng.windowMs, sampleRate = eng.sampleRate) }
                }
                if (n % 600 == 0) restarts = 0 // a stable minute earns the retry budget back
                if (n % (if (visible) 25 else 5) == 0) {
                    updateNotification(buffered, eng.windowMs)
                }
            }
            n++
            delay(if (visible) 40L else 200L)
        }
    }

    // ---------------------------------------------------------------- saving

    /** Saves the newest [lastMs] (or the whole buffer when null) without interrupting capture. */
    fun saveReplay(lastMs: Long?) {
        val eng = engine ?: return
        val settings = precord.settings.value
        scope.launch(Dispatchers.IO) {
            if (!saveMutex.tryLock()) return@launch // a save is already running
            try {
                Monitor.update { it.copy(saving = true) }
                val ask = lastMs?.coerceAtMost(eng.windowMs) ?: eng.windowMs
                val target = precord.clips.newFile(settings.format)
                val out = ClipEncoder.encode(eng, settings, target, ask)
                val clip = precord.clips.register(out.file, out.durationMs, eng.sampleRate)
                val exportFailed = settings.autoExport && ClipExporter.exportToRecordings(this@PrecordService, out.file) == null
                Haptics.thud(this@PrecordService)
                lastNote = getString(R.string.notif_saved, Fmt.clock(clip.durationMs), clip.file.name)
                lastNoteAt = android.os.SystemClock.elapsedRealtime()
                Monitor.emit(SaveEvent.Saved(clip, out.fellBackFrom, exportFailed))
                updateNotification(eng.bufferedMs(), eng.windowMs)
            } catch (e: Throwable) {
                Monitor.emit(SaveEvent.Failed(getString(R.string.err_save_hint)))
                lastNote = getString(R.string.notif_save_failed)
                lastNoteAt = android.os.SystemClock.elapsedRealtime()
            } finally {
                Monitor.update { it.copy(saving = false) }
                saveMutex.unlock()
            }
        }
    }

    // ---------------------------------------------------------------- notification

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, getString(R.string.channel_monitor), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun updateNotification(bufferedMs: Long, windowMs: Long) {
        if (!foregrounded) return
        val s = precord.settings.value
        val n = buildNotification(bufferedMs, windowMs, s)
        getSystemService(NotificationManager::class.java)!!.notify(NOTIF_ID, n)
    }

    private fun buildNotification(bufferedMs: Long, windowMs: Long, s: PrecordSettings): Notification {
        fun action(code: Int, label: String, act: String) = NotificationCompat.Action.Builder(
            0, label,
            PendingIntent.getService(this, code, ServiceController.intent(this, act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        ).build()

        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // a save/failure note replaces the buffer line for 10 s only
        if (lastNote != null && android.os.SystemClock.elapsedRealtime() - lastNoteAt > 10_000) lastNote = null
        val line = "${Fmt.clock(bufferedMs)} / ${Fmt.clock(windowMs)}  -  ${Fmt.rate(s.sampleRate)}  ${s.format.label}"
        return NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(lastNote ?: line)
            .setSubText(if (lastNote != null) line else null)
            .setProgress(windowMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1), bufferedMs.coerceAtMost(windowMs).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            // Android 16+: "Live Update" - the system shows a chip with this text in the status bar.
            .addExtras(Bundle().apply {
                putBoolean("android.requestPromotedOngoing", true)
                putCharSequence("android.shortCriticalText", Fmt.clock(bufferedMs))
            })
            .addAction(action(1, getString(R.string.action_save), ServiceController.ACTION_SAVE))
            .addAction(action(2, getString(R.string.action_stop), ServiceController.ACTION_STOP))
            .build()
    }

    companion object {
        private const val CHANNEL_MONITOR = "monitor"
        private const val NOTIF_ID = 4201
    }
}
