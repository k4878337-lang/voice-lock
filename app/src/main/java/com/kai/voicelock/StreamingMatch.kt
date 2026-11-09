package com.kai.voicelock

import kotlin.math.sqrt

/**
 * Incremental MFCC over a sliding 25 ms / 10 ms window.
 * Uses a running cepstral mean instead of full-utterance CMN so features exist while
 * you are still speaking — this is what makes sub-100 ms triggering possible.
 */
class StreamingMfcc {

    private val window = ShortArray(400)
    private val mean = DoubleArray(Dsp.CEPSTRA)
    private var blocks = 0
    private var frames = 0

    fun push(block: ShortArray): DoubleArray? {
        System.arraycopy(window, 160, window, 0, 240)
        System.arraycopy(block, 0, window, 240, 160)
        blocks++
        if (blocks < 3) return null

        val raw = Dsp.mfccFrame(window, 0)
        frames++
        for (i in raw.indices) {
            mean[i] += (raw[i] - mean[i]) / frames
            raw[i] -= mean[i]
        }

        var norm = 0.0
        for (v in raw) norm += v * v
        norm = sqrt(norm)
        if (norm > 1e-9) {
            for (i in raw.indices) raw[i] /= norm
        }
        return raw
    }
}

/**
 * Streaming DTW. Keeps one DP row per enrolled template and fires as soon as the
 * template is aligned, even before the speaker stops talking.
 */
class StreamingMatcher(
    private val model: Enrollment.Model,
    sensitivity: Float
) {
    val threshold: Double = model.threshold * sensitivity * 1.15

    private class State(template: Array<DoubleArray>) {
        val template: Array<DoubleArray> = template
        val m: Int = template.size
        var prev = DoubleArray(m + 1) { it.toDouble() }
        var cur = DoubleArray(m + 1)
        var streak = 0
    }

    private val states = model.templates.map { State(it) }
    private var frames = 0
    private var fired = false

    /** Returns true exactly once when the phrase is confidently matched. */
    fun push(frame: DoubleArray): Boolean {
        if (fired) return false
        frames++

        for (state in states) {
            val m = state.m
            state.cur[0] = frames.toDouble()
            for (j in 1..m) {
                val t = state.template[j - 1]
                var dot = 0.0
                for (d in 0 until Dsp.CEPSTRA) dot += frame[d] * t[d]
                val cost = (1.0 - dot).coerceIn(0.0, 2.0)
                state.cur[j] = cost + minOf(state.cur[j - 1], state.prev[j], state.prev[j - 1])
            }
            val swap = state.prev
            state.prev = state.cur
            state.cur = swap
        }

        if (frames < 30) return false

        for (state in states) {
            val coverage = frames.toDouble() / state.m
            val cost = state.prev[state.m] / frames
            if (coverage in 0.75..1.60 && cost <= threshold) {
                state.streak++
                if (state.streak >= 3) {
                    fired = true
                    return true
                }
            } else {
                state.streak = 0
            }
        }
        return false
    }
}
