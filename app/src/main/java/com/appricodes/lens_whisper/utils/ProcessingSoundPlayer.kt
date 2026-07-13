package com.appricodes.lens_whisper.utils

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * A short, pleasant three-note ascending bell arpeggio (C5-E5-G5) played on a loop while an
 * AI request is in flight — used instead of a plain generated beep. Same additive-harmonic
 * bell synthesis approach as [OfflineLightDetector], just arranged as a melodic figure instead
 * of a single tone.
 */
object ProcessingSoundPlayer {
    private const val SAMPLE_RATE = 44100
    private const val TOTAL_DURATION_SEC = 0.6

    private val harmonics = listOf(1.0 to 1.00, 2.0 to 0.45, 3.0 to 0.18)
    private val harmonicAmplitudeSum = harmonics.sumOf { it.second }

    private data class NoteEvent(val frequency: Double, val startSec: Double, val durationSec: Double, val decayRate: Double)

    // C5, E5, G5 — a bright ascending major-triad arpeggio with a slight overlap for shimmer.
    private val notes = listOf(
        NoteEvent(523.25, 0.00, 0.30, 7.0),
        NoteEvent(659.25, 0.09, 0.30, 7.0),
        NoteEvent(783.99, 0.18, 0.38, 6.0)
    )

    fun playChime() {
        val totalSamples = (TOTAL_DURATION_SEC * SAMPLE_RATE).toInt()
        val mixBuffer = DoubleArray(totalSamples)

        for (note in notes) {
            val startSample = (note.startSec * SAMPLE_RATE).toInt()
            val noteSamples = (note.durationSec * SAMPLE_RATE).toInt()
            val attackSamples = (0.008 * SAMPLE_RATE).toInt()
            for (i in 0 until noteSamples) {
                val bufferIndex = startSample + i
                if (bufferIndex >= totalSamples) break
                val t = i.toDouble() / SAMPLE_RATE

                var raw = 0.0
                for ((harmonicMultiple, amplitude) in harmonics) {
                    raw += amplitude * sin(2 * PI * note.frequency * harmonicMultiple * t)
                }
                raw /= harmonicAmplitudeSum

                val attack = if (i < attackSamples) i.toDouble() / attackSamples else 1.0
                val envelope = attack * exp(-note.decayRate * t)
                mixBuffer[bufferIndex] += raw * envelope * 0.6 // headroom for overlapping notes
            }
        }

        val pcm = ByteArray(totalSamples * 2)
        var idx = 0
        for (sample in mixBuffer) {
            val clamped = sample.coerceIn(-1.0, 1.0)
            val value = (clamped * 32767).toInt().toShort()
            pcm[idx++] = (value.toInt() and 0x00FF).toByte()
            pcm[idx++] = ((value.toInt() and 0xFF00) ushr 8).toByte()
        }

        try {
            val audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                pcm.size,
                AudioTrack.MODE_STATIC
            )
            audioTrack.write(pcm, 0, pcm.size)
            audioTrack.play()
            Handler(Looper.getMainLooper()).postDelayed({ audioTrack.release() }, (TOTAL_DURATION_SEC * 1000).toLong() + 150)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
