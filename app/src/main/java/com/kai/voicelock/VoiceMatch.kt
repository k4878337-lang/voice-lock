package com.kai.voicelock

import kotlin.math.abs

object VoiceMatch {

    /** MFCC + per-frame normalization; null if the clip is too short. */
    fun prepare(samples: ShortArray): Array<DoubleArray>? {
        val frames = Dsp.mfcc(samples)
        if (frames.size < 15) return null
        return Dsp.normalize(frames)
    }

    /**
     * DTW distance between two normalized MFCC sequences.
     * ~0.10–0.20 = same speaker, same phrase. 0.25+ = different phrase/speaker.
     */
    fun distance(a: Array<DoubleArray>, b: Array<DoubleArray>): Double {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return 2.0

        var prev = DoubleArray(m + 1)
        var cur = DoubleArray(m + 1)
        for (j in 0..m) prev[j] = j.toDouble()

        for (i in 1..n) {
            cur[0] = i.toDouble()
            for (j in 1..m) {
                val cost = cosineDistance(a[i - 1], b[j - 1])
                cur[j] = cost + minOf(prev[j], cur[j - 1], prev[j - 1])
            }
            val swap = prev
            prev = cur
            cur = swap
        }
        return prev[m] / maxOf(n, m)
    }

    /** Mean F0 over voiced frames, or null if the clip has no clear pitch. */
    fun pitchMean(samples: ShortArray): Double? {
        if (samples.size < 800) return null
        val values = ArrayList<Double>()
        var start = 0
        while (start + 400 <= samples.size) {
            if (frameRms(samples, start, 400) > 0.02) {
                f0At(samples, start, 400)?.let { values.add(it) }
            }
            start += 160
        }
        if (values.size < 5) return null
        return values.average()
    }

    private fun cosineDistance(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0
        for (i in a.indices) dot += a[i] * b[i]
        return (1.0 - dot).coerceIn(0.0, 2.0)
    }

    private fun frameRms(x: ShortArray, start: Int, length: Int): Double {
        var sum = 0.0
        for (j in 0 until length) {
            val v = x[start + j] / 32768.0
            sum += v * v
        }
        return kotlin.math.sqrt(sum / length)
    }

    private fun f0At(x: ShortArray, start: Int, length: Int): Double? {
        val minLag = 40   // 400 Hz
        val maxLag = 240  // 66 Hz
        var energy = 0.0
        for (j in 0 until length) {
            val v = x[start + j] / 32768.0
            energy += v * v
        }
        if (energy <= 0.0) return null

        var bestLag = -1
        var best = 0.0
        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (j in 0 until length - lag) {
                sum += (x[start + j] / 32768.0) * (x[start + j + lag] / 32768.0)
            }
            val norm = sum / energy
            if (norm > best) {
                best = norm
                bestLag = lag
            }
        }
        if (bestLag < 0 || best < 0.40) return null
        return Dsp.SAMPLE_RATE.toDouble() / bestLag
    }
}

data class MatchResult(
    val accepted: Boolean,
    val distance: Double,
    val threshold: Double
)

object VoiceMatcher {

    fun match(model: Enrollment.Model, samples: ShortArray, sensitivity: Float): MatchResult {
        val features = VoiceMatch.prepare(samples)
            ?: return MatchResult(false, 2.0, 0.0)

        var best = Double.MAX_VALUE
        var bestFrames = 1
        for (template in model.templates) {
            val d = VoiceMatch.distance(features, template)
            if (d < best) {
                best = d
                bestFrames = template.size
            }
        }

        val threshold = (model.threshold * sensitivity).coerceAtLeast(0.05)
        val ratio = features.size.toDouble() / bestFrames.toDouble()
        val durationOk = ratio in 0.5..2.0

        val pitch = VoiceMatch.pitchMean(samples)
        val pitchOk = if (pitch == null || model.pitchMean <= 0.0) {
            true
        } else {
            val tolerance = maxOf(35.0, model.pitchStd * 2.5)
            abs(pitch - model.pitchMean) <= tolerance
        }

        val accepted = durationOk && best <= threshold && (pitchOk || best <= threshold * 0.75)
        return MatchResult(accepted, best, threshold)
    }
}
