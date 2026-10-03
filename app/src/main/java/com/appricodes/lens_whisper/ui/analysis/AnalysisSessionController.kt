package com.appricodes.lens_whisper.ui.analysis

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appricodes.lens_whisper.data.OfflineModelsRepository
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.domain.OfflineModelInfo
import com.appricodes.lens_whisper.domain.PromptConfig
import com.appricodes.lens_whisper.inference.runSelectedModelAnalysis
import com.appricodes.lens_whisper.network.LocalServerFeature
import com.appricodes.lens_whisper.inference.runSelectedOfflineModelAnalysisStreaming
import com.appricodes.lens_whisper.offline.OfflineTranslator
import com.appricodes.lens_whisper.service.ModelDownloadService
import com.appricodes.lens_whisper.service.ModelDownloadState
import com.appricodes.lens_whisper.ui.components.DownloadProgressDialog
import com.appricodes.lens_whisper.ui.components.DownloadPromptDialog
import com.appricodes.lens_whisper.ui.components.StillDownloadingDialog
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.appricodes.lens_whisper.utils.ProcessingSoundPlayer
import com.appricodes.lens_whisper.utils.ThermalGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** What a screen-specific frame source hands back to [AnalysisSessionController.runAnalysis].
 *
 *  [offlineImages] is the only field that matters in the shipping configuration — every model runs
 *  on-device. The Local Server fields feed the switched-off LAN path (see
 *  [com.appricodes.lens_whisper.network.LocalServerFeature]) and are kept so re-enabling it needs
 *  no rework here; [localServerCallStreaming] is null wherever streaming isn't offered for that
 *  capture (Explore), in which case [localServerCall] is used regardless of the streaming setting.
 *
 *  The cloud-backend fields this used to carry are gone with the backend itself. */
data class CapturedBundle(
    val offlineImages: List<Bitmap>,
    val localServerCall: suspend () -> String = { "ERROR: Local Server isn't available for this action." },
    val localServerCallStreaming: (() -> Flow<String>)? = null
)

/**
 * Loop detection for [AnalysisSessionController.runStreamingAnalysis]: small on-device models
 * occasionally degenerate into repeating themselves once they run out of anything new to say. The
 * original version of this check only caught a *whole sentence* repeating a previous one
 * verbatim — too narrow, since a model can just as easily loop on a sub-sentence phrase (never
 * completing two matching whole sentences) or drift into slightly different wrapper phrasing
 * around the same repeated core each time.
 *
 * The signal: does the response so far *end* with two adjacent, identical runs of at least
 * [LOOP_MIN_REPEAT_WORDS] whole words — i.e. did the model just say the same phrase twice in a
 * row, whatever that phrase actually was. Checked on every streamed delta (not just at a sentence
 * terminator), so a loop is caught mid-sentence rather than only once/if it happens to complete
 * another whole matching sentence.
 *
 * Operates on a rolling `IntArray` of per-word CRC32 hashes ([WordRepeatTracker]) rather than
 * re-scanning the response's raw characters on every delta (an earlier version of this did
 * exactly that, first via a backtracking regex, then via a bounded character-range scan — see git
 * history) — comparing `Int`s is cheaper than comparing character ranges, each word is hashed
 * exactly once no matter how many later deltas re-check the tail, and bounding the window by word
 * *count* rather than character count sidesteps a character-window's fixed ceiling on the longest
 * single repeated unit it can ever catch. That ceiling is exactly what shipped 2026-09-02 and
 * failed: a 4000-character window (2000-character max repeated unit) still wasn't enough for a
 * repeated chunk one on-device test turned out to produce. This replaces that window entirely, not
 * adds to it — see [LOOP_MAX_TRACKED_WORDS]'s own doc for why a word-count window sized well under
 * that failed one's raw character reach is still correct: it's set from this app's actual on-device
 * models' real output lengths, confirmed always well under the cap, rather than from
 * `MAX_OUTPUT_TOKENS`'s theoretical worst case.
 */
private const val LOOP_MIN_REPEAT_WORDS = 3

// Hard cap on how many trailing words WordRepeatTracker ever keeps — same "only the tail matters,
// bound the cost" reasoning the old character window used, just counted in words instead of
// characters. Also caps the longest single repeated unit catchable at half this value (200 words)
// the same way the old window did at half its own size. 400 rather than a larger number gone by
// the raw MAX_OUTPUT_TOKENS math (800 tokens, worst case ~600 words): confirmed on-device that
// this app's actual on-device models never produce a response anywhere near that in practice
// (well under 400 words), so a 400-word window can't be the limiting factor the way the earlier
// 4000-character one turned out to be, without paying for headroom nothing here will ever use. If
// a future model's real output regularly runs longer, revisit this against its actual word counts,
// not the token cap alone. Cost stays cheap at this size (~20,000 Int comparisons worst case per
// call that actually completes a word - see [WordRepeatTracker.feed]'s early-exit for calls that
// don't).
private const val LOOP_MAX_TRACKED_WORDS = 400

/**
 * Tracks the CRC32 hash of every completed word in a streaming response (a "word" being a maximal
 * run of Unicode letters/digits, matching how [findSentenceEnd]'s siblings define token
 * boundaries elsewhere) in a fixed-size [IntArray] ring buffer, and answers whether the trailing
 * words repeat — the word-level replacement for the character-window scan described in the
 * loop-detection section's own doc.
 *
 * One instance per [AnalysisSessionController.runStreamingAnalysis] call (local, not a shared
 * field) — it holds real per-response state that must never leak between one analysis and the
 * next.
 *
 * [feed] must see every character of every delta, in order, exactly once — a delta has no
 * obligation to end on a word boundary (a streamed chunk can split a word in half), so
 * [partialWord] carries whatever's accumulated so far across calls until either a non-letter/digit
 * character terminates it or the response ends.
 */
private class WordRepeatTracker {
    private val hashes = IntArray(LOOP_MAX_TRACKED_WORDS)
    private var size = 0 // valid entries so far; grows to hashes.size, then stays there
    private var head = 0 // physical index of the oldest entry, once size == hashes.size
    private val partialWord = StringBuilder()
    private val crc32 = java.util.zip.CRC32()

    /** [logicalIndex] 0 = oldest tracked word, [size] - 1 = newest — maps onto [hashes]' actual
     *  ring-buffer layout. */
    private operator fun get(logicalIndex: Int): Int = hashes[(head + logicalIndex) % hashes.size]

    private fun pushWord(word: String) {
        if (word.isEmpty()) return
        crc32.reset()
        crc32.update(word.toByteArray(Charsets.UTF_8))
        // CRC32 is a 32-bit checksum, not a cryptographic hash - two different words could in
        // principle collide. Immaterial here: even a stray single-word collision only matters if
        // every word across an entire LOOP_MIN_REPEAT_WORDS-long run collides the same way, which
        // is astronomically less likely than any individual collision, and a hash-based approach
        // over hundreds of ordinary spoken words has no adversary trying to engineer one anyway.
        hashes[(head + size) % hashes.size] = crc32.value.toInt()
        if (size < hashes.size) size++ else head = (head + 1) % hashes.size
    }

    /** Feeds one streamed delta's characters into the tracker, completing whatever word(s) it
     *  finishes, and returns whether the hash array now ends with two adjacent, identical runs of
     *  at least [LOOP_MIN_REPEAT_WORDS] words. Skips the scan entirely (returning `false`) when
     *  this delta didn't complete any word — most streamed deltas are sub-word fragments, and the
     *  scan's answer can only change on a call that actually adds a new hash. Otherwise a plain
     *  length-by-length scan, same shape as the character-window version this replaced: for each
     *  candidate repeat length starting at [LOOP_MIN_REPEAT_WORDS], compare the last `length`
     *  word-hashes against the `length` word-hashes immediately before them; stop at the first
     *  (shortest) match. */
    fun feed(delta: String): Boolean {
        var pushedAny = false
        for (c in delta) {
            if (c.isLetterOrDigit()) {
                partialWord.append(c)
            } else if (partialWord.isNotEmpty()) {
                pushWord(partialWord.toString())
                partialWord.clear()
                pushedAny = true
            }
        }
        if (!pushedAny) return false
        var length = LOOP_MIN_REPEAT_WORDS
        while (length * 2 <= size) {
            var allMatch = true
            for (i in 0 until length) {
                if (this[size - 2 * length + i] != this[size - length + i]) {
                    allMatch = false
                    break
                }
            }
            if (allMatch) return true
            length++
        }
        return false
    }
}

/** Index of the first sentence-terminating character in [sb] that's actually a sentence boundary —
 *  followed by whitespace, another terminator, or being a newline itself — not, say, the decimal
 *  point in "3.14" sitting at the end of a streamed chunk with more digits still to come. Covers
 *  the CJK/Persian full-width terminators too, since [PreferencesManager.selectedLanguage] can be
 *  any of the app's supported languages. Returns -1 if no complete sentence is in the buffer yet
 *  (deliberately, not "assume end-of-buffer means end-of-sentence" — that would misfire whenever a
 *  streamed delta happens to end right after a period that isn't actually a sentence end). */
private fun findSentenceEnd(sb: StringBuilder): Int {
    val terminators = charArrayOf('.', '!', '?', '。', '！', '؟')
    for (i in sb.indices) {
        val c = sb[i]
        if (c == '\n') return i
        if (c !in terminators) continue
        val next = if (i + 1 < sb.length) sb[i + 1] else null
        if (next != null && (next.isWhitespace() || next in terminators)) return i
    }
    return -1
}

/**
 * The "common class" shared by [com.appricodes.lens_whisper.CameraActivity] and
 * `MediaDescriberScreen`: every piece of analysis/voice/Explore/repeat/gating logic that today is
 * either duplicated between the two, or was buried inline in one Activity, lives here instead.
 * Screen-specific bits (how to actually capture a frame, the Explore activity-result launcher,
 * `onKeyDown` wiring) stay in the host — see each method's doc for what it needs supplied.
 *
 * Plain `remember`-instantiated state holder, matching the existing
 * [com.appricodes.lens_whisper.ui.components.CameraController] pattern — this codebase has no
 * ViewModel/DI layer and this restructure doesn't introduce one.
 */
class AnalysisSessionController(
    private val context: Context,
    private val prefs: PreferencesManager,
    private val coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    var isAnalyzing by mutableStateOf(false)
        private set

    var dialogState by mutableStateOf<Pair<String, String>?>(null)
        private set

    /** See [blockedByHeat]/[continueDespiteHeat]. */
    var showThermalWarning by mutableStateOf(false)
        private set

    var pendingDownloadModel by mutableStateOf<OfflineModelInfo?>(null)
        private set
    private var pendingDownloadAction: (() -> Unit)? = null
    /** True once the user backs the download dialog into the background ("Continue in
     *  Background") — [pendingDownloadModel]/[pendingDownloadAction] stay tracked (so
     *  auto-resume-on-complete still fires) but [ObserveDownloads] stops rendering it. */
    var downloadDialogHidden by mutableStateOf(false)
        private set
    var stillDownloadingModel by mutableStateOf<OfflineModelInfo?>(null)
        private set

    // 4 slots: the Local Server grid only ever populates/reads indices 0-1 (via the general,
    // non-slot-targeted voice query below); the on-device catalog uses all 4, each independently
    // voice-recordable via long-press (see OfflineVlmButtonProvider). Backed by SnapshotStateList
    // so mutating an element in place (not reassigning the whole list) still triggers recomposition.
    private val _customPromptTexts = mutableStateListOf("", "", "", "")
    private val _customPromptTitles = mutableStateListOf("Custom 1", "Custom 2", "Custom 3", "Custom 4")
    val customPromptTexts: List<String> get() = _customPromptTexts
    val customPromptTitles: List<String> get() = _customPromptTitles

    var lastPrompt by mutableStateOf<PromptConfig?>(null)
        private set

    /** True while [startVoiceRecognition] is actively listening — lets a volume-key long-press
     *  (see `VolumeKeyGestureHandler`) force-stop it the moment the key is released, unlike an
     *  on-screen mic tap/long-press, which always runs until the recognizer stops itself. */
    var isListening by mutableStateOf(false)
        private set

    private var activeAudioFeedbackJob: Job? = null
    private val repository by lazy { OfflineModelsRepository(context.applicationContext) }
    private val speechRecognizer by lazy { SpeechRecognizer.createSpeechRecognizer(context) }

    /** True for the on-device VLM catalog (Gemma4/SmolVLM2/Qwen-VL/...) — those models are asked
     *  to answer in the target language via their system instruction, but small on-device models
     *  frequently ignore that, so [OfflineTranslator.translate] is applied as a fallback. Not
     *  MLKit (which never routes through here — see `MlkitButtonProvider`) and not the Local
     *  Server (which answers in the target language via the same system instruction). */
    private fun isOfflineVlmSelected(): Boolean =
        prefs.selectedModelId != "mlkit" && prefs.selectedModelId != LocalServerFeature.MODEL_ID

    /** Mirrors [isOfflineVlmSelected]'s gating on the way in, the same way [handleResponse]/
     *  [runStreamingAnalysis] already gate [OfflineTranslator.translate] on the way out: only the
     *  on-device VLM catalog is ever asked to answer in English (see
     *  `AnalysisRouter.buildOfflinePrompt`'s `replyLanguage`, on when offline translation is
     *  enabled — [OfflineTranslator.translateToEnglish] itself checks that same setting and is a
     *  no-op when it's off), so only its questions ever need translating to English first. The
     *  Local Server still gets its own system-instruction-driven target-language answer (untouched
     *  by this), and MLKit never reaches a "custom question" at all. */
    private suspend fun translateVoiceQueryToEnglish(text: String): String =
        if (isOfflineVlmSelected()) OfflineTranslator.translateToEnglish(context, text, prefs) else text

    private suspend fun handleResponse(response: String) {
        activeAudioFeedbackJob?.cancel()
        if (response.startsWith("ERROR:")) {
            AccessibilityUtils.announceToTalkBack(context, "Error occurred.")
            dialogState = "Error" to response.removePrefix("ERROR:").trim()
        } else {
            val spoken = if (isOfflineVlmSelected()) OfflineTranslator.translate(context, response, prefs) else response
            AccessibilityUtils.announceToTalkBack(context, spoken)
            if (prefs.showResultsInDialog) dialogState = "AI Description" to spoken
        }
    }

    fun dismissDialog() { dialogState = null }

    /** Checked at the top of every entry point that would otherwise reach
     *  [com.appricodes.lens_whisper.inference.OfflineInferenceEngine] — before any capture/detect/
     *  isolate work starts, so a refusal doesn't burn a shutter+segmentation cycle for nothing. True
     *  (and surfaces [showThermalWarning]) refuses; false lets the caller proceed. Never blocks the
     *  Local Server branch (nothing runs on this phone for that path) or MLKit (which never reaches
     *  this controller's analysis methods at all) — same exclusion [com.appricodes.lens_whisper.inference.AnalysisRouter]'s
     *  own, deeper check already applies, kept in sync here rather than trusting only that backstop. */
    private fun blockedByHeat(): Boolean {
        if (prefs.selectedModelId == LocalServerFeature.MODEL_ID) return false
        if (ThermalGuard.blockingMessageOrNull(context) == null) return false
        showThermalWarning = true
        AccessibilityUtils.announceToTalkBack(context, ThermalGuard.HOT_MESSAGE)
        return true
    }

    fun dismissThermalWarning() { showThermalWarning = false }

    /** User chose "Continue Anyway" on the heat warning — stops blocking (and asking again) for
     *  the rest of this app process, per explicit instruction: dismissing it should hold until the
     *  app is closed, not forever (see [ThermalGuard.dismissForThisSession]'s own doc for why
     *  that's in-memory only). Doesn't retry whatever action was just refused — the user presses
     *  the same button again, same as dismissing any other blocking dialog in this app. */
    fun continueDespiteHeat() {
        ThermalGuard.dismissForThisSession()
        showThermalWarning = false
    }
    fun dismissDownloadPrompt() { pendingDownloadModel = null; pendingDownloadAction = null; downloadDialogHidden = false }
    fun hideDownloadDialog() { downloadDialogHidden = true }
    fun dismissStillDownloadingDialog() { stillDownloadingModel = null }

    /** Starts the repeating "waiting for the model" tone — call once frames are actually ready to
     *  send (i.e. after capture/extraction has finished, not before — see each caller). */
    fun startProcessingChime() {
        activeAudioFeedbackJob = coroutineScope.launch(Dispatchers.IO) {
            ProcessingSoundPlayer.loopClockWaitingTone(context)
        }
    }

    /** Stops the tone started by [startProcessingChime] right away, rather than waiting for
     *  [runAnalysis]/[runAnalysisOnBitmap]'s own `finally` — needed by a caller that starts the
     *  tone itself (at shutter time, before this controller is even involved) and then finds out
     *  partway through its own capture/detect/isolate pipeline that there's nothing to analyze
     *  after all (e.g. "Describe TV Image" finding no `tv` instance) — see [CameraActivity]. */
    fun stopProcessingChime() {
        activeAudioFeedbackJob?.cancel()
    }

    /** This controller's one active analysis/capture job, if any — see [launchAnalysis]. */
    private var activeAnalysisJob: Job? = null

    /**
     * Launches [body] as this controller's one active job, replacing whatever's currently running
     * rather than refusing to start — every entry point below that reaches the on-device model or
     * a camera capture ([runAnalysis], [onRepeat], [runGuarded]) funnels through here instead of
     * each keeping its own busy gate, so a second button press always cancels-and
     * -replaces an in-flight analysis, the same way [SegmentationExploreActivity.analyzeTappedObject]
     * replaces one tap's analysis with the next.
     *
     * `previous?.cancelAndJoin()` — never a bare `cancel()` — is deliberately the *first* thing the
     * new coroutine does, before [body] touches anything:
     * - It's what makes cancelling an in-flight on-device model call safe. [Job.cancel] only
     *   *requests* cancellation; the actual unwind happens later, asynchronously, at the cancelled
     *   coroutine's next suspension point. [cancelAndJoin] waits for that unwind to actually finish
     *   before returning — which for a call into
     *   [com.appricodes.lens_whisper.inference.OfflineInferenceEngine.analyzeStreaming] means
     *   waiting for its own `catch (e: CancellationException) { conversation.cancelProcess(); ... }`
     *   to run. Skipping that wait (or using bare `cancel()`) risks the new job's own `Conversation`
     *   opening while the old one is still mid-teardown — confirmed on-device as a native SIGSEGV
     *   inside the GPU delegate when a `Conversation` is closed while still generating; see that
     *   function's own doc for the full history.
     * - It's also what avoids a race on [activeAudioFeedbackJob] and [isAnalyzing] themselves:
     *   both the old and new job's own `finally` below touch the same shared fields. If the new
     *   job set `isAnalyzing = true` (or started a chime) *before* the old job's cancellation had
     *   fully unwound, the old job's `finally` would run after and clobber the new job's state —
     *   turning `isAnalyzing` back off, or cancelling the *new* chime instead of the old one.
     *   Joining first guarantees the old job's `finally` is completely done before this one starts.
     *
     * Also catches (and reports, rather than crashing) any non-cancellation exception [body]
     * itself doesn't already handle — e.g. a detector failure inside "Describe TV Image"'s
     * `OfflineSegmentationDetector.detect` call, which [runGuarded] adds no catch of its own around.
     * Every job launched here runs on this same [coroutineScope]; that scope is not a
     * `SupervisorJob`, so an uncaught throw wouldn't just fail *this* job — it would kill the
     * whole scope, silently turning every later button press into a no-op for the rest of this
     * screen's lifetime. `catch (e: CancellationException) { throw e }` has to come first and
     * must rethrow: `CancellationException` is itself an `Exception` subtype, and this is exactly
     * the exception a cancel-and-replace produces on every single press — swallowing it here would
     * both misreport a normal replace as "Error occurred" and leave `activeAnalysisJob`'s own
     * cancellation half-handled.
     */
    /** The "Stop" button in [com.appricodes.lens_whisper.ui.components.ModelTopBar], shown only
     *  while [isAnalyzing]. Unlike [launchAnalysis]'s own `cancelAndJoin` (needed there because a
     *  *new* job's `Conversation` is about to open and must wait for the old one to finish
     *  tearing down first — see that function's doc), nothing new starts here, so there's no
     *  unwind race to protect against: a plain `cancel()` is enough, and [stopProcessingChime]
     *  stops the waiting tone immediately rather than waiting for the cancelled job's own `finally`
     *  (same one [launchAnalysis] runs) to get there. */
    fun stopAnalysis() {
        if (!isAnalyzing) return
        activeAnalysisJob?.cancel()
        stopProcessingChime()
        AccessibilityUtils.announceToTalkBack(context, "Stopped.")
    }

    /** Cancels any in-flight analysis and *waits* for its teardown to actually finish — the same
     *  `cancelAndJoin` (not [stopAnalysis]'s bare `cancel`) reasoning as [launchAnalysis]'s own
     *  `previous?.cancelAndJoin()`: a caller about to touch the shared
     *  [com.appricodes.lens_whisper.inference.OfflineInferenceEngine] itself right after (switching
     *  the selected model, which preloads a different engine or releases the current one) needs the
     *  old `Conversation` fully torn down first, or that close can race a generation that's still
     *  using it. Confirmed on-device: switching models mid-inference used to leave the old
     *  generation running until it failed on the now-closed engine several seconds later, surfacing
     *  as a delayed "Error occurred" dialog instead of stopping right away. Silent on purpose (no
     *  chime-stop call, no announcement) — [launchAnalysis]'s own `finally` already runs as part of
     *  the join and handles both, and a model switch the user just performed doesn't need a
     *  "Stopped." announcement on top of it. */
    suspend fun cancelActiveAnalysisAndJoin() {
        activeAnalysisJob?.cancelAndJoin()
    }

    private fun launchAnalysis(body: suspend () -> Unit) {
        val previous = activeAnalysisJob
        activeAnalysisJob = coroutineScope.launch {
            previous?.cancelAndJoin()
            isAnalyzing = true
            try {
                body()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AccessibilityUtils.announceToTalkBack(context, "Error occurred.")
                dialogState = "Error" to (e.localizedMessage ?: "Analysis failed.")
            } finally {
                isAnalyzing = false
                activeAudioFeedbackJob?.cancel()
            }
        }
    }

    /** Runs [action] under the same cancel-and-replace bookkeeping [runAnalysis] itself uses (see
     *  [launchAnalysis]), for a caller whose actual capture/detect/isolate work happens outside
     *  this controller (see "Describe TV Image" in [CameraActivity], which segments and isolates
     *  one object before ever reaching [runAnalysisOnBitmap]) — so that whole pipeline is covered,
     *  not just the final model call. Not used by "Explore and Describe"'s per-tap analysis, which
     *  manages its own, separate cancel-and-replace job — see [runAnalysisOnBitmap]'s doc. */
    fun runGuarded(action: suspend () -> Unit) {
        if (blockedByHeat()) return
        launchAnalysis(action)
    }

    /**
     * Runs one analysis request directly against an already-prepared [bitmap] — no capture step of
     * its own, unlike [runAnalysis] — shared by "Describe TV Image" (wrapped in [runGuarded] by its
     * caller) and "Explore and Describe"'s per-tap analysis (see [SegmentationExploreActivity]),
     * both of which build their own isolated-object bitmap via
     * [com.appricodes.lens_whisper.offline.SegmentationIsolation] rather than routing through a
     * screen's normal capture flow.
     *
     * Deliberately does not touch [isAnalyzing] or [lastPrompt]: "Explore and Describe" needs to
     * cancel one in-flight call and immediately start another as the user keeps exploring (its own
     * caller tracks and cancels the [kotlinx.coroutines.Job] this runs in — cancelling it here mid
     * -stream, via [runStreamingAnalysis]'s shared `flow.collect`, also genuinely stops the model
     * from generating further tokens, same as [LoopDetected] already relies on), which
     * an internal busy-block would prevent; and there is no `captureFrames` closure here for
     * [onRepeat] to later re-invoke, so leaving [lastPrompt] alone means Repeat simply falls back to
     * whatever normal prompt ran last, rather than trying to "repeat" a one-off isolated crop.
     */
    suspend fun runAnalysisOnBitmap(config: PromptConfig, bitmap: Bitmap) {
        if (blockedByHeat()) return
        val streamingFlow = try {
            runSelectedOfflineModelAnalysisStreaming(context, prefs, config, listOf(bitmap))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleResponse("ERROR: ${e.localizedMessage ?: "Setup failed."}")
            return
        }
        runStreamingAnalysis(streamingFlow, translate = isOfflineVlmSelected())
    }

    /**
     * Runs one analysis request: [captureFrames] does whatever screen-specific work is needed
     * (capture a camera photo, extract video frames, load a static image) and returns a
     * [CapturedBundle], or null if capture failed outright. Replaces both
     * `AdvancedCameraScreen.runAnalysis`/`performAnalysis` and `MediaDescriberScreen.runAnalysis`.
     *
     * Not suspend — launches (and owns) its own job via [launchAnalysis], cancelling whatever
     * analysis was already in flight rather than refusing to start a new one. A caller no longer
     * needs to wrap this in its own `coroutineScope.launch { }`.
     */
    fun runAnalysis(config: PromptConfig, label: String, captureFrames: suspend (PromptConfig) -> CapturedBundle?) {
        if (blockedByHeat()) return
        lastPrompt = config
        launchAnalysis {
            val bundle = captureFrames(config)
            if (bundle == null) {
                handleResponse("ERROR: Could not capture the image.")
                return@launchAnalysis
            }
            // Every model that reaches this point always streams now — on-device unconditionally,
            // and the Local Server too: this branch used to check a Settings toggle
            // (PreferencesManager.streamingEnabled, since removed) before calling
            // bundle.localServerCallStreaming; now it always does, whenever that streaming call is
            // offered at all (see CapturedBundle's own doc for when it isn't). The *Streaming
            // functions on LocalServerEngine already always sent `streaming: true` on their own —
            // this only changes which of the two Android ever chooses to call. MLKit never reaches
            // either branch (see isOfflineVlmSelected).
            //
            // Wrapped in its own try/catch: unlike runSelectedModelAnalysis (which reports setup
            // problems by returning an "ERROR: ..." string), runSelectedOfflineModelAnalysisStreaming
            // throws for the exact same class of problem (model missing, not downloaded, no images,
            // now also "device too hot" — see ThermalGuard) — and it throws *eagerly*, before
            // returning a Flow, not from inside one. Left uncaught, that exception would escape
            // this whole launchAnalysis body (which has no catch of its own) and crash the
            // coroutine this runs in. It's a real, easily-reached path — a phone legitimately gets
            // hot from sustained on-device inference, so "too hot" is not a rare edge case here.
            val streamingFlow: Flow<String>? = try {
                when {
                    isOfflineVlmSelected() ->
                        runSelectedOfflineModelAnalysisStreaming(context, prefs, config, bundle.offlineImages)
                    prefs.selectedModelId == LocalServerFeature.MODEL_ID ->
                        bundle.localServerCallStreaming?.invoke()
                    else -> null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleResponse("ERROR: ${e.localizedMessage ?: "Setup failed."}")
                return@launchAnalysis
            }
            if (streamingFlow != null) {
                runStreamingAnalysis(streamingFlow, translate = isOfflineVlmSelected())
            } else {
                val response = runSelectedModelAnalysis(
                    context = context,
                    prefs = prefs,
                    config = config,
                    offlineImages = bundle.offlineImages,
                    localServerCall = bundle.localServerCall
                )
                handleResponse(response)
            }
        }
    }

    /**
     * Streaming counterpart to [handleResponse] — used for on-device models always, and for the
     * Local Server whenever it offers a streaming call at all (see the two branches in
     * [runAnalysis] that decide which [Flow] gets passed in here, if any). Speaks each complete
     * sentence (ending in `.`/`!`/`?`/newline, or their CJK/Persian equivalents — see
     * [findSentenceEnd]) via TalkBack as soon as it's been generated — and translated, if
     * [translate] is true — instead of waiting for and speaking the full response as one block.
     * [translate] mirrors [isOfflineVlmSelected]'s reasoning: only on-device models need the
     * client-side translation fallback, since the Local Server already answers in the target language via
     * its own system prompt. See [runSelectedOfflineModelAnalysisStreaming] /
     * `OfflineInferenceEngine.analyzeStreaming` (on-device) and `LocalServerEngine`'s `*Streaming`
     * functions.
     */
    private suspend fun runStreamingAnalysis(flow: Flow<String>, translate: Boolean) {
        val fullText = StringBuilder()
        val pending = StringBuilder()
        val wordRepeatTracker = WordRepeatTracker()

        suspend fun speak(sentence: String) {
            val trimmed = sentence.trim()
            if (trimmed.isEmpty()) return
            val spoken = if (translate) OfflineTranslator.translate(context, trimmed, prefs) else trimmed
            AccessibilityUtils.announceToTalkBack(context, spoken)
        }

        try {
            flow.collect { delta ->
                // The waiting tone deliberately keeps playing through the *entire* stream, not just
                // until the first delta arrives — it means "the model is still working", which
                // stays true until the last token, not just until the first one.
                fullText.append(delta)
                pending.append(delta)
                // Checked on every delta, not just at a sentence boundary — see WordRepeatTracker's
                // doc for why. Throwing LoopDetected here unwinds out of flow.collect and — because
                // that's the same coroutine actually driving OfflineInferenceEngine's token
                // generation — genuinely stops the model from generating further tokens, not just
                // stops this function from speaking them.
                if (wordRepeatTracker.feed(delta)) throw LoopDetected()
                var end = findSentenceEnd(pending)
                while (end != -1) {
                    val sentence = pending.substring(0, end + 1)
                    pending.delete(0, end + 1)
                    speak(sentence)
                    end = findSentenceEnd(pending)
                }
            }
            activeAudioFeedbackJob?.cancel()
            speak(pending.toString()) // whatever's left over (no trailing punctuation) still gets spoken
            if (prefs.showResultsInDialog) dialogState = "AI Description" to fullText.toString()
        } catch (e: LoopDetected) {
            // Not an error — the model simply had nothing more to add. Whatever was already spoken
            // stands as the final answer; pending (the start of the detected repeat) is deliberately
            // never spoken.
            activeAudioFeedbackJob?.cancel()
            if (prefs.showResultsInDialog) dialogState = "AI Description" to fullText.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            activeAudioFeedbackJob?.cancel()
            AccessibilityUtils.announceToTalkBack(context, "Error occurred.")
            dialogState = "Error" to (e.localizedMessage ?: "Analysis failed.")
        }
    }

    /**
     * Thrown from inside [runStreamingAnalysis]'s `flow.collect` to unwind out of it the moment
     * [WordRepeatTracker.feed] fires — see that class's doc and the loop-detection section above it.
     *
     * A [CancellationException] subtype *on purpose* — the same early-unwind technique
     * `kotlinx.coroutines.flow`'s own `take()` operator uses internally (its private
     * `AbortFlowException`): throwing it here is what makes it actually reach
     * [com.appricodes.lens_whisper.inference.OfflineInferenceEngine.analyzeStreaming]'s own
     * `catch (e: CancellationException)` handler, which calls `Conversation.cancelProcess()`
     * before its `use{}` block's implicit `close()` runs — exactly the same "stop the in-flight
     * model call safely" method [SegmentationExploreActivity.analyzeTappedObject] relies on via
     * `Job.cancelAndJoin()`. A plain (non-cancellation) exception would skip that handler entirely
     * and reach `close()` on a `Conversation` that's still generating, which is what crashes the
     * process with a native SIGSEGV — see that fix's own doc for the confirmed repro. Caught
     * specifically, by exact subtype, right below in [runStreamingAnalysis] — before the generic
     * `catch (e: CancellationException)` there — so it's swallowed as a normal "nothing more to
     * add" outcome rather than propagating as a real cancellation of this coroutine itself; since
     * it's manually thrown rather than the result of an actual `Job.cancel()`, catching it without
     * rethrowing leaves this coroutine's own Job untouched (still Active), same as `take()`
     * catching its own `AbortFlowException`.
     */
    private class LoopDetected : CancellationException()

    /**
     * Repeats the last prompt run (voice query, tap, or custom), if any. [selectedModelId] guards
     * against the one way a multi-frame Story prompt can reach an on-device model even though no
     * offline button ever offers Story directly (see [com.appricodes.lens_whisper.ui.models.OfflineVlmButtonProvider]):
     * run Story on the Local Server, switch to an on-device model, tap Repeat — [lastPrompt] is tracked
     * regardless of which model ran it, so without this check it would resurface here. Currently
     * blocks it for every offline model, not just `fastvlm_0_5b` — the request that added this was
     * scoped to FastVLM specifically, but the leak itself isn't model-specific, so neither is the fix.
     *
     * Not suspend, same as [runAnalysis] (which this just delegates to) — see that function's doc.
     */
    fun onRepeat(selectedModelId: String, captureFrames: suspend (PromptConfig) -> CapturedBundle?) {
        val prompt = lastPrompt
        when {
            prompt == null -> AccessibilityUtils.announceToTalkBack(context, "No previous action to repeat.")
            selectedModelId != LocalServerFeature.MODEL_ID && prompt.framesCount > 1 ->
                AccessibilityUtils.announceToTalkBack(context, "Story mode isn't available for on-device models.")
            else -> runAnalysis(prompt, prompt.title, captureFrames)
        }
    }

    /** Sets [lastPrompt] directly, without running anything - seeds a sensible Repeat target (the
     *  grid's first prompt, e.g. "Brief Narration") on a fresh app open or a model switch, instead
     *  of leaving Repeat pointing at null ("No previous action to repeat") or a stale prompt left
     *  over from whichever model was selected before. Call from a `LaunchedEffect(selectedModelId)`
     *  keyed the same way CameraActivity/MediaDescriberScreen already register [onRepeat]'s host
     *  callback, so it reruns on every switch, not just the first composition. */
    fun seedDefaultPrompt(config: PromptConfig) {
        lastPrompt = config
    }

    /**
     * The duplicated `SpeechRecognizer` flow (previously copy-pasted between
     * `AdvancedCameraActivity` and `MediaDescriberScreen`). [onBeforeListen] covers the one real
     * difference between the two screens today (MediaDescriber pauses video playback first).
     *
     * [targetCustomSlot] is null for a general voice query (the bottom Microphone button, and a
     * volume-key long-press) — the recognized question runs once but is *not* saved anywhere, per
     * the "bottom mic doesn't populate a custom button" requirement; only [onRepeat] (via
     * [lastPrompt], tracked regardless) can bring it back, re-run on a fresh capture. When
     * [targetCustomSlot] is 0-3 (a long-press on one of the offline catalog's custom-prompt
     * cells), the recognized question is *saved* into that slot and then run — or, if nothing
     * was heard, that slot is cleared back to "Custom N".
     */
    fun startVoiceRecognition(
        onBeforeListen: () -> Unit = {},
        targetCustomSlot: Int? = null,
        captureFrames: suspend (PromptConfig) -> CapturedBundle?
    ) {
        onBeforeListen()
        val localeCode = prefs.speechLocaleCode
        AccessibilityUtils.announceToTalkBack(context, "Listening... Please speak your question now.")
        isListening = true

        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeCode)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsd: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                isListening = false
                // Always said out loud: a user who can't see the screen otherwise hears "Listening…"
                // and then nothing at all. Only "nothing was heard" clears a custom slot, matching
                // onResults' blank case below — a network or busy-recognizer error shouldn't wipe a
                // question the user saved earlier.
                val nothingHeard = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                val message = when {
                    nothingHeard && targetCustomSlot != null -> {
                        clearCustomSlot(targetCustomSlot)
                        "No question heard. Custom ${targetCustomSlot + 1} cleared."
                    }
                    nothingHeard -> "No question heard. Please try again."
                    error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required to ask a question."
                    error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Speech recognition needs a network connection on this phone. Please try again."
                    else -> "Speech recognition failed. Please try again."
                }
                AccessibilityUtils.announceToTalkBack(context, message)
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val recognizedQuery = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (targetCustomSlot != null) {
                    if (recognizedQuery.isNullOrBlank()) {
                        clearCustomSlot(targetCustomSlot)
                        AccessibilityUtils.announceToTalkBack(context, "No question heard. Custom ${targetCustomSlot + 1} cleared.")
                    } else {
                        // The slot's title stays in the language the user actually spoke — it's
                        // what gets read back as this button's own identity. Only the prompt text
                        // sent to (and replayed against) the model is translated, below.
                        _customPromptTitles[targetCustomSlot] = truncatedTitle(recognizedQuery)
                        coroutineScope.launch {
                            val englishQuery = translateVoiceQueryToEnglish(recognizedQuery)
                            _customPromptTexts[targetCustomSlot] = englishQuery
                            val config = PromptConfig("custom", _customPromptTitles[targetCustomSlot], englishQuery, 1, 0.5)
                            runAnalysis(config, config.title, captureFrames)
                        }
                    }
                } else if (!recognizedQuery.isNullOrBlank()) {
                    coroutineScope.launch {
                        val englishQuery = translateVoiceQueryToEnglish(recognizedQuery)
                        val config = PromptConfig("custom", "Voice Query", englishQuery, 1, 0.5)
                        runAnalysis(config, "Voice Query", captureFrames)
                    }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer.startListening(intent)
    }

    /** Force-stops listening right now — only meaningful for a volume-key-triggered mic session
     *  (see `VolumeKeyGestureHandler`), which must stop the instant the key is released, unlike
     *  an on-screen mic button/long-press. `stopListening()` (not `cancel()`) finalizes whatever
     *  was captured so far instead of discarding it. */
    fun stopVoiceListening() {
        if (isListening) {
            runCatching { speechRecognizer.stopListening() }
            isListening = false
        }
    }

    private fun clearCustomSlot(slotIndex: Int) {
        _customPromptTexts[slotIndex] = ""
        _customPromptTitles[slotIndex] = "Custom ${slotIndex + 1}"
    }

    private fun truncatedTitle(text: String) = if (text.length > 20) text.take(17) + "..." else text

    fun releaseSpeechRecognizer() {
        speechRecognizer.destroy()
    }

    /**
     * If [model] isn't downloaded yet, opens the download-size prompt instead of running
     * [action], and auto-resumes [action] once that model finishes downloading (observed via
     * [ObserveDownloads]). No-op passthrough for MLKit/Local Server (`model == null`). If [model] is
     * *already* downloading right now (e.g. the user backed the progress dialog into the
     * background via "Continue in Background" and then tapped one of that model's buttons),
     * shows a "still downloading, wait or pick another model" notice instead of the download
     * prompt — re-prompting to download something already downloading makes no sense.
     */
    fun checkDownloadedOrRedirect(model: OfflineModelInfo?, action: () -> Unit) {
        if (model == null || repository.isDownloaded(model)) {
            action()
            return
        }
        // Track the model/action regardless of which branch below fires — including when a
        // download already in progress was started from a *different* screen/controller
        // instance (e.g. ModelManagementActivity) — so both "Show Progress" (from the still-
        // downloading notice) and the normal auto-resume-on-complete in ObserveDownloads have
        // something to act on either way.
        pendingDownloadModel = model
        pendingDownloadAction = action
        val currentlyDownloading = ModelDownloadService.state.value as? ModelDownloadState.Downloading
        if (currentlyDownloading != null && currentlyDownloading.modelId == model.id) {
            stillDownloadingModel = model
            downloadDialogHidden = true
        } else {
            stillDownloadingModel = null
            downloadDialogHidden = false
        }
    }

    /** The one gate every model-specific button (prompt tap, Explore, Repeat) runs through. Now
     *  that nothing is metered or paid for, the only thing left to gate on is whether an on-device
     *  catalog model has actually been downloaded; MLKit and the Local Server go straight through. */
    fun gateForSelectedModel(selectedModelId: String, selectedOfflineModel: OfflineModelInfo?, action: () -> Unit) {
        if (selectedOfflineModel != null) checkDownloadedOrRedirect(selectedOfflineModel, action) else action()
    }

    /** Must be called once, in composition, by the host — auto-resumes a gated action once its
     *  model finishes downloading, and renders the download prompt/progress dialogs. */
    @Composable
    fun ObserveDownloads() {
        val downloadState by ModelDownloadService.state.collectAsState()
        LaunchedEffect(downloadState) {
            val completed = downloadState as? ModelDownloadState.Completed
            if (completed != null && completed.modelId == pendingDownloadModel?.id) {
                val action = pendingDownloadAction
                pendingDownloadModel = null
                pendingDownloadAction = null
                action?.invoke()
            }
        }

        val model = pendingDownloadModel
        val activeDownload = downloadState as? ModelDownloadState.Downloading
        if (model != null && !downloadDialogHidden) {
            if (activeDownload == null) {
                DownloadPromptDialog(
                    model = model,
                    onConfirm = { ModelDownloadService.start(context, model) },
                    onDismiss = { dismissDownloadPrompt() }
                )
            } else if (activeDownload.modelId == model.id) {
                DownloadProgressDialog(
                    activeDownload = activeDownload,
                    displayName = model.displayName,
                    onCancel = { ModelDownloadService.cancel(context); dismissDownloadPrompt() },
                    onContinueInBackground = { hideDownloadDialog() }
                )
            }
        }

        stillDownloadingModel?.let { downloadingModel ->
            StillDownloadingDialog(
                model = downloadingModel,
                onShowProgress = { downloadDialogHidden = false; dismissStillDownloadingDialog() },
                onDismiss = { dismissStillDownloadingDialog() }
            )
        }
    }

    /** Renders the result/error dialog, the Explore-unsupported dialog, and the heat warning. Call
     *  once in composition. */
    @Composable
    fun ResultDialogs() {
        dialogState?.let { (title, msg) ->
            AlertDialog(
                onDismissRequest = { dismissDialog() },
                title = { Text(title) },
                text = { Text(msg, fontSize = 18.sp) },
                confirmButton = { Button(onClick = { dismissDialog() }) { Text("OK") } }
            )
        }
        // "Continue Anyway" calls ThermalGuard.dismissForThisSession() and just closes the dialog —
        // it doesn't retry whatever was refused; the user presses the same button again, same as
        // dismissing any other blocking dialog here. See blockedByHeat()'s doc for why.
        if (showThermalWarning) {
            AlertDialog(
                onDismissRequest = { dismissThermalWarning() },
                title = { Text("Phone Is Hot") },
                text = { Text(ThermalGuard.HOT_MESSAGE, fontSize = 18.sp) },
                confirmButton = { Button(onClick = { dismissThermalWarning() }) { Text("Wait") } },
                dismissButton = { Button(onClick = { continueDespiteHeat() }) { Text("Continue Anyway") } }
            )
        }
    }

}

/** Creates and remembers an [AnalysisSessionController] scoped to this composition, disposing
 *  its [SpeechRecognizer] when it leaves composition. */
@Composable
fun rememberAnalysisSessionController(context: Context, prefs: PreferencesManager): AnalysisSessionController {
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val controller = remember { AnalysisSessionController(context, prefs, coroutineScope) }
    DisposableEffect(controller) { onDispose { controller.releaseSpeechRecognizer() } }
    return controller
}
