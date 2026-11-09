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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

class VoiceLockService : Service() {

    companion object {
        const val ACTION_START = "com.kai.voicelock.START"
        const val ACTION_STOP = "com.kai.voicelock.STOP"
        private const val CHANNEL_ID = "voice_lock"
        private const val NOTIF_ID = 42

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
        if (!Prefs.hasModel(this)) {
            Toast.makeText(this, "Voice model download karo pehle", Toast.LENGTH_LONG).show()
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
            // Android 14+ rejects mic-type foreground service without permission/exemption.
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
        var model: Model? = null
        var recognizer: Recognizer? = null
        try {
            model = Model(Prefs.modelDir(this).absolutePath)
            recognizer = Recognizer(model, 16000.0f)

            val minBuf = AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) throw IllegalStateException("AudioRecord buffer size error")

            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, 8192)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord initialize nahi hua")
            }
            record.startRecording()

            val buf = ShortArray(4096)
            while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) continue
                val phrases = Prefs.phrases(this)
                if (recognizer.acceptWaveForm(buf, n)) {
                    checkText(recognizer.result, phrases)
                } else {
                    checkText(recognizer.partialResult, phrases)
                }
            }
        } catch (t: Throwable) {
            notify("Voice engine band: ${t.message ?: t.javaClass.simpleName}")
            Handler(Looper.getMainLooper()).post { shutdown() }
        } finally {
            running = false
            runCatching { record?.stop() }
            runCatching { record?.release() }
            runCatching { recognizer?.close() }
            runCatching { model?.close() }
        }
    }

    private fun checkText(json: String, phrases: List<String>) {
        val text = runCatching {
            val obj = JSONObject(json)
            if (obj.has("partial")) obj.optString("partial") else obj.optString("text")
        }.getOrDefault("")
        if (text.isBlank()) return
        val hit = phrases.firstOrNull { PhraseMatcher.matches(text, it) }
        if (hit != null) trigger(hit)
    }

    private fun trigger(phrase: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrigger < 3000L) return
        lastTrigger = now

        Handler(Looper.getMainLooper()).post {
            val ok = LockController.lock(this)
            if (ok) {
                Toast.makeText(this, "Locked ✅", Toast.LENGTH_SHORT).show()
                notify("Locked — sun raha hoon…")
            } else {
                Toast.makeText(
                    this,
                    "Lock permission nahi mili — Accessibility ya Device Admin on karo",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

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
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }
}
