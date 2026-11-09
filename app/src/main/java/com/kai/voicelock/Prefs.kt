package com.kai.voicelock

import android.content.Context

object Prefs {
    private const val FILE = "voice_lock"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun wantsRunning(ctx: Context): Boolean = sp(ctx).getBoolean("wants_running", false)

    fun setWantsRunning(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("wants_running", value).apply()
    }

    /** 0..100 user-facing slider. 50 = 1.0x auto threshold. */    fun sensitivityProgress(ctx: Context): Int = sp(ctx).getInt("sensitivity", 50)

    fun setSensitivityProgress(ctx: Context, value: Int) {
        sp(ctx).edit().putInt("sensitivity", value.coerceIn(0, 100)).apply()
    }

    fun sensitivityFactor(ctx: Context): Float =
        0.7f + sensitivityProgress(ctx) / 100f * 0.6f

    /** Far mode: MIC source + software gain + lower VAD threshold. */
    fun farMode(ctx: Context): Boolean = sp(ctx).getBoolean("far_mode", false)

    fun setFarMode(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("far_mode", value).apply()
    }
}
