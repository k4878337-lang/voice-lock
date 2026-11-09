package com.kai.voicelock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.wantsRunning(context)) return
        if (!Enrollment.exists(context)) return
        try {
            VoiceLockService.start(context)
        } catch (t: Throwable) {
            Log.w("VoiceLock", "Boot start failed: ${t.message}")
        }
    }
}
