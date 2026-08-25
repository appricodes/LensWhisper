package com.appricodes.lens_whisper.ui.analysis

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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
import com.appricodes.lens_whisper.inference.runSelectedOfflineModelAnalysisStreaming
import com.appricodes.lens_whisper.offline.OfflineTranslator
import com.appricodes.lens_whisper.service.ModelDownloadService
import com.appricodes.lens_whisper.service.ModelDownloadState
import com.appricodes.lens_whisper.ui.components.DownloadProgressDialog
import com.appricodes.lens_whisper.ui.components.DownloadPromptDialog
import com.appricodes.lens_whisper.ui.components.StillDownloadingDialog
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.appricodes.lens_whisper.utils.ProcessingSoundPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** What a screen-specific frame source hands back to [AnalysisSessionController.runAnalysis].
 *  [onlineCallStreaming]/[localServerCallStreaming] are null wherever streaming isn't offered for
 *  that capture (Explore) — [AnalysisSessionController] falls back to [onlineCall]/[localServerCall]
 *  in that case regardless of the user's streaming setting. */
data class CapturedBundle(
    val offlineImages: List<Bitmap>,
    val onlineCall: suspend () -> String,
    val onlineCallStreaming: (() -> Flow<String>)? = null,
    val localServerCall: suspend () -> String = { "ERROR: Local Server isn't available for this action." },
    val localServerCallStreaming: (() -> Flow<String>)? = null
)

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

    var showExploreUnsupportedDialog by mutableStateOf(false)
        private set

    var showOnlineGateDialog by mutableStateOf(false)
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

    // 4 slots: Online only ever populates/reads indices 0-1 (via the general, non-slot-targeted
    // voice query below); the offline catalog uses all 4, each independently voice-recordable via
    // long-press (see OfflineVlmButtonProvider). Backed by SnapshotStateList so mutating an
    // element in place (not reassigning the whole list) still triggers recomposition.
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

    private fun announceBusy() {
        AccessibilityUtils.announceToTalkBack(context, "Please wait for the current analysis to finish.")
    }

    /** True for the on-device VLM catalog (Gemma4/FastVLM/Qwen-VL/...) — those models are asked
     *  to answer in the target language via their system instruction, but small on-device models
     *  frequently ignore that, so [OfflineTranslator.translate] is applied as a fallback. Not
     *  MLKit (which never routes through here — see `MlkitButtonProvider`), Online, or Local
     *  Server (both already answer in the target language via the same system instruction). */
    private fun isOfflineVlmSelected(): Boolean =
        prefs.selectedModelId != "online" && prefs.selectedModelId != "mlkit" && prefs.selectedModelId != "local_server"

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
    fun dismissExploreUnsupportedDialog() { showExploreUnsupportedDialog = false }
    fun dismissOnlineGateDialog() { showOnlineGateDialog = false }
    fun dismissDownloadPrompt() { pendingDownloadModel = null; pendingDownloadAction = null; downloadDialogHidden = false }
    fun hideDownloadDialog() { downloadDialogHidden = true }
    fun dismissStillDownloadingDialog() { stillDownloadingModel = null }

    /** Starts the repeating "processing" chime — call once frames are actually ready to send. */
    fun startProcessingChime() {
        activeAudioFeedbackJob = coroutineScope.launch(Dispatchers.IO) {
            while (true) {
                ProcessingSoundPlayer.playChime()
                delay(1100)
            }
        }
    }

    /**
     * Runs one analysis request: [captureFrames] does whatever screen-specific work is needed
     * (capture a camera photo, extract video frames, load a static image) and returns a
     * [CapturedBundle], or null if capture failed outright. Replaces both
     * `AdvancedCameraScreen.runAnalysis`/`performAnalysis` and `MediaDescriberScreen.runAnalysis`.
     */
    suspend fun runAnalysis(config: PromptConfig, label: String, captureFrames: suspend (PromptConfig) -> CapturedBundle?) {
        if (isAnalyzing) {
            announceBusy()
            return
        }
        isAnalyzing = true
        lastPrompt = config
        try {
            val bundle = captureFrames(config)
            if (bundle == null) {
                handleResponse("ERROR: Could not capture the image.")
                return
            }
            // Offline models always stream; Online streams only when the user opted in (Settings,
            // default off — see PreferencesManager.streamingEnabled) since it changes the wire
            // protocol process.php speaks (NDJSON vs. one JSON object, see backend/readme.md).
            // MLKit never reaches either branch (see isOfflineVlmSelected). Explore is excluded from
            // both — its structured grid-JSON response isn't sentence-shaped (OfflineVlmButtonProvider
            // doesn't even offer Explore offline, and bundle.onlineCallStreaming is null for it).
            val streamingFlow: Flow<String>? = when {
                isOfflineVlmSelected() && config.id != "explore" ->
                    runSelectedOfflineModelAnalysisStreaming(context, prefs, config, bundle.offlineImages)
                prefs.selectedModelId == "online" && prefs.streamingEnabled && config.id != "explore" ->
                    bundle.onlineCallStreaming?.invoke()
                prefs.selectedModelId == "local_server" && prefs.streamingEnabled && config.id != "explore" ->
                    bundle.localServerCallStreaming?.invoke()
                else -> null
            }
            if (streamingFlow != null) {
                runStreamingAnalysis(streamingFlow, translate = isOfflineVlmSelected())
            } else {
                val response = runSelectedModelAnalysis(
                    context = context,
                    prefs = prefs,
                    config = config,
                    offlineImages = bundle.offlineImages,
                    onlineCall = bundle.onlineCall,
                    localServerCall = bundle.localServerCall
                )
                handleResponse(response)
            }
        } finally {
            isAnalyzing = false
            activeAudioFeedbackJob?.cancel()
        }
    }

    /**
     * Streaming counterpart to [handleResponse] — used for on-device models always, and for the
     * Online Describer when [PreferencesManager.streamingEnabled] is on (see the two branches in
     * [runAnalysis] that decide which [Flow] gets passed in here, if any). Speaks each complete
     * sentence (ending in `.`/`!`/`?`/newline, or their CJK/Persian equivalents — see
     * [findSentenceEnd]) via TalkBack as soon as it's been generated — and translated, if
     * [translate] is true — instead of waiting for and speaking the full response as one block.
     * [translate] mirrors [isOfflineVlmSelected]'s reasoning: only on-device models need the
     * client-side translation fallback, since Online already answers in the target language via
     * its own system prompt. See [runSelectedOfflineModelAnalysisStreaming] /
     * `OfflineInferenceEngine.analyzeStreaming` (offline) and `XaiVisionEngine`'s `*Streaming`
     * functions / backend/readme.md's "Streaming" section (online).
     */
    private suspend fun runStreamingAnalysis(flow: Flow<String>, translate: Boolean) {
        val fullText = StringBuilder()
        val pending = StringBuilder()
        var chimeStopped = false

        suspend fun speak(sentence: String) {
            val trimmed = sentence.trim()
            if (trimmed.isEmpty()) return
            val spoken = if (translate) OfflineTranslator.translate(context, trimmed, prefs) else trimmed
            AccessibilityUtils.announceToTalkBack(context, spoken)
        }

        try {
            flow.collect { delta ->
                if (!chimeStopped) {
                    // The chime represents "still waiting" — the moment real output starts
                    // arriving, it's no longer accurate (unlike the non-streaming path, which only
                    // learns a response exists once the whole thing is ready).
                    activeAudioFeedbackJob?.cancel()
                    chimeStopped = true
                }
                fullText.append(delta)
                pending.append(delta)
                var end = findSentenceEnd(pending)
                while (end != -1) {
                    speak(pending.substring(0, end + 1))
                    pending.delete(0, end + 1)
                    end = findSentenceEnd(pending)
                }
            }
            speak(pending.toString()) // whatever's left over (no trailing punctuation) still gets spoken
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
     * Repeats the last prompt run (voice query, tap, or custom), if any. [selectedModelId] guards
     * against the one way a multi-frame Story prompt can reach an on-device model even though no
     * offline button ever offers Story directly (see [com.appricodes.lens_whisper.ui.models.OfflineVlmButtonProvider]):
     * run Story while on Online, switch to an offline model, tap Repeat — [lastPrompt] is tracked
     * regardless of which model ran it, so without this check it would resurface here. Currently
     * blocks it for every offline model, not just `fastvlm_0_5b` — the request that added this was
     * scoped to FastVLM specifically, but the leak itself isn't model-specific, so neither is the fix.
     */
    suspend fun onRepeat(selectedModelId: String, captureFrames: suspend (PromptConfig) -> CapturedBundle?) {
        val prompt = lastPrompt
        when {
            prompt == null -> AccessibilityUtils.announceToTalkBack(context, "No previous action to repeat.")
            selectedModelId != "online" && selectedModelId != "local_server" && prompt.framesCount > 1 ->
                AccessibilityUtils.announceToTalkBack(context, "Story mode isn't available for on-device models.")
            else -> runAnalysis(prompt, prompt.title, captureFrames)
        }
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
        val rawCode = prefs.selectedLanguageCode
        val localeCode = if (rawCode.startsWith("fa", ignoreCase = true) || prefs.selectedLanguage.equals("Persian", ignoreCase = true)) "fa-IR" else rawCode
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
                if (targetCustomSlot != null) clearCustomSlot(targetCustomSlot)
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val recognizedQuery = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (targetCustomSlot != null) {
                    if (recognizedQuery.isNullOrBlank()) {
                        clearCustomSlot(targetCustomSlot)
                        AccessibilityUtils.announceToTalkBack(context, "No question heard. Custom ${targetCustomSlot + 1} cleared.")
                    } else {
                        _customPromptTexts[targetCustomSlot] = recognizedQuery
                        _customPromptTitles[targetCustomSlot] = truncatedTitle(recognizedQuery)
                        val config = PromptConfig("custom", _customPromptTitles[targetCustomSlot], recognizedQuery, 1, 0.5)
                        coroutineScope.launch { runAnalysis(config, config.title, captureFrames) }
                    }
                } else if (!recognizedQuery.isNullOrBlank()) {
                    val config = PromptConfig("custom", "Voice Query", recognizedQuery, 1, 0.5)
                    coroutineScope.launch { runAnalysis(config, "Voice Query", captureFrames) }
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

    /** Busy-check + Explore-support gating, shared verbatim between camera and media-describer Explore buttons. */
    fun onExplore(selectedModelId: String, captureFrame: suspend () -> Uri?, launchExplore: (Uri) -> Unit) {
        if (isAnalyzing) {
            announceBusy()
            return
        }
        if (!repository.isExploreSupported(selectedModelId)) {
            showExploreUnsupportedDialog = true
            return
        }
        isAnalyzing = true
        coroutineScope.launch {
            try {
                AccessibilityUtils.announceToTalkBack(context, "Entering exploration mode. Analyzing elements.")
                val uri = captureFrame()
                if (uri == null) {
                    AccessibilityUtils.announceToTalkBack(context, "Could not capture the current frame.")
                    return@launch
                }
                launchExplore(uri)
            } finally {
                isAnalyzing = false
            }
        }
    }

    /**
     * If [model] isn't downloaded yet, opens the download-size prompt instead of running
     * [action], and auto-resumes [action] once that model finishes downloading (observed via
     * [ObserveDownloads]). No-op passthrough for MLKit/Online (`model == null`). If [model] is
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

    /** Mirrors `ModelManagementActivity`'s online-eligibility check; gates every Online button tap. */
    fun checkOnlineEligibleOrGate(action: () -> Unit) {
        val eligible = prefs.freeCredits > 0 || prefs.purchasedCredits > 0
        if (eligible) action() else showOnlineGateDialog = true
    }

    /** The one gate every model-specific button (prompt tap, Explore, Repeat) should run
     *  through, regardless of which provider is active: online eligibility for the online model,
     *  download-redirect for an on-device catalog model, or straight through for MLKit/Local
     *  Server (no credits needed for the latter — its server doesn't meter usage). */
    fun gateForSelectedModel(selectedModelId: String, selectedOfflineModel: OfflineModelInfo?, action: () -> Unit) {
        when {
            selectedModelId == "online" -> checkOnlineEligibleOrGate(action)
            selectedOfflineModel != null -> checkDownloadedOrRedirect(selectedOfflineModel, action)
            else -> action()
        }
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

    /** Renders the result/error dialog and the Explore-unsupported dialog. Call once in composition. */
    @Composable
    fun ResultDialogs(onOpenModelManager: () -> Unit) {
        dialogState?.let { (title, msg) ->
            AlertDialog(
                onDismissRequest = { dismissDialog() },
                title = { Text(title) },
                text = { Text(msg, fontSize = 18.sp) },
                confirmButton = { Button(onClick = { dismissDialog() }) { Text("OK") } }
            )
        }
        if (showExploreUnsupportedDialog) {
            AlertDialog(
                onDismissRequest = { dismissExploreUnsupportedDialog() },
                title = { Text("Explore Not Supported") },
                text = { Text("The selected model can't be used for the Explore feature. Choose a different model to use Explore.", fontSize = 18.sp) },
                confirmButton = {
                    Button(onClick = { dismissExploreUnsupportedDialog(); onOpenModelManager() }) { Text("Open Model Manager") }
                },
                dismissButton = { Button(onClick = { dismissExploreUnsupportedDialog() }) { Text("Cancel") } }
            )
        }
    }

    /** Renders the "watch an ad to use the Online Describer" gate. Call once in composition.
     *  [onGetCredits] should open [com.appricodes.lens_whisper.ui.components.CreditsDialog]. */
    @Composable
    fun OnlineGateDialog(onGetCredits: () -> Unit) {
        if (!showOnlineGateDialog) return
        AlertDialog(
            onDismissRequest = { dismissOnlineGateDialog() },
            title = { Text("Online Describer") },
            text = {
                Text("You need credits (watch a short ad to earn some) to use the Online Describer.", fontSize = 16.sp)
            },
            confirmButton = {
                Button(onClick = { dismissOnlineGateDialog(); onGetCredits() }) { Text("Get Credits") }
            }
        )
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
