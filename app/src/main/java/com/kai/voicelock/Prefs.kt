package com.kai.voicelock

import android.content.Context

object Prefs {
    private const val FILE = "voice_lock"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun phrases(ctx: Context): List<String> =
        sp(ctx).getString("phrases", "lock phone")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOf("lock phone")

    fun setPhrases(ctx: Context, raw: String) {
        val cleaned = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        sp(ctx).edit().putString("phrases", cleaned.ifEmpty { "lock phone" }).apply()
    }

    fun lang(ctx: Context): String = sp(ctx).getString("lang", "en") ?: "en"
    fun setLang(ctx: Context, value: String) {
        sp(ctx).edit().putString("lang", value).apply()
    }

    fun wantsRunning(ctx: Context): Boolean = sp(ctx).getBoolean("wants_running", false)
    fun setWantsRunning(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("wants_running", value).apply()
    }

    fun modelDir(ctx: Context) = java.io.File(ctx.filesDir, "model")
    fun hasModel(ctx: Context): Boolean {
        val dir = modelDir(ctx)
        return dir.isDirectory && java.io.File(dir, "conf").exists()
    }
}
