package com.appricodes.lens_whisper.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.appricodes.lens_whisper.data.PreferencesManager
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The app's synthesized, non-speech UI sounds — plain additive-harmonic/noise synthesis into a
 * static [AudioTrack] buffer, no bundled audio asset. All gated on
 * [PreferencesManager.soundEffectsEnabled] internally, so no call site needs to remember to check
 * it itself (see [com.appricodes.lens_whisper.utils.HoverFeedback] for the equivalent on the
 * haptics side, gated on [PreferencesManager.vibrationEnabled] instead).
 *
 * Played on [ACCESSIBILITY_AUDIO_ATTRIBUTES] — `USAGE_ASSISTANCE_ACCESSIBILITY` +
 * `CONTENT_TYPE_SONIFICATION` — rather than a plain music/media stream: every one of these sounds
 * *is* accessibility information for a blind user (a photo was taken, the app is still working, a
 * light-level reading), not incidental media, so it belongs on the channel Android reserves for
 * exactly that (routes and ducks correctly alongside TalkBack speech, unlike `STREAM_MUSIC`).
 * `OfflineLightDetector`'s pre-rendered-file notes use the same attributes on their `MediaPlayer`
 * instances, for the same reason — this object's sounds are all synthesized PCM, not files, so
 * they go through [AudioTrack] instead, but the two intentionally agree on attributes.
 */
object ProcessingSoundPlayer {
    private const val SAMPLE_RATE = 44100

    private val ACCESSIBILITY_AUDIO_ATTRIBUTES = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun play(pcm: ByteArray, releaseDelayMs: Long) {
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

    private fun toPcm16(mixBuffer: DoubleArray): ByteArray {
        val pcm = ByteArray(mixBuffer.size * 2)
        var idx = 0
        for (sample in mixBuffer) {
            val clamped = sample.coerceIn(-1.0, 1.0)
            val value = (clamped * 32767).toInt().toShort()
            pcm[idx++] = (value.toInt() and 0x00FF).toByte()
            pcm[idx++] = ((value.toInt() and 0xFF00) ushr 8).toByte()
        }
        return pcm
    }

    // --- "waiting for the model" tone -------------------------------------------------------

    private const val WAIT_DURATION_SEC = 0.85
    private val waitHarmonics = listOf(1.0 to 1.00, 2.0 to 0.35)
    private val waitHarmonicAmplitudeSum = waitHarmonics.sumOf { it.second }

    private data class WaitVoice(val frequency: Double, val startSec: Double, val durationSec: Double, val decayRate: Double, val peak: Double)

    // A low root (D3) under a tritone (Ab3) a beat later — the classic "unresolved tension"
    // interval, played quiet and slow rather than resolved into anything pleasant, so it reads as
    // suspenseful/"still working on it" rather than as a cheerful notification. Both voices are
    // deliberately quiet ([peak]) and long-tailed (low [decayRate]) instead of the old bright,
    // fast-decaying major-triad chime — a held, low murmur under the spoken UI rather than
    // something that competes with it.
    // Peaks are 50% louder than the original 0.16/0.11 — still quiet by design, just less easily
    // masked under TalkBack speech than the first pass turned out to be.
    private val waitVoices = listOf(
        WaitVoice(frequency = 146.83, startSec = 0.00, durationSec = 0.70, decayRate = 2.6, peak = 0.24), // D3
        WaitVoice(frequency = 207.65, startSec = 0.22, durationSec = 0.55, decayRate = 3.0, peak = 0.165)  // Ab3, a tritone up
    )

    /**
     * Call on a loop (see every `startProcessingChime`-style caller — `delay(1100)` between calls
     * is the established cadence) for as long as a model request is in flight. Must keep being
     * called for the *entire* wait, including the whole duration of a streaming response, not just
     * until the first token/sentence arrives — stopping early would announce "done" before the
     * model actually is.
     */
    fun playWaitingTone(context: Context) {
        if (!PreferencesManager(context).soundEffectsEnabled) return
        val totalSamples = (WAIT_DURATION_SEC * SAMPLE_RATE).toInt()
        val mixBuffer = DoubleArray(totalSamples)

        for (voice in waitVoices) {
            val startSample = (voice.startSec * SAMPLE_RATE).toInt()
            val voiceSamples = (voice.durationSec * SAMPLE_RATE).toInt()
            val attackSamples = (0.05 * SAMPLE_RATE).toInt() // slow swell-in, not a sharp attack — part of the "held" feel
            for (i in 0 until voiceSamples) {
                val bufferIndex = startSample + i
                if (bufferIndex >= totalSamples) break
                val t = i.toDouble() / SAMPLE_RATE

                var raw = 0.0
                for ((harmonicMultiple, amplitude) in waitHarmonics) {
                    raw += amplitude * sin(2 * PI * voice.frequency * harmonicMultiple * t)
                }
                raw /= waitHarmonicAmplitudeSum

                val attack = if (i < attackSamples) i.toDouble() / attackSamples else 1.0
                val envelope = attack * exp(-voice.decayRate * t)
                mixBuffer[bufferIndex] += raw * envelope * voice.peak
            }
        }

        play(toPcm16(mixBuffer), (WAIT_DURATION_SEC * 1000).toLong() + 150)
    }

    // --- shutter click -----------------------------------------------------------------------

    private const val SHUTTER_DURATION_SEC = 0.07
    private const val SHUTTER_PEAK = 0.30 // quiet by design — "low volume shutter sound", not a real camera's click

    /** A brief, quiet percussive click — filtered noise burst plus a short high tick, not a tone —
     *  so it reads as a mechanical "photo taken" cue rather than another chime. Call once, right
     *  when a photo is actually captured (see [com.appricodes.lens_whisper.ui.components.CameraController]'s
     *  `capturePhotoInternal`, the single choke point every capture path in the app already goes
     *  through, so every "take a photo" button gets this for free). */
    fun playShutter(context: Context) {
        if (!PreferencesManager(context).soundEffectsEnabled) return
        val totalSamples = (SHUTTER_DURATION_SEC * SAMPLE_RATE).toInt()
        val mixBuffer = DoubleArray(totalSamples)
        val random = Random(System.nanoTime())

        // Broadband noise burst, fast decay — the "click" body.
        val noiseDecayRate = 90.0
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            val noise = random.nextDouble(-1.0, 1.0)
            mixBuffer[i] += noise * exp(-noiseDecayRate * t) * 0.55
        }

        // A very short high tick layered on top for a bit of "snap".
        val tickFrequency = 2200.0
        val tickDecayRate = 140.0
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            mixBuffer[i] += sin(2 * PI * tickFrequency * t) * exp(-tickDecayRate * t) * 0.45
        }

        for (i in mixBuffer.indices) mixBuffer[i] *= SHUTTER_PEAK

        play(toPcm16(mixBuffer), (SHUTTER_DURATION_SEC * 1000).toLong() + 100)
    }

    // --- capture-acknowledged tick --------------------------------------------------------

    private const val ACK_DURATION_SEC = 0.03
    private const val ACK_FREQUENCY = 520.0

    /**
     * A tiny, soft blip — call the instant a capture is *requested* (button pressed), before the
     * camera even starts binding. On this app's "camera only opens for the moment of capture"
     * design, binding a cold camera can itself take a second or two (sensor open, AE/AF
     * convergence) before [playShutter] gets to fire — with no visual loading indicator, a blind
     * user has no way to tell a tap registered at all during that gap. This exists purely to close
     * that gap instantly; [playShutter] still fires only at the real moment of capture and still
     * means "the photo was just taken" — this one deliberately uses a different, softer, shorter
     * shape (a single quiet sine blip vs. the shutter's noise-burst click) so the two are never
     * mistaken for each other. */
    fun playCaptureAcknowledge(context: Context) {
        if (!PreferencesManager(context).soundEffectsEnabled) return
        val totalSamples = (ACK_DURATION_SEC * SAMPLE_RATE).toInt()
        val mixBuffer = DoubleArray(totalSamples)
        val decayRate = 60.0
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            mixBuffer[i] = sin(2 * PI * ACK_FREQUENCY * t) * exp(-decayRate * t) * 0.18
        }
        play(toPcm16(mixBuffer), (ACK_DURATION_SEC * 1000).toLong() + 100)
    }

    // --- empty-area ambient tone -----------------------------------------------------------

    private const val EMPTY_AREA_DURATION_SEC = 0.35
    private const val EMPTY_AREA_FREQUENCY = 90.0 // low and calm — an ambient placeholder, not an alert

    /** A soft, low, calm hum — call wherever a hover/touch lands on "nothing here" in a photo
     *  (Explore, Read Document), instead of a spoken "Nothing here." Zero-to-zero linear envelope,
     *  same reasoning as `OfflineLightDetector`'s notes: a finger sliding across empty space can
     *  trigger this repeatedly in quick succession, so the waveform can't afford to click. */
    fun playEmptyAreaTone(context: Context) {
        if (!PreferencesManager(context).soundEffectsEnabled) return
        val totalSamples = (EMPTY_AREA_DURATION_SEC * SAMPLE_RATE).toInt()
        val mixBuffer = DoubleArray(totalSamples)
        val attackSamples = (totalSamples * 0.15).toInt()
        val releaseSamples = (totalSamples * 0.35).toInt()
        val releaseStart = totalSamples - releaseSamples
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            val envelope = when {
                i < attackSamples -> i.toDouble() / attackSamples
                i >= releaseStart -> (totalSamples - i).toDouble() / releaseSamples
                else -> 1.0
            }
            val raw = sin(2 * PI * EMPTY_AREA_FREQUENCY * t) + 0.4 * sin(2 * PI * EMPTY_AREA_FREQUENCY * 2 * t)
            mixBuffer[i] = raw / 1.4 * envelope * 0.14
        }
        play(toPcm16(mixBuffer), (EMPTY_AREA_DURATION_SEC * 1000).toLong() + 100)
    }

    // --- explore/read-document screen opened ------------------------------------------------

    private const val SCREEN_OPENED_DURATION_SEC = 0.18

    /** A very short, distinct "ready" chirp — call once when Explore or Read Document actually has
     *  something to show (detection/OCR finished), right before announcing how to use the screen.
     *  Two quick rising notes, shorter and brighter than [playEmptyAreaTone] or the welcome phrase,
     *  so it reads as "this specific screen is ready" rather than either of those. */
    fun playScreenOpened(context: Context) {
        if (!PreferencesManager(context).soundEffectsEnabled) return
        val totalSamples = (SCREEN_OPENED_DURATION_SEC * SAMPLE_RATE).toInt()
        val mixBuffer = DoubleArray(totalSamples)
        val noteSamples = totalSamples / 2
        val frequencies = listOf(587.33, 880.0) // D5, A5
        val decayRate = 22.0
        for ((noteIndex, frequency) in frequencies.withIndex()) {
            val startSample = noteIndex * noteSamples
            for (i in 0 until noteSamples) {
                val bufferIndex = startSample + i
                if (bufferIndex >= totalSamples) break
                val t = i.toDouble() / SAMPLE_RATE
                mixBuffer[bufferIndex] += sin(2 * PI * frequency * t) * exp(-decayRate * t) * 0.25
            }
        }
        play(toPcm16(mixBuffer), (SCREEN_OPENED_DURATION_SEC * 1000).toLong() + 100)
    }

    // --- welcome phrase ------------------------------------------------------------------

    private const val WELCOME_TOTAL_DURATION_SEC = 2.3
    private val welcomeHarmonics = listOf(1.0 to 1.00, 2.0 to 0.45, 3.0 to 0.18)
    private val welcomeHarmonicAmplitudeSum = welcomeHarmonics.sumOf { it.second }

    private data class WelcomeNote(val frequency: Double, val startSec: Double, val durationSec: Double)

    // A bright ascending major arpeggio reaching the octave (C5-E5-G5-C6) — this app's own
    // "hello", distinct in character from the low/held waiting tone and the percussive shutter
    // click. Notes overlap slightly for a pleasant shimmer rather than four separate beeps.
    private val welcomeNotes = listOf(
        WelcomeNote(523.25, 0.00, 0.9),  // C5
        WelcomeNote(659.25, 0.35, 0.9),  // E5
        WelcomeNote(783.99, 0.70, 0.9),  // G5
        WelcomeNote(1046.50, 1.15, 1.1)  // C6
    )

    /** A short (~2.3s) welcome phrase — call once when the app's main screen first opens (see
     *  `CameraActivity`'s top-level `LaunchedEffect(Unit)`), not on every return to it. */
    fun playWelcome(context: Context) {
        if (!PreferencesManager(context).soundEffectsEnabled) return
        val totalSamples = (WELCOME_TOTAL_DURATION_SEC * SAMPLE_RATE).toInt()
        val mixBuffer = DoubleArray(totalSamples)

        for (note in welcomeNotes) {
            val startSample = (note.startSec * SAMPLE_RATE).toInt()
            val noteSamples = (note.durationSec * SAMPLE_RATE).toInt()
            val attackSamples = (0.015 * SAMPLE_RATE).toInt()
            val decayRate = 3.2
            for (i in 0 until noteSamples) {
                val bufferIndex = startSample + i
                if (bufferIndex >= totalSamples) break
                val t = i.toDouble() / SAMPLE_RATE

                var raw = 0.0
                for ((harmonicMultiple, amplitude) in welcomeHarmonics) {
                    raw += amplitude * sin(2 * PI * note.frequency * harmonicMultiple * t)
                }
                raw /= welcomeHarmonicAmplitudeSum

                val attack = if (i < attackSamples) i.toDouble() / attackSamples else 1.0
                val envelope = attack * exp(-decayRate * t)
                mixBuffer[bufferIndex] += raw * envelope * 0.45
            }
        }

        play(toPcm16(mixBuffer), (WELCOME_TOTAL_DURATION_SEC * 1000).toLong() + 200)
    }
}
