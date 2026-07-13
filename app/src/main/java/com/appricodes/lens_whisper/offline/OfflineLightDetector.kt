package com.appricodes.lens_whisper.offline

import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Sonifies scene brightness as a musical tone: fully dark maps to ~200Hz, fully bright maps
 * two octaves up (~800Hz), quantized to the 24 semitones in between so the result always
 * lands on a real musical note rather than an arbitrary frequency.
 */
object OfflineLightDetector {

    private const val BASE_FREQUENCY = 200.0
    private const val OCTAVE_SPAN_SEMITONES = 24 // 2 octaves of 12-TET semitones

    // Fundamental + a few decaying overtones, so the tone sounds like a struck bell/chime
    // rather than a flat, clinical sine wave.
    private val harmonics = listOf(
        1.0 to 1.00,
        2.0 to 0.45,
        3.0 to 0.20,
        4.0 to 0.10
    )
    private val harmonicAmplitudeSum = harmonics.sumOf { it.second }

    private fun frequencyForBrightness(avgLuma: Int): Double {
        val fraction = (avgLuma / 255.0).coerceIn(0.0, 1.0)
        val semitone = Math.round(fraction * OCTAVE_SPAN_SEMITONES)
        return BASE_FREQUENCY * 2.0.pow(semitone / 12.0)
    }

    private fun playFrequency(freqOfTone: Double) {
        val sampleRate = 44100
        val duration = 0.25 // 250 ms, long enough to let the tone "ring" and decay
        val numSamples = (duration * sampleRate).toInt()

        val attackSamples = (0.01 * sampleRate).toInt() // 10 ms quick attack
        val decayRate = 6.0 // exponential decay coefficient, higher = shorter tail

        val generatedSnd = ByteArray(numSamples * 2)
        var idx = 0
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate

            var raw = 0.0
            for ((harmonicMultiple, amplitude) in harmonics) {
                raw += amplitude * sin(2 * PI * freqOfTone * harmonicMultiple * t)
            }
            raw /= harmonicAmplitudeSum

            val attack = if (i < attackSamples) i.toDouble() / attackSamples else 1.0
            val envelope = attack * exp(-decayRate * t)

            val sample = (raw * envelope).coerceIn(-1.0, 1.0)
            val value = (sample * 32767).toInt().toShort()
            generatedSnd[idx++] = (value.toInt() and 0x00FF).toByte()
            generatedSnd[idx++] = ((value.toInt() and 0xFF00) ushr 8).toByte()
        }

        try {
            val audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                generatedSnd.size,
                AudioTrack.MODE_STATIC
            )

            audioTrack.write(generatedSnd, 0, generatedSnd.size)
            audioTrack.play()

            Handler(Looper.getMainLooper()).postDelayed({
                audioTrack.release()
            }, (duration * 1000).toLong() + 100)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun detectAndPlayTone(bitmap: Bitmap) {
        val scaled = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        var sum = 0L

        for (x in 0 until 64) {
            for (y in 0 until 64) {
                val pixel = scaled.getPixel(x, y)
                sum += (0.299 * Color.red(pixel) +
                        0.587 * Color.green(pixel) +
                        0.114 * Color.blue(pixel)).toInt()
            }
        }

        val avg = (sum / (64 * 64)).toInt()
        playFrequency(frequencyForBrightness(avg))
    }
}
