package com.kai.voicelock

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tiny in-process ring of the last events, shown in the app so behaviour is visible. */
object EventLog {
    private const val MAX = 6
    private const val MAX_AGE_MS = 10 * 60 * 1000L

    private data class Entry(val at: Long, val text: String)

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun add(text: String) {
        val now = System.currentTimeMillis()
        while (entries.isNotEmpty() && now - entries.last().at > MAX_AGE_MS) entries.removeLast()
        entries.addFirst(Entry(now, text))
        while (entries.size > MAX) entries.removeLast()
    }

    @Synchronized
    fun snapshot(): List<String> {
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        return entries
            .filter { now - it.at <= MAX_AGE_MS }
            .map { "${fmt.format(Date(it.at))}  ${it.text}" }
    }

    @Synchronized
    fun clear() = entries.clear()
}
