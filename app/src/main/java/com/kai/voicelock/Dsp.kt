package com.kai.voicelock

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Minimal MFCC front-end (pre-emphasis, Hamming, FFT, mel filterbank, DCT, CMN).
 * No native libs, no model download, ~microseconds per frame class.
 */
object Dsp {

    const val SAMPLE_RATE = 16000
    const val CEPSTRA = 13

    private const val FRAME_LEN = 400   // 25 ms
    private const val HOP_LEN = 160     // 10 ms
    private const val FFT_SIZE = 512
    private const val MEL_BANDS = 26

    private val hamming = DoubleArray(FRAME_LEN) {
        0.54 - 0.46 * cos(2.0 * PI * it / (FRAME_LEN - 1))
    }

    private val melFilters: Array<DoubleArray> by lazy { buildMelFilters() }

    fun mfcc(samples: ShortArray): Array<DoubleArray> {
        if (samples.size < FRAME_LEN) return emptyArray()

        val frameCount = 1 + (samples.size - FRAME_LEN) / HOP_LEN
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        val power = DoubleArray(FFT_SIZE / 2 + 1)
        val mel = DoubleArray(MEL_BANDS)
        val out = Array(frameCount) { DoubleArray(CEPSTRA) }

        for (i in 0 until frameCount) {
            val start = i * HOP_LEN
            java.util.Arrays.fill(re, 0.0)
            java.util.Arrays.fill(im, 0.0)

            var prev = if (start > 0) samples[start - 1].toDouble() else 0.0
            for (j in 0 until FRAME_LEN) {
                val cur = samples[start + j].toDouble()
                re[j] = (cur - 0.97 * prev) * hamming[j]
                prev = cur
            }

            fft(re, im)

            for (k in power.indices) power[k] = re[k] * re[k] + im[k] * im[k]
            for (b in 0 until MEL_BANDS) {
                var sum = 0.0
                val filter = melFilters[b]
                for (k in power.indices) sum += power[k] * filter[k]
                mel[b] = ln(sum + 1e-10)
            }
            for (c in 0 until CEPSTRA) {
                var sum = 0.0
                for (b in 0 until MEL_BANDS) {
                    sum += mel[b] * cos(PI * c * (b + 0.5) / MEL_BANDS)
                }
                out[i][c] = sum
            }
        }

        // Cepstral mean normalization: removes channel/mic gain differences.
        for (c in 0 until CEPSTRA) {
            var mean = 0.0
            for (i in out.indices) mean += out[i][c]
            mean /= out.size
            for (i in out.indices) out[i][c] -= mean
        }
        return out
    }

    /** Unit-normalizes every frame so DTW compares shape, not loudness. */
    fun normalize(frames: Array<DoubleArray>): Array<DoubleArray> {
        for (f in frames) {
            var n = 0.0
            for (v in f) n += v * v
            n = sqrt(n)
            if (n > 1e-9) {
                for (j in f.indices) f[j] /= n
            }
        }
        return frames
    }

    fun energyDb(frame: ShortArray): Double {
        var sum = 0.0
        for (s in frame) {
            val v = s / 32768.0
            sum += v * v
        }
        return 10.0 * log10(sum / frame.size + 1e-12)
    }

    /** One MFCC frame from a 25 ms window starting at [offset]. No CMN (caller handles it). */
    fun mfccFrame(samples: ShortArray, offset: Int): DoubleArray {
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        val power = DoubleArray(FFT_SIZE / 2 + 1)

        var prev = if (offset > 0) samples[offset - 1].toDouble() else 0.0
        for (j in 0 until FRAME_LEN) {
            val cur = samples[offset + j].toDouble()
            re[j] = (cur - 0.97 * prev) * hamming[j]
            prev = cur
        }

        fft(re, im)
        for (k in power.indices) power[k] = re[k] * re[k] + im[k] * im[k]

        val mel = DoubleArray(MEL_BANDS)
        for (b in 0 until MEL_BANDS) {
            var sum = 0.0
            val filter = melFilters[b]
            for (k in power.indices) sum += power[k] * filter[k]
            mel[b] = ln(sum + 1e-10)
        }

        val out = DoubleArray(CEPSTRA)
        for (c in 0 until CEPSTRA) {
            var sum = 0.0
            for (b in 0 until MEL_BANDS) {
                sum += mel[b] * cos(PI * c * (b + 0.5) / MEL_BANDS)
            }
            out[c] = sum
        }
        return out
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang)
            val wIm = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                val half = len shr 1
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + half] * curRe - im[i + k + half] * curIm
                    val vIm = re[i + k + half] * curIm + im[i + k + half] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe
                    im[i + k + half] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun buildMelFilters(): Array<DoubleArray> {
        val bins = FFT_SIZE / 2 + 1
        val lowMel = hzToMel(50.0)
        val highMel = hzToMel(7800.0)

        val hzPoints = DoubleArray(MEL_BANDS + 2) { i ->
            melToHz(lowMel + (highMel - lowMel) * i / (MEL_BANDS + 1))
        }
        val binHz = DoubleArray(bins) { it * SAMPLE_RATE.toDouble() / FFT_SIZE }
        val filters = Array(MEL_BANDS) { DoubleArray(bins) }

        for (b in 0 until MEL_BANDS) {
            val left = hzPoints[b]
            val center = hzPoints[b + 1]
            val right = hzPoints[b + 2]
            for (k in 0 until bins) {
                val f = binHz[k]
                filters[b][k] = when {
                    f <= left || f >= right -> 0.0
                    f <= center -> (f - left) / (center - left)
                    else -> (right - f) / (right - center)
                }
            }
        }
        return filters
    }

    private fun hzToMel(hz: Double): Double = 1127.0 * ln(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (kotlin.math.exp(mel / 1127.0) - 1.0)
}
