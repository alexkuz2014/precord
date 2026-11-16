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
import android.media.AudioRecord
import android.media.MediaRecorder
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
    private var captureKey: Pair<Int, Int>? = null // bufferSec to sampleRate currently running

    private var ticker: Job? = null
    private var settingsJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var shake: ShakeDetector? = null
    private var foregrounded = false
    private var lastNote: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        shake = ShakeDetector(getSystemService(Context.SENSOR_SERVICE) as SensorManager) { saveReplay(null) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ServiceController.ACTION_START, null -> startMonitoring()
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

    private fun startMonitoring() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Monitor.update { it.copy(running = false, error = "Microphone permission missing") }
            stopSelf()
            return
        }
        if (!foregrounded) {
            try {
                startForeground(NOTIF_ID, buildNotification(0, 1, precord.settings.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                foregrounded = true
            } catch (e: Exception) { // e.g. ForegroundServiceStartNotAllowedException when started from the background
                Monitor.update { it.copy(running = false, error = "Open Precord once to arm the buffer") }
                stopSelf()
                return
            }
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "precord:capture").apply { setReferenceCounted(false) }
        wakeLock?.acquire()

        if (!beginCapture(precord.settings.value)) {
            stopMonitoring()
            return
        }
        ticker?.cancel()
        ticker = scope.launch { tickLoop() }
        settingsJob?.cancel()
        settingsJob = scope.launch {
            precord.settings.flow.collect { applySettings(it) }
        }
    }

    private fun stopMonitoring() {
        ticker?.cancel()
        settingsJob?.cancel()
        shake?.stop()
        endCapture()
        wakeLock?.let { if (it.isHeld) it.release() }
        Monitor.update { it.copy(running = false, bufferedMs = 0, rms = 0f, peak = 0f, scope = FloatArray(SCOPE_POINTS)) }
        if (foregrounded) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregrounded = false
        }
        stopSelf()
    }

    override fun onDestroy() {
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
            Monitor.update { it.copy(running = false, error = "Not enough memory for ${Fmt.bytes(s.ramBytes)} buffer") }
            return false
        }
        val minBuf = AudioRecord.getMinBufferSize(s.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val block = s.sampleRate / 25 // 40 ms reads
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
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
            Monitor.update { it.copy(running = false, error = "Microphone unavailable") }
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            eng.close()
            Monitor.update { it.copy(running = false, error = "Microphone is in use by another app") }
            return false
        }
        eng.setDsp(s.gainDb.toFloat(), s.gateEnabled, s.gateDb.toFloat())
        engine = eng
        record = rec
        captureKey = s.bufferSec to s.sampleRate
        capturing = true
        rec.startRecording()
        captureThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(block)
            while (capturing) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) eng.write(buf, n) else if (n < 0) break
            }
        }, "precord-capture").also { it.start() }
        Monitor.update { it.copy(running = true, error = null, windowMs = s.windowMs, sampleRate = s.sampleRate, bufferedMs = 0) }
        return true
    }

    private fun endCapture() = synchronized(captureLock) {
        capturing = false
        runCatching { record?.stop() }
        captureThread?.join(1000)
        captureThread = null
        record?.release()
        record = null
        engine?.close()
        engine = null
        captureKey = null
    }

    private fun applySettings(s: PrecordSettings) {
        val eng = engine ?: return
        eng.setDsp(s.gainDb.toFloat(), s.gateEnabled, s.gateDb.toFloat())
        if (s.shakeToSave) shake?.start() else shake?.stop()
        if (captureKey != (s.bufferSec to s.sampleRate)) {
            // Different buffer length / sample rate needs a fresh ring (the old audio cannot be reinterpreted).
            if (!beginCapture(s)) stopMonitoring()
        }
    }

    // ---------------------------------------------------------------- periodic UI / notification update

    private suspend fun tickLoop() {
        var n = 0
        val scopeBuf = FloatArray(SCOPE_POINTS)
        while (scope.isActive) {
            val eng = engine
            if (eng != null) {
                val (rms, peak) = eng.levels()
                eng.scope(scopeBuf)
                val buffered = eng.bufferedMs()
                Monitor.update { it.copy(running = true, bufferedMs = buffered, windowMs = eng.windowMs, sampleRate = eng.sampleRate, rms = rms, peak = peak, scope = scopeBuf.copyOf()) }
                if (n % 30 == 0) updateNotification(buffered, eng.windowMs)
            }
            n++
            delay(33)
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
                var note = out.note
                if (settings.autoExport && ClipExporter.exportToRecordings(this@PrecordService, out.file) == null) {
                    note = (note?.plus("; ") ?: "") + "export to Recordings failed"
                }
                Haptics.thud(this@PrecordService)
                lastNote = "Saved ${Fmt.clock(clip.durationMs)} - ${clip.file.name}"
                Monitor.emit(SaveEvent.Saved(clip, note))
                updateNotification(eng.bufferedMs(), eng.windowMs)
            } catch (e: Throwable) {
                Monitor.emit(SaveEvent.Failed(e.message ?: "Save failed"))
                lastNote = "Save failed"
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
        val line = "${Fmt.clock(bufferedMs)} / ${Fmt.clock(windowMs)}  -  ${Fmt.rate(s.sampleRate)}  ${s.format.label}"
        return NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("PRECORD  -  ARMED")
            .setContentText(lastNote ?: line)
            .setSubText(if (lastNote != null) line else null)
            .setProgress(windowMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1), bufferedMs.coerceAtMost(windowMs).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setColor(0xFFFF3B00.toInt())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(action(1, "SAVE REPLAY", ServiceController.ACTION_SAVE))
            .addAction(action(2, "STOP", ServiceController.ACTION_STOP))
            .build()
    }

    companion object {
        private const val CHANNEL_MONITOR = "monitor"
        private const val NOTIF_ID = 4201
    }
}
