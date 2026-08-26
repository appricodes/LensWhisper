package com.appricodes.lens_whisper.offline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.appricodes.lens_whisper.R
import kotlin.math.pow

/**
 * Sonifies scene brightness by stepping through a set of 21 pre-rendered notes
 * (`res/raw/light_note_01.wav`..`light_note_21.wav`, 200ms each, 200Hz-1600Hz) rather than
 * synthesizing PCM at call time the way this app's other sound effects do — a deliberate switch
 * after the synthesized version's envelope still produced an audible click between notes. Each
 * file's waveform starts and ends at exactly 0 amplitude (10% linear attack / 80% sustain / 10%
 * linear release), generated offline by a Python script (not checked into the repo) using the
 * same fundamental-plus-decaying-overtones bell recipe this app's other tones use — see
 * [NOTE_RESOURCES] for the exact frequency of each. Only the first [USED_NOTE_COUNT] (200-800Hz,
 * two octaves) are wired up for now; the remaining 7 (800-1600Hz) are bundled unused, for a future
 * wider range.
 *
 * Playback is a small state machine, not "play whatever note the latest frame maps to" —
 * [detectAndPlayTone] is called far more often (once per camera frame, ~15-30/sec) than a 200ms
 * note can finish, so it only ever updates [targetNoteIndex]; the actual player
 * ([advanceTowardTarget]) runs one note at a time to completion (via
 * [MediaPlayer.setOnCompletionListener], never overlapping two notes), then steps
 * [currentNoteIndex] one position toward whatever [targetNoteIndex] is *by then* — which may have
 * moved again while the last note was playing. A big brightness jump is therefore heard as a
 * smooth run through the intervening notes, low to high or high to low, one at a time, never as a
 * jump straight to the target. When brightness stops changing, the current note just keeps
 * repeating so the meter still reads as "live" rather than going silent.
 *
 * [start]/[stop] bracket the whole thing explicitly — [detectAndPlayTone] refuses to (re)activate
 * the chain on its own. Camera frames arrive on a background executor with real latency; one that
 * was already in flight when Light Level was turned off can still call [detectAndPlayTone] a
 * moment *after* [stop] already ran, and without this it would set [active] back to `true` and
 * resurrect the whole self-perpetuating chain — which is exactly what used to make the tone keep
 * playing past a back-press, or even past the app closing.
 */
object OfflineLightDetector {

    /** All 21 generated note resources, 200Hz-1600Hz, in ascending order. Only indices
     *  0..[USED_NOTE_COUNT]-1 are ever selected by [detectAndPlayTone] today. */
    private val NOTE_RESOURCES = intArrayOf(
        R.raw.light_note_01, R.raw.light_note_02, R.raw.light_note_03, R.raw.light_note_04,
        R.raw.light_note_05, R.raw.light_note_06, R.raw.light_note_07, R.raw.light_note_08,
        R.raw.light_note_09, R.raw.light_note_10, R.raw.light_note_11, R.raw.light_note_12,
        R.raw.light_note_13, R.raw.light_note_14, R.raw.light_note_15, R.raw.light_note_16,
        R.raw.light_note_17, R.raw.light_note_18, R.raw.light_note_19, R.raw.light_note_20,
        R.raw.light_note_21
    )

    /** How many of [NOTE_RESOURCES], starting from index 0 (200Hz), brightness currently maps
     *  onto — 14 -> 200-800Hz (note 14 of 21 is exactly 800Hz by construction). Raise to use more
     *  of the bundled range. */
    private const val USED_NOTE_COUNT = 14

    private val mainHandler = Handler(Looper.getMainLooper())

    // A small cache of MediaPlayer instances, one per used note, created lazily and reused
    // (seekTo(0) + start()) rather than built fresh on every play — these files play often enough
    // (up to several times a second while Light Level is on) that repeated MediaPlayer.create()'s
    // own decode setup would be wasteful and could itself introduce a gap between notes.
    private var players: Array<MediaPlayer?>? = null

    @Volatile private var targetNoteIndex = 0
    private var currentNoteIndex = 0
    private var isPlaying = false

    /** True only while Light Level mode is actually on — guards the self-perpetuating playback
     *  chain in [advanceTowardTarget] from continuing to play notes after the mode (or the app)
     *  stops; without this, a note's own completion callback would keep queuing the next one
     *  forever, since nothing else naturally halts that chain. Call [stop] when leaving the mode. */
    @Volatile private var active = false

    private fun playerFor(context: Context, index: Int): MediaPlayer {
        val pool = players ?: arrayOfNulls<MediaPlayer>(USED_NOTE_COUNT).also { players = it }
        return pool[index] ?: MediaPlayer.create(context.applicationContext, NOTE_RESOURCES[index]).also { mp ->
            mp.setOnCompletionListener { advanceTowardTarget(context) }
            pool[index] = mp
        }
    }

    private fun advanceTowardTarget(context: Context) {
        if (!active) {
            isPlaying = false
            return
        }
        val target = targetNoteIndex
        currentNoteIndex = when {
            currentNoteIndex < target -> currentNoteIndex + 1
            currentNoteIndex > target -> currentNoteIndex - 1
            else -> currentNoteIndex
        }
        isPlaying = true
        val player = playerFor(context, currentNoteIndex)
        player.seekTo(0)
        player.start()
    }

    /** Call once when Light Level mode starts (see `MlkitButtonProvider.toggleLight`) — until this
     *  runs, [detectAndPlayTone] deliberately won't start the playback chain on its own. */
    fun start() {
        active = true
    }

    /** Call once when Light Level mode stops (see `MlkitButtonProvider.toggleLight`/
     *  `stopActiveMode`/`stopContinuous`) — halts the playback chain and silences whatever note is
     *  currently sounding, rather than letting it (and the notes after it) keep playing unattended. */
    fun stop() {
        active = false
        mainHandler.post {
            isPlaying = false
            players?.forEach { player -> if (player?.isPlaying == true) player.pause() }
        }
    }

    /** How strongly the brightest points in the frame outweigh the darkest ones when averaging —
     *  see [detectAndPlayTone]'s brightness computation. 1.0 would be a plain arithmetic mean
     *  (every pixel weighted equally); 2.0 would be RMS (root-mean-square, which weights bright
     *  pixels quite aggressively — squaring a large value grows it far more than squaring a small
     *  one). 1.1 sits close to a plain average but still leans it slightly toward the brighter
     *  parts of the scene — every pixel, including dark ones, still contributes to the result,
     *  rather than the average being dominated by a few bright spots. */
    private const val BRIGHTNESS_POWER = 1.1

    /** [detectAndPlayTone] downscales to this on its longer side (aspect ratio preserved, unlike
     *  the fixed-square scale this replaced) before doing any math — a coarse average brightness
     *  doesn't need per-pixel detail, and the camera's own analysis stream is already requested at
     *  its lowest supported resolution (see [androidx.camera.core.ImageAnalysis.Builder]'s
     *  `ResolutionSelector` in `CameraController.rebind`), so this is a second, cheap downscale on
     *  top of an already-small frame, not the primary size reduction. */
    private const val MAX_DIMENSION = 32

    fun detectAndPlayTone(context: Context, bitmap: Bitmap) {
        if (!active) return // Light Level isn't actually on — see the class doc for why this check exists

        val longerSide = maxOf(bitmap.width, bitmap.height)
        val scaleFactor = MAX_DIMENSION.toFloat() / longerSide
        val scaledWidth = (bitmap.width * scaleFactor).toInt().coerceAtLeast(1)
        val scaledHeight = (bitmap.height * scaleFactor).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

        // Generalized/Hölder power mean instead of a plain arithmetic mean: raise every pixel's
        // luma to BRIGHTNESS_POWER before summing, then take the same root at the end. Every pixel
        // still contributes (this is still a mean over all of them, not a max/percentile), but a
        // pixel twice as bright as another now moves the result by more than twice as much would
        // under a plain average — the meter leans toward "how bright are the bright parts of this
        // scene" rather than being pulled down evenly by a large dim background.
        var sumPow = 0.0
        for (x in 0 until scaledWidth) {
            for (y in 0 until scaledHeight) {
                val pixel = scaled.getPixel(x, y)
                val luma = 0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)
                sumPow += luma.pow(BRIGHTNESS_POWER)
            }
        }
        val avg = (sumPow / (scaledWidth * scaledHeight)).pow(1.0 / BRIGHTNESS_POWER)
        val fraction = (avg / 255.0).coerceIn(0.0, 1.0)
        targetNoteIndex = Math.round(fraction * (USED_NOTE_COUNT - 1)).toInt().coerceIn(0, USED_NOTE_COUNT - 1)

        // detectAndPlayTone is called from the camera analysis executor, not the main thread —
        // MediaPlayer's callbacks need a Looper, so every actual player interaction happens on Main.
        mainHandler.post {
            if (!active) return@post // stop() may have run between the check above and this post landing
            if (!isPlaying) advanceTowardTarget(context)
            // else: a note is already playing: its own completion listener will pick up whatever
            // targetNoteIndex is by the time it finishes, so there's nothing to do here now.
        }
    }
}
