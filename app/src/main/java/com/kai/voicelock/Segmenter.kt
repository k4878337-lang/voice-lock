package com.kai.voicelock

import kotlin.math.log10

/**
 * Streaming voice-activity segmenter.
 * Feed 10 ms frames, get back one complete utterance (speech only, silence trimmed).
 * Adaptive noise floor means it works in quiet rooms and with YouTube in the background.
 */
class Segmenter {

    companion object {
        const val FRAME_SAMPLES = 160      // 10 ms @ 16 kHz
        private const val PRE_ROLL = 12    // 120 ms kept before speech starts
        private const val START_FRAMES = 3 // 30 ms of speech to trigger
        private const val END_SILENCE = 20 // 200 ms silence ends the utterance
        private const val MAX_FRAMES = 450 // 4.5 s hard cap
        private const val MIN_SPEECH_FRAMES = 25
        private const val START_OVER_NOISE_DB = 8.0
        private const val END_OVER_NOISE_DB = 7.0
    }

    private val preRoll = ArrayDeque<ShortArray>()
    private var segment = ArrayList<ShortArray>()
    private var active = false
    private var speechRun = 0
    private var silenceRun = 0
    private var noiseDb = -65.0
    private var noiseReady = false

    /** Returns a complete utterance when one ends, otherwise null. */
    fun push(frame: ShortArray): ShortArray? {
        val copy = frame.copyOf()
        val db = energyDb(copy)

        if (!noiseReady) {
            noiseDb = db
            noiseReady = true
        }

        if (!active) {
            preRoll.addLast(copy)
            while (preRoll.size > PRE_ROLL) preRoll.removeFirst()

            if (db > noiseDb + START_OVER_NOISE_DB) speechRun++ else speechRun = 0
            if (db < noiseDb) noiseDb = noiseDb * 0.95 + db * 0.05
            else noiseDb = minOf(noiseDb + 0.01, -20.0)

            if (speechRun >= START_FRAMES) {
                active = true
                segment = ArrayList(preRoll)
                silenceRun = 0
                speechRun = 0
            }
            return null
        }

        segment.add(copy)
        if (db < noiseDb + END_OVER_NOISE_DB) silenceRun++ else silenceRun = 0

        if (silenceRun >= END_SILENCE || segment.size >= MAX_FRAMES) {
            return finish()
        }
        return null
    }

    fun reset() {
        preRoll.clear()
        segment = ArrayList()
        active = false
        speechRun = 0
        silenceRun = 0
    }

    private fun finish(): ShortArray? {
        val frames = segment
        val threshold = noiseDb + 6.0

        var start = 0
        var end = frames.size - 1
        while (start < end && energyDb(frames[start]) < threshold) start++
        while (end > start && energyDb(frames[end]) < threshold) end--

        reset()

        val count = end - start + 1
        if (count < MIN_SPEECH_FRAMES) return null

        val out = ShortArray(count * FRAME_SAMPLES)
        var pos = 0
        for (i in start..end) {
            System.arraycopy(frames[i], 0, out, pos, FRAME_SAMPLES)
            pos += FRAME_SAMPLES
        }
        return out
    }

    private fun energyDb(frame: ShortArray): Double {
        var sum = 0.0
        for (s in frame) {
            val v = s / 32768.0
            sum += v * v
        }
        return 10.0 * log10(sum / frame.size + 1e-12)
    }
}
