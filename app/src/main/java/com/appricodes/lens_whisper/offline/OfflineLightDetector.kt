package com.appricodes.lens_whisper.offline

import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.sin
import kotlin.math.PI

object OfflineLightDetector {

	private fun playFrequency(freqOfTone: Double) {
		val sampleRate = 44100
		val duration = 0.15                      // 250 ms
		val numSamples = (duration * sampleRate).toInt()

		val fadeMs = 0.02                        // 20 ms fade
		val fadeSamples = (fadeMs * sampleRate).toInt()

		val sample = DoubleArray(numSamples)
		val generatedSnd = ByteArray(numSamples * 2)

		// Generate raw sine wave
		for (i in 0 until numSamples) {
			sample[i] = sin(2 * PI * i / (sampleRate / freqOfTone))
		}

		// Apply fade-in and fade-out
		for (i in 0 until numSamples) {
			var amp = 1.0

			// Fade-in
			if (i < fadeSamples) {
				amp = i.toDouble() / fadeSamples
			}

			// Fade-out
			if (i > numSamples - fadeSamples) {
				val pos = numSamples - i
				amp = pos.toDouble() / fadeSamples
			}

			sample[i] *= amp
		}

		// Convert to PCM 16‑bit
		var idx = 0
		for (dVal in sample) {
			val value = (dVal * 32767).toInt().toShort()
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
			}, 200)

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

		val freqsAll = listOf(
			// octave #1
			196.00, 220.00, 246.94,
			// octave #2
			261.63, 293.66, 329.63, 349.23, 392.00, 440.00, 493.88,
			// octave #3
			523.26, 587.32, 659.26, 698.46, 784.00, 880.00, 987.76, 1046.50
		)

		playFrequency(freqsAll[(avg / 15).coerceIn(0, freqsAll.size - 1)])
	}
}
