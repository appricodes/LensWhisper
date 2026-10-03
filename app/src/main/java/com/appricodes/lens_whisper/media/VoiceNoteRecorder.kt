package com.appricodes.lens_whisper.media

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Records and plays back the short voice notes the user attaches to a scanned barcode (see
 * [com.appricodes.lens_whisper.data.BarcodeNoteStore] for where the files live and how they are
 * named).
 *
 * **Format.** AAC in an MP4 container, written with a `.m4a` extension. Android has no bundled
 * MP3 *encoder* — only a decoder — so the `.mp3` the feature was sketched with isn't actually
 * available to `MediaRecorder`; `.m4a` was the explicitly allowed alternative, plays back
 * everywhere, and is smaller at the same quality. Mono at 22.05 kHz / 64 kbps: this is speech
 * being recorded to be listened back to, not music, and a note that stays a few tens of kilobytes
 * keeps the Export archive small.
 *
 * **Lifetime.** One instance per screen, held across recompositions and released from the screen's
 * `onDispose`/`onPause`. `MediaRecorder` and `MediaPlayer` both hold native resources and a
 * microphone/audio-output handle that survive the Activity if they are not explicitly released —
 * exactly the kind of leak this app has been bitten by before — so [release] is unconditional and
 * safe to call repeatedly.
 *
 * Every method swallows and logs its failures rather than throwing: a failed recording is a
 * "couldn't record" announcement to the user, never a crash in a screen a blind user is relying on.
 */
class VoiceNoteRecorder(private val context: Context) {

    private companion object {
        const val TAG = "VoiceNoteRecorder"
        /** Anything shorter than this is almost certainly a mis-tap rather than a real note, and a
         *  zero-length file left on disk would make [BarcodeNoteStore.hasAudioNote] lie. */
        const val MIN_USEFUL_BYTES = 1024L
    }

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var recordingTarget: File? = null

    val isRecording: Boolean get() = recorder != null
    val isPlaying: Boolean get() = player?.isPlaying == true

    /**
     * Starts recording into [target], replacing whatever was there. Returns false if the recorder
     * could not start at all (no microphone permission, mic already held by something else) — the
     * caller announces that; nothing is left behind on disk either way.
     */
    fun startRecording(target: File): Boolean {
        stopPlayback()
        if (recorder != null) return false
        target.parentFile?.mkdirs()
        // Recorded into a sibling ".part" file and only moved over [target] once stopRecording
        // succeeds — so a cancelled or too-short re-recording leaves the previous note intact
        // instead of having already deleted it.
        val temp = partFileFor(target)
        runCatching { temp.delete() }
        val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            newRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(22050)
                setAudioEncodingBitRate(64000)
                setOutputFile(temp.absolutePath)
                prepare()
                start()
            }
            recorder = newRecorder
            recordingTarget = target
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not start recording", e)
            runCatching { newRecorder.release() }
            runCatching { temp.delete() }
            recorder = null
            recordingTarget = null
            false
        }
    }

    /**
     * Stops the current recording and returns the file if it actually holds something usable.
     * `MediaRecorder.stop()` throws when it is stopped before any frame was written (a tap-tap on
     * the record button) and leaves a zero-length file behind, so that case is cleaned up here
     * rather than being allowed to masquerade as a saved note.
     */
    fun stopRecording(): File? {
        val current = recorder ?: return null
        val target = recordingTarget
        recorder = null
        recordingTarget = null
        val stopped = try {
            current.stop()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Recording was too short to save", e)
            false
        } finally {
            runCatching { current.release() }
        }
        if (target == null) return null
        val temp = partFileFor(target)
        if (!stopped || !temp.exists() || temp.length() < MIN_USEFUL_BYTES) {
            runCatching { temp.delete() }
            return null
        }
        runCatching { target.delete() }
        return if (temp.renameTo(target)) target else {
            runCatching { temp.delete() }
            null
        }
    }

    /** Abandons an in-progress recording and removes its partial file, leaving any previously
     *  saved note untouched. Used when the screen goes away mid-recording — see [release]. */
    fun cancelRecording() {
        val target = recordingTarget ?: return
        val current = recorder
        recorder = null
        recordingTarget = null
        runCatching { current?.stop() }
        runCatching { current?.release() }
        runCatching { partFileFor(target).delete() }
    }

    private fun partFileFor(target: File) = File(target.parentFile, target.name + ".part")

    /**
     * Plays [file], replacing any playback already running. [onComplete] fires on normal
     * completion and on failure alike, so a caller's "playing" flag always gets cleared.
     */
    fun play(file: File, onComplete: () -> Unit = {}): Boolean {
        stopPlayback()
        if (!file.exists() || file.length() == 0L) return false
        return try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    stopPlayback()
                    onComplete()
                }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "Playback error $what/$extra")
                    stopPlayback()
                    onComplete()
                    true
                }
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not play voice note", e)
            stopPlayback()
            onComplete()
            false
        }
    }

    fun stopPlayback() {
        val current = player ?: return
        player = null
        runCatching { if (current.isPlaying) current.stop() }
        runCatching { current.release() }
    }

    /** Releases both native objects. Idempotent; call from `onDispose` and `onPause`. */
    fun release() {
        cancelRecording()
        stopPlayback()
    }
}
