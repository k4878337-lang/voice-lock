package com.kai.voicelock

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var enrollStatus: TextView
    private lateinit var sensitivityLabel: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnTest: Button
    private lateinit var btnClear: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var sensitivityBar: SeekBar
    private lateinit var farSwitch: SwitchCompat
    private lateinit var eventLog: TextView

    private val samples = mutableListOf<Array<DoubleArray>>()
    private val pitches = mutableListOf<Double>()
    private var model: Enrollment.Model? = null
    private var busy = false
    private var afterPermission: (() -> Unit)? = null

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) afterPermission?.invoke() else toast("Microphone permission chahiye")
            afterPermission = null
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startNow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        enrollStatus = findViewById(R.id.enrollStatus)
        sensitivityLabel = findViewById(R.id.sensitivityLabel)
        btnRecord = findViewById(R.id.btnRecord)
        btnTest = findViewById(R.id.btnTest)
        btnClear = findViewById(R.id.btnClear)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        sensitivityBar = findViewById(R.id.sensitivityBar)
        farSwitch = findViewById(R.id.farSwitch)
        eventLog = findViewById(R.id.eventLog)

        farSwitch.isChecked = Prefs.farMode(this)
        farSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setFarMode(this, checked)
            toast(if (checked) "Far mode ON — gain 2.5x" else "Far mode OFF")
        }

        sensitivityBar.progress = Prefs.sensitivityProgress(this)
        sensitivityLabel.text = sensitivityText(sensitivityBar.progress)
        sensitivityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                sensitivityLabel.text = sensitivityText(progress)
                if (fromUser) Prefs.setSensitivityProgress(this@MainActivity, progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        btnRecord.setOnClickListener {
            withMic {
                if (busy) return@withMic
                if (model != null) {
                    Enrollment.clear(this)
                    model = null
                }
                if (samples.size >= 3) {
                    samples.clear()
                    pitches.clear()
                }
                recordSample()
            }
        }

        btnTest.setOnClickListener {
            val m = model
            if (m == null) {
                toast("Pehle 3 samples enroll karo")
                return@setOnClickListener
            }
            withMic { testPhrase(m) }
        }

        btnClear.setOnClickListener {
            Enrollment.clear(this)
            model = null
            samples.clear()
            pitches.clear()
            updateUi()
            toast("Enrollment clear ho gaya")
        }

        btnStart.setOnClickListener {
            if (model == null) {
                toast("Pehle 3 samples record karo")
                return@setOnClickListener
            }
            withMic { ensureNotificationThenStart() }
        }

        btnStop.setOnClickListener {
            Prefs.setWantsRunning(this, false)
            VoiceLockService.stop(this)
            toast("Listening band")
            updateUi()
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            toast("Settings me VoiceLock dhoondh ke on karo")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnAdmin).setOnClickListener { toggleAdmin() }

        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            val pm = getSystemService(PowerManager::class.java)
            if (pm?.isIgnoringBatteryOptimizations(packageName) == true) {
                toast("Already exempt hai")
            } else {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }.onFailure { toast("Settings me manually exempt karo") }
            }
        }

        model = Enrollment.load(this)
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        model = Enrollment.load(this)
        updateUi()
        updateEventLog()
    }

    private fun updateEventLog() {
        val lines = EventLog.snapshot()
        eventLog.text = if (lines.isEmpty()) {
            "Abhi koi event nahi — Start listening ke baad yahan dikhega"
        } else {
            lines.joinToString("\n")
        }
    }

    // --- enrollment ---

    private fun recordSample() {
        if (busy) return
        busy = true
        updateUi()
        enrollStatus.text = "Ab bolo… (sample ${samples.size + 1}/3)"

        lifecycleScope.launch {
            val pcm = withContext(Dispatchers.IO) {
                AudioCapture.recordPhrase(gain = if (Prefs.farMode(this@MainActivity)) 2.5f else 1f)
            }
            if (pcm == null) {
                busy = false
                updateUi()
                enrollStatus.text = "Kuch sunai nahi diya — phir bolo"
                return@launch
            }

            val features = withContext(Dispatchers.Default) { VoiceMatch.prepare(pcm) }
            val pitch = withContext(Dispatchers.Default) { VoiceMatch.pitchMean(pcm) }

            if (features == null) {
                busy = false
                updateUi()
                enrollStatus.text = "Clip bahut chhoti thi — poora phrase bolo"
                return@launch
            }

            samples.add(features)
            if (pitch != null) pitches.add(pitch)
            busy = false

            if (samples.size >= 3) finishEnrollment() else updateUi()
        }
    }

    private fun finishEnrollment() {
        var worst = 0.0
        for (i in samples.indices) {
            for (j in i + 1 until samples.size) {
                worst = max(worst, VoiceMatch.distance(samples[i], samples[j]))
            }
        }
        val threshold = (worst * 1.5).coerceIn(0.18, 0.55)
        val meanPitch = if (pitches.size >= 2) pitches.average() else 0.0
        val variance = if (pitches.size >= 2) {
            pitches.sumOf { (it - meanPitch) * (it - meanPitch) } / pitches.size
        } else {
            0.0
        }

        val newModel = Enrollment.Model(samples.toList(), threshold, meanPitch, sqrt(variance))
        Enrollment.save(this, newModel)
        model = newModel
        samples.clear()
        pitches.clear()
        toast("Enrolled ✅ ab test karo")
        updateUi()
    }

    private fun testPhrase(m: Enrollment.Model) {
        if (busy) return
        busy = true
        updateUi()
        enrollStatus.text = "Test — ab bolo…"

        lifecycleScope.launch {
            val pcm = withContext(Dispatchers.IO) {
                AudioCapture.recordPhrase(gain = if (Prefs.farMode(this@MainActivity)) 2.5f else 1f)
            }
            val result = if (pcm == null) {
                null
            } else {
                withContext(Dispatchers.Default) {
                    VoiceMatcher.match(m, pcm, Prefs.sensitivityFactor(this@MainActivity))
                }
            }
            busy = false
            updateUi()
            enrollStatus.text = if (result == null) {
                "Kuch sunai nahi diya — phir test karo"
            } else {
                "Test: ${if (result.accepted) "MATCH ✅" else "no match ❌"}  •  score ${fmt(result.distance)} / limit ${fmt(result.threshold)}"
            }
        }
    }

    // --- service / permissions ---

    private fun ensureNotificationThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startNow()
        }
    }

    private fun startNow() {
        Prefs.setWantsRunning(this, true)
        try {
            VoiceLockService.start(this)
            toast("Sunna shuru — ab apna phrase bolo")
        } catch (t: Throwable) {
            toast("Start fail: ${t.message}")
        }
        updateUi()
    }

    private fun withMic(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            afterPermission = action
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun toggleAdmin() {
        val dpm = getSystemService(DevicePolicyManager::class.java) ?: return
        val admin = ComponentName(this, AdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            dpm.removeActiveAdmin(admin)
            toast("Device admin off")
        } else {
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Voice phrase bolne par screen lock karne ke liye."
                    )
            )
        }
    }

    // --- UI state ---

    private fun updateUi() {
        val m = model
        if (m != null) {
            enrollStatus.text =
                "Enrolled ✅  •  ${m.templates.size} samples  •  threshold ${fmt(m.threshold)}"
        } else {
            enrollStatus.text = if (samples.isNotEmpty()) {
                "Samples: ${samples.size}/3 — aur record karo"
            } else {
                "Abhi enroll nahi hua — 3 samples chahiye"
            }
        }

        btnRecord.text = when {
            busy -> "Recording…"
            m != null -> "Phir se enroll karo"
            samples.isNotEmpty() -> "Record sample ${samples.size + 1}/3"
            else -> "Record sample 1/3"
        }
        btnRecord.isEnabled = !busy
        btnTest.isEnabled = m != null && !busy
        btnStart.isEnabled = m != null && !busy
        btnClear.isEnabled = !busy

        val parts = listOf(
            if (VoiceLockService.running) "Listening: ON" else "Listening: OFF",
            if (isAccessibilityEnabled()) "Accessibility: ON" else "Accessibility: OFF",
            if (isAdminActive()) "Admin: ON" else "Admin: OFF"
        )
        statusText.text = parts.joinToString("   •   ")
        btnStop.isEnabled = VoiceLockService.running
    }

    private fun sensitivityText(progress: Int): String =
        "Sensitivity: $progress%  (zyada = loose, kam = strict)"

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java) ?: return false
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun isAdminActive(): Boolean {
        val dpm = getSystemService(DevicePolicyManager::class.java) ?: return false
        return dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.3f", value)

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
