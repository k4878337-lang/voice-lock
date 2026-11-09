package com.kai.voicelock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Always-on foreground listener.
 *
 * Fast path (instant): MFCC is computed per 10 ms frame while you speak and a streaming
 * DTW row is kept per enrolled template. As soon as the template is aligned the screen
 * locks — no waiting for silence.
 *
 * Safety path (full): when the VAD closes an utterance (150 ms silence), a full DTW +
 * pitch check runs as a fallback for slower/faster deliveries the streaming gate missed.
 */
class VoiceLockService : Service() {

    companion object {
        const val ACTION_START = "com.kai.voicelock.START"
        const val ACTION_STOP = "com.kai.voicelock.STOP"
        private const val CHANNEL_ID = "voice_lock"
        private const val NOTIF_ID = 42
        private const val COOLDOWN_MS = 1800L

        @Volatile
        var running: Boolean = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, VoiceLockService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, VoiceLockService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastTrigger = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (!Enrollment.exists(this)) {
            Toast.makeText(this, "Pehle phrase enroll karo (3 samples)", Toast.LENGTH_LONG).show()
            shutdown()
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Microphone permission nahi hai", Toast.LENGTH_LONG).show()
            shutdown()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIF_ID, buildNotification("Sun raha hoon…"))
        } catch (t: Throwable) {
            Toast.makeText(this, "Foreground service start nahi hui: ${t.message}", Toast.LENGTH_LONG).show()
            shutdown()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        if (job?.isActive != true) {
            running = true
            job = scope.launch { listen() }
        }
        return START_STICKY
    }

    private fun listen() {
        var record: AudioRecord? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                Dsp.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) throw IllegalStateException("AudioRecord buffer size error")

            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                Dsp.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, 4096)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord initialize nahi hua")
            }
            record.startRecording()

            val segmenter = Segmenter()
            val frame = ShortArray(Segmenter.FRAME_SAMPLES)
            var streamMfcc: StreamingMfcc? = null
            var streamMatcher: StreamingMatcher? = null
            var gain = 1f

            while (running) {
                var read = 0
                while (read < frame.size) {
                    val n = record.read(frame, read, frame.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < frame.size) continue

                if (!segmenter.activeNow) {
                    gain = if (Prefs.farMode(this)) 2.5f else 1f
                }
                AudioCapture.applyGain(frame, gain)

                val wasActive = segmenter.activeNow
                val utterance = segmenter.push(frame)
                val nowActive = segmenter.activeNow

                if (!wasActive && nowActive) {
                    val model = Enrollment.load(this)
                    if (model != null) {
                        streamMfcc = StreamingMfcc()
                        streamMatcher = StreamingMatcher(model, Prefs.sensitivityFactor(this))
                    }
                }

                if (nowActive && streamMfcc != null && streamMatcher != null) {
                    val feat = streamMfcc.push(frame)
                    if (feat != null && streamMatcher.push(feat)) {
                        performLock("instant", streamMatcher.threshold)
                        segmenter.reset()
                        streamMfcc = null
                        streamMatcher = null
                        continue
                    }
                }

                if (utterance != null) {
                    streamMfcc = null
                    streamMatcher = null
                    val model = Enrollment.load(this) ?: continue
                    val result = VoiceMatcher.match(model, utterance, Prefs.sensitivityFactor(this))
                    if (result.accepted) {
                        performLock("full", result.threshold, result.distance)
                    } else {
                        EventLog.add("REJECT  score ${fmt(result.distance)} / ${fmt(result.threshold)}")
                    }
                }
            }
        } catch (t: Throwable) {
            notify("Mic band ho gaya: ${t.message ?: t.javaClass.simpleName}")
            Handler(Looper.getMainLooper()).post { shutdown() }
        } finally {
            running = false
            runCatching { record?.stop() }
            runCatching { record?.release() }
        }
    }

    private fun performLock(source: String, threshold: Double, score: Double = -1.0) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrigger < COOLDOWN_MS) return
        lastTrigger = now

        Handler(Looper.getMainLooper()).post {
            val t0 = SystemClock.elapsedRealtime()
            val locked = LockController.lock(this)
            val actionMs = SystemClock.elapsedRealtime() - t0
            vibrate()
            val detail = if (score >= 0) "score ${fmt(score)}/${fmt(threshold)}" else "threshold ${fmt(threshold)}"
            if (locked) {
                Toast.makeText(this, "Locked ✅ ${actionMs}ms", Toast.LENGTH_SHORT).show()
                EventLog.add("MATCH ($source) • $detail • action ${actionMs}ms ✅")
                notify("Locked • sun raha hoon…")
            } else {
                EventLog.add("MATCH ($source) • $detail • lock FAIL (permission)")
                notify("Lock fail — Accessibility/Admin on karo")
            }
        }
    }

    private fun vibrate() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val vm = getSystemService(VibratorManager::class.java) ?: return
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
                    ?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    private fun fmt(value: Double): String = String.format(java.util.Locale.US, "%.3f", value)

    private fun shutdown() {
        running = false
        job?.cancel()
        job = null
        releaseWakeLock()
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        job?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- notification / wake lock ---

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Voice lock",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, VoiceLockService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("VoiceLock")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    private fun notify(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceLock::listening").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }
}
