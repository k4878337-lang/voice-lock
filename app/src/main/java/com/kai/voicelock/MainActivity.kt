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
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var phraseInput: EditText
    private lateinit var modelStatus: TextView
    private lateinit var progressText: TextView
    private lateinit var downloadBtn: Button
    private lateinit var langGroup: RadioGroup

    private var downloading = false

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) ensureNotificationThenStart()
            else toast("Mic permission ke bina sun nahi sakta")
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startNow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        phraseInput = findViewById(R.id.phraseInput)
        modelStatus = findViewById(R.id.modelStatus)
        progressText = findViewById(R.id.progressText)
        downloadBtn = findViewById(R.id.downloadBtn)
        langGroup = findViewById(R.id.langGroup)

        phraseInput.setText(Prefs.phrases(this).joinToString(", "))
        if (Prefs.lang(this) == "hi") langGroup.check(R.id.langHi) else langGroup.check(R.id.langEn)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            savePhrases()
            if (!Prefs.hasModel(this)) {
                toast("Pehle voice model download karo")
                return@setOnClickListener
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                return@setOnClickListener
            }
            ensureNotificationThenStart()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            Prefs.setWantsRunning(this, false)
            VoiceLockService.stop(this)
            toast("Listening band")
            refresh()
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

        downloadBtn.setOnClickListener { downloadModel() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun downloadModel() {
        if (downloading) return
        val hindi = langGroup.checkedRadioButtonId == R.id.langHi
        Prefs.setLang(this, if (hindi) "hi" else "en")
        val url = if (hindi) ModelDownloader.URL_HI else ModelDownloader.URL_EN

        downloading = true
        downloadBtn.isEnabled = false
        progressText.text = "Download shuru…"
        lifecycleScope.launch {
            try {
                ModelDownloader.download(this@MainActivity, url) { pct ->
                    runOnUiThread { progressText.text = "Download: $pct%" }
                }
                progressText.text = if (hindi) {
                    "Model ready ✅ (Hindi model Devanagari likhta hai — phrase Devanagari me daalo)"
                } else {
                    "Model ready ✅"
                }
            } catch (t: Throwable) {
                progressText.text = "Download fail: ${t.message}"
            } finally {
                downloading = false
                refresh()
            }
        }
    }

    private fun savePhrases() {
        Prefs.setPhrases(this, phraseInput.text.toString())
    }

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
            toast("Sunna shuru — ab phrase bolo")
        } catch (t: Throwable) {
            toast("Start fail: ${t.message}")
        }
        refresh()
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

    private fun refresh() {
        val parts = buildList {
            add(if (VoiceLockService.running) "Listening: ON" else "Listening: OFF")
            add(if (isAccessibilityEnabled()) "Accessibility: ON" else "Accessibility: OFF")
            add(if (isAdminActive()) "Admin: ON" else "Admin: OFF")
        }
        statusText.text = parts.joinToString("   •   ")

        modelStatus.text = if (Prefs.hasModel(this)) {
            "Voice model: ready ✅"
        } else {
            "Voice model: abhi download nahi hua"
        }
        downloadBtn.isEnabled = !Prefs.hasModel(this) && !downloading
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java) ?: return false
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun isAdminActive(): Boolean {
        val dpm = getSystemService(DevicePolicyManager::class.java) ?: return false
        return dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
