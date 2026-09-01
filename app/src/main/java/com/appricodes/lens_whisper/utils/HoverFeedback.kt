package com.appricodes.lens_whisper.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.appricodes.lens_whisper.data.PreferencesManager
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Haptic + audio feedback for finger-hover exploration (see
 * [com.appricodes.lens_whisper.SegmentationExploreActivity]'s `HoverExploreLayer`, the sole
 * caller): a short, hard vibration plus a distinct short tone the instant the finger crosses into
 * or out of an object's mask, and a soft, throttled vibration for as long as it stays inside one.
 * Haptics are gated on [PreferencesManager.vibrationEnabled], tones on
 * [PreferencesManager.soundEffectsEnabled] — independently, since they're different settings; a
 * call here with both off is a silent no-op, not an error.
 */
object HoverFeedback {
    private const val SAMPLE_RATE = 44100

    // Same reasoning and same attributes as ProcessingSoundPlayer.ACCESSIBILITY_AUDIO_ATTRIBUTES:
    // these enter/exit tones are accessibility information, not incidental media, and belong on
    // the channel Android reserves for exactly that (routes and ducks correctly alongside TalkBack
    // speech, unlike STREAM_MUSIC).
    private val ACCESSIBILITY_AUDIO_ATTRIBUTES = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    // --- vibration -----------------------------------------------------------------------------

    private fun vibrator(context: Context): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private fun vibrate(context: Context, durationMs: Long, amplitude: Int) {
        if (!PreferencesManager(context).vibrationEnabled) return
        val vib = vibrator(context) ?: return
        if (!vib.hasVibrator()) return
        try {
            vib.vibrate(VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255)))
        } catch (e: Exception) {
            // Some OEM vibrator HALs reject unusual (duration, amplitude) pairs — feedback is a
            // nice-to-have here, never worth crashing exploration over.
        }
    }

    // Throttles the continuous "inside an object" pulse — [onInsideObject] is meant to be called
    // from every qualifying pointer-move event, which on a fast finger drag can fire far more
    // often than a vibration motor can usefully ramp up/down for; this caps it to a rate that
    // still reads as "continuous" without turning into a single buzzing mess.
    private const val INSIDE_TICK_MIN_INTERVAL_MS = 70L
    private var lastInsideTickAt = 0L

    /** Crossing into a new object, or out of one to empty space — a short, strong pulse, the same
     *  in both directions (the caller picks the accompanying tone; see [playEnterTone]/[playExitTone]). */
    fun onBoundaryCrossed(context: Context) {
        vibrate(context, durationMs = 35, amplitude = 255)
    }

    /** The finger is moving but has stayed inside the same object since the last event — a soft,
     *  rate-limited pulse so the motor reads as "still inside something" rather than firing once
     *  per pixel of movement. */
    fun onInsideObject(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastInsideTickAt < INSIDE_TICK_MIN_INTERVAL_MS) return
        lastInsideTickAt = now
        vibrate(context, durationMs = 12, amplitude = 55)
    }

    // --- tones -----------------------------------------------------------------------------

    private fun play(mixBuffer: DoubleArray, releaseDelayMs: Long) {
        val pcm = ByteArray(mixBuffer.size * 2)
        var idx = 0
        for (sample in mixBuffer) {
            val clamped = sample.coerceIn(-1.0, 1.0)
            val value = (clamped * 32767).toInt().toShort()
            pcm[idx++] = (value.toInt() and 0x00FF).toByte()
            pcm[idx++] = ((value.toInt() and 0xFF00) ushr 8).toByte()
        }
        try {
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(ACCESSIBILITY_AUDIO_ATTRIBUTES)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            audioTrack.write(pcm, 0, pcm.size)
            audioTrack.play()
            Handler(Looper.getMainLooper()).postDelayed({ audioTrack.release() }, releaseDelayMs)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Two quick rising notes — a small, bright "found something" blip, distinct from [playExitTone]'s
     *  falling shape so the two are tellable apart by ear alone, without needing to hear the
     *  follow-up spoken label. */
    fun playEnterTone(context: Context) {
        if (!PreferencesManager(context).soundEffectsEnabled) return
        play(twoNoteBlip(lowFrequency = 660.0, highFrequency = 880.0, rising = true), 220)
    }

    /** The mirror of [playEnterTone]: two quick falling notes for "left that object". */
    fun playExitTone(context: Context) {
        if (!PreferencesManager(context).soundEffectsEnabled) return
        play(twoNoteBlip(lowFrequency = 660.0, highFrequency = 880.0, rising = false), 220)
    }

    private fun twoNoteBlip(lowFrequency: Double, highFrequency: Double, rising: Boolean): DoubleArray {
        val noteDurationSec = 0.045
        val totalDurationSec = noteDurationSec * 2
        val totalSamples = (totalDurationSec * SAMPLE_RATE).toInt()
        val mixBuffer = DoubleArray(totalSamples)
        val noteSamples = (noteDurationSec * SAMPLE_RATE).toInt()
        val decayRate = 18.0
        val frequencies = if (rising) listOf(lowFrequency, highFrequency) else listOf(highFrequency, lowFrequency)

        for ((noteIndex, frequency) in frequencies.withIndex()) {
            val startSample = noteIndex * noteSamples
            for (i in 0 until noteSamples) {
                val bufferIndex = startSample + i
                if (bufferIndex >= totalSamples) break
                val t = i.toDouble() / SAMPLE_RATE
                val envelope = exp(-decayRate * t)
                mixBuffer[bufferIndex] += sin(2 * PI * frequency * t) * envelope * 0.22
            }
        }
        return mixBuffer
    }
}
