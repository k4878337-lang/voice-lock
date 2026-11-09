package com.kai.voicelock

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

object AudioCapture {

    /** Records one utterance (VAD-trimmed) or null on timeout/error. Blocking — call off the main thread. */
    fun recordPhrase(maxMs: Int = 4500, gain: Float = 1f): ShortArray? {
        val minBuf = AudioRecord.getMinBufferSize(
            Dsp.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            Dsp.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 2, 8192)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }

        val segmenter = Segmenter()
        val frame = ShortArray(Segmenter.FRAME_SAMPLES)
        val maxFrames = maxMs / 10

        try {
            record.startRecording()
            var frames = 0
            while (frames < maxFrames) {
                var read = 0
                while (read < frame.size) {
                    val n = record.read(frame, read, frame.size - read)
                    if (n <= 0) return null
                    read += n
                }
                frames++
                applyGain(frame, gain)
                segmenter.push(frame)?.let { return it }
            }
            return null
        } catch (t: Throwable) {
            return null
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    fun applyGain(frame: ShortArray, gain: Float) {
        if (gain == 1f) return
        for (i in frame.indices) {
            val v = (frame[i] * gain).toInt()
            frame[i] = v.coerceIn(-32768, 32767).toShort()
        }
    }
}
