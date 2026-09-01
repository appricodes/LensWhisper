package com.appricodes.lens_whisper.inference

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import com.appricodes.lens_whisper.data.OfflineModelsRepository
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.domain.OfflineModelInfo
import com.appricodes.lens_whisper.utils.DeviceMemoryDetector
import com.appricodes.lens_whisper.utils.ImageScaling
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Channel
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * Runs on-device analysis via LiteRT-LM against whichever downloaded model is currently
 * selected. Only one model is ever loaded at a time (they're 1-3GB+) — switching the selected
 * model closes the previous engine and loads the new one on the next query. Loading is slow
 * (up to ~10s normally), so it's cached across calls and only reloaded when the model changes.
 *
 * Memory safety net (see [DeviceMemoryDetector] for the *before* check, applied at selection
 * time): the LiteRT-LM engine itself gives no cancellation or progress API, and a genuinely
 * out-of-memory device doesn't throw a catchable exception here — it thrashes (heavy swapping)
 * for minutes rather than failing fast, which is indistinguishable from a real hang from the
 * caller's side. [ensureEngine] and [analyze]'s inference call are both wrapped with a generous
 * timeout as a *during* check: if loading/inference is taking dramatically longer than normal,
 * that's treated as a probable low-memory thrash and reported to the user, rather than leaving
 * the UI stuck indefinitely. This can't force-stop the underlying native call (there's no API for
 * that) — the abandoned attempt keeps running on its own thread and is closed automatically if it
 * ever does complete (see the `invokeOnCompletion` handlers below) — but it does stop the *app*
 * from being unresponsive for minutes on end.
 */
object OfflineInferenceEngine {
    private const val TAG = "OfflineInferenceEngine"
    private const val LOAD_TIMEOUT_MS = 60_000L
    private const val INFERENCE_TIMEOUT_MS = 90_000L

    /** Caps every on-device response at 200 tokens (roughly 150-180 words for these models). This
     *  is a hard backstop on top of the word limits in [com.appricodes.lens_whisper.utils.OfflinePromptBuilder]
     *  ("under 20/150 words"), which the model is free to ignore — small models routinely run past a
     *  word count asked for in the prompt. This caps generation time and battery cost on-device
     *  regardless of whether the model cooperates; the streaming path also has its own
     *  repeated-sentence cutoff (see `AnalysisSessionController.runStreamingAnalysis`) for the more
     *  common failure mode of a small model looping well before this token cap is reached.
     *
     *  This is the baseline for every call. A Gemma call with reasoning enabled uses a higher,
     *  effort-scaled cap instead — see [reasoningPlanFor] — since the model's chain-of-thought has
     *  to fit inside the same budget as its actual answer. */
    private const val MAX_OUTPUT_TOKENS = 200

    /** Published so the UI can show a blocking "Loading <model>…" alert while a model is
     *  actually loading into memory, and auto-dismiss once it's ready — whether loading was
     *  triggered eagerly by [preload] (right when the model is selected) or lazily by the first
     *  [analyze] call. */
    sealed class LoadState {
        data object Idle : LoadState()
        data class Loading(val modelId: String) : LoadState()
        data class Ready(val modelId: String) : LoadState()
        data class Failed(val modelId: String, val message: String) : LoadState()
    }

    private val _loadState = MutableStateFlow<LoadState>(LoadState.Idle)
    val loadState: StateFlow<LoadState> = _loadState

    /** True while the OS has told the app memory is critically low (via [registerMemoryPressureMonitor]),
     *  reset back to false the next time a load starts. The UI can show a stronger warning than
     *  "loading…" when this is true partway through, since it's a real signal from the system —
     *  not a guess based on elapsed time like the timeouts above. */
    private val _lowMemoryPressure = MutableStateFlow(false)
    val lowMemoryPressure: StateFlow<Boolean> = _lowMemoryPressure

    private var loadedEngine: Engine? = null
    private var loadedModelId: String? = null
    private val mutex = Mutex()

    // Deliberately not tied to any caller's coroutine scope: a load/inference attempt that times
    // out must keep running here (so it can be closed if it ever finishes) independent of
    // whatever the UI does next — see the class doc.
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Registers for system memory-pressure callbacks — call once from `LensWhisperApplication.onCreate`.
     *
     *  Also releases the loaded model the moment the user leaves the app: [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN]
     *  is Android's one-shot signal that none of the process's UI is visible any more — fired on
     *  Home, on switching to another app, or on backing out of the last activity, regardless of
     *  which of the app's several activities was in front. That makes it a better hook here than
     *  an `onDestroy`/`onStop` in each activity individually: this app has no single "root"
     *  activity whose lifecycle stands in for the whole app, and per-activity `onPause` already
     *  fires on the ordinary "camera screen resumes MediaDescriber's onResume runs first" activity
     *  swaps this app does internally, which would release the model on every such swap, not just
     *  on actually leaving the app.
     *
     *  Trade-off worth knowing: a brief switch away and straight back (checking a notification,
     *  say) now costs a full reload — up to [LOAD_TIMEOUT_MS] worst case, ~10s normally — on
     *  return, where previously the model stayed warm. That's the deliberate exchange for not
     *  holding a 1-3GB model in memory for a backgrounded, screen-reader-driven app the user isn't
     *  looking at. */
    fun registerMemoryPressureMonitor(app: android.app.Application) {
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                    _lowMemoryPressure.value = true
                    Log.w(TAG, "System reported critical memory pressure (level=$level) while loadState=${_loadState.value}")
                }
                if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && loadedEngine != null) {
                    Log.i(TAG, "App backgrounded (all UI hidden) — releasing ${loadedModelId}")
                    release()
                }
            }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            @Deprecated("Deprecated in Java") override fun onLowMemory() { _lowMemoryPressure.value = true }
        })
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun ensureEngine(context: Context, model: OfflineModelInfo): Engine = mutex.withLock {
        loadedEngine?.let { if (loadedModelId == model.id) { _loadState.value = LoadState.Ready(model.id); return@withLock it } }

        // Release whatever's currently loaded (a different model, if any) BEFORE checking whether
        // the new one will fit. The check below reads system-wide available memory, so it has to
        // see memory as it will actually be once the old model is gone — checking first and
        // closing after would spuriously fail a switch between two similarly-sized models whose
        // combined size doesn't fit, even though either one alone fits fine. This is also the
        // actual point where "switching models frees the old one's memory" happens.
        loadedEngine?.close()
        loadedEngine = null
        loadedModelId = null
        _loadState.value = LoadState.Idle

        // Hard pre-check, right before actually starting: if free memory is already below the
        // model's raw file size, it cannot possibly load — fail immediately with a clear message
        // instead of spending a minute finding that out the hard way. This is deliberately a much
        // more extreme floor than DeviceMemoryDetector's selection-time heuristic (which only
        // *warns and lets the user proceed*) — it only trips when things are unambiguously dire.
        val availableNow = DeviceMemoryDetector.availableRamBytes(context)
        if (availableNow < model.sizeBytes) {
            val message = "Not enough free memory to load ${model.displayName} right now (only " +
                "${availableNow / 1_000_000}MB free). Close other apps or choose a smaller model."
            _loadState.value = LoadState.Failed(model.id, message)
            throw IllegalStateException(message)
        }

        _loadState.value = LoadState.Loading(model.id)
        _lowMemoryPressure.value = false

        val modelFile = OfflineModelsRepository(context.applicationContext).localFileFor(model)

        suspend fun attemptLoad(useCpuVision: Boolean): Engine {
            val engineConfig = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                visionBackend = if (useCpuVision) Backend.CPU() else Backend.GPU(),
                cacheDir = context.applicationContext.cacheDir.path
            )
            val loadJob = engineScope.async {
                val engine = Engine(engineConfig)
                engine.initialize()
                engine
            }
            return withTimeoutOrNull(LOAD_TIMEOUT_MS) { loadJob.await() }
                ?: run {
                    Log.w(TAG, "Loading ${model.id} exceeded ${LOAD_TIMEOUT_MS}ms — likely a low-memory thrash, giving up")
                    // If the abandoned load eventually does finish, nothing is using it — close it
                    // right away instead of leaking it for the rest of the process's life.
                    loadJob.invokeOnCompletion { cause -> if (cause == null) runCatching { loadJob.getCompleted().close() } }
                    val message = "Loading ${model.displayName} is taking much longer than usual — your device " +
                        "likely doesn't have enough free memory for this model. Try a smaller model."
                    _loadState.value = LoadState.Failed(model.id, message)
                    throw IllegalStateException(message)
                }
        }

        // GPU is tried first for every model on every device — a per-(model, device) preference
        // learned from a real failure (see PreferencesManager.getVisionBackendOverride) is the only
        // thing that skips straight to CPU. If GPU fails right here at load time (rather than later,
        // at conversation time — see analyze()'s own fallback below for that case) and this is the
        // first time we're seeing it, retry once on CPU and remember the result.
        val prefs = PreferencesManager(context.applicationContext)
        val engine = if (prefs.getVisionBackendOverride(model.id) == "CPU") {
            attemptLoad(useCpuVision = true)
        } else {
            try {
                attemptLoad(useCpuVision = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "GPU vision init failed for ${model.id}, retrying once on CPU", e)
                prefs.setVisionBackendOverride(model.id, "CPU")
                attemptLoad(useCpuVision = true)
            }
        }

        loadedEngine = engine
        loadedModelId = model.id
        _loadState.value = LoadState.Ready(model.id)
        engine
    }

    /** Eagerly loads [model] into memory right away (rather than waiting for the first
     *  [analyze] call) so selecting a model shows its loading cost immediately, via [loadState],
     *  instead of surfacing as unexplained lag the first time the user taps a button. */
    suspend fun preload(context: Context, model: OfflineModelInfo) {
        try {
            ensureEngine(context, model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Preload failed for model ${model.id}", e)
            _loadState.value = LoadState.Failed(model.id, e.localizedMessage ?: "Failed to load model")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reasoning effort — Gemma 4 only.
    //
    // LiteRT-LM's Thinking Mode (ai.google.dev/gemma/docs/core/prompt-formatting-gemma4) is a
    // Gemma-4-family feature, not a generic LiteRT-LM one: enabled by putting the literal control
    // token "<|think|>" at the start of the system turn, after which the model emits its reasoning
    // wrapped as "<|channel>thought ... <channel|>" before its real answer, in the same turn.
    // Qwen2-VL-2B (this predates Qwen3's own hybrid thinking mode), SmolVLM2 and LFM2.5-VL have no
    // such mode — nothing in their model cards, litert-community's docs, or LiteRT-LM's own
    // announcement (which scopes Thinking Mode explicitly to "the Gemma 4 model family") mentions
    // one — so [reasoningPlanFor] returns a no-op plan for every model but Gemma, regardless of
    // [PreferencesManager.reasoningEffort]: sending Gemma-only control tokens to a model that was
    // never trained on them risks degrading its output for no benefit, not just doing nothing.
    //
    // [ThinkingConfig] (enableThinking/thinkingTokenBudget) is a genuine, native-wired parameter —
    // confirmed by decompiling litertlm-android 0.16.1: Engine.createConversation and every
    // Conversation.sendMessage*/sendMessageAsync* overload pass it straight into the matching
    // LiteRtLmJni.native* call, not an inert field — so it's set here too, alongside the literal
    // prompt token, on the theory a redundant enable is harmless where a missing one would silently
    // produce no reasoning at all. Whether thinkingTokenBudget is actually *enforced* by the native
    // side is less certain — google-ai-edge/LiteRT-LM#2424 is an open, unanswered feature request
    // (filed 2026-06-01) asking for exactly this. If it turns out not to be, Low/Medium/High simply
    // collapse to "reasoning on, unbounded", which is degraded, not wrong.

    /** [PreferencesManager.reasoningEffort]'s valid values. */
    const val REASONING_NONE = "none"
    const val REASONING_LOW = "low"
    const val REASONING_MEDIUM = "medium"
    const val REASONING_HIGH = "high"
    val ALL_REASONING_EFFORTS = listOf(REASONING_NONE, REASONING_LOW, REASONING_MEDIUM, REASONING_HIGH)

    /** One line per level for the Settings picker. */
    fun reasoningEffortDisplayName(effort: String): String = when (effort) {
        REASONING_NONE -> "No reasoning"
        REASONING_LOW -> "Low"
        REASONING_MEDIUM -> "Medium"
        REASONING_HIGH -> "High"
        else -> effort
    }

    /** Only Gemma is known to support Thinking Mode — see this section's doc. Matches
     *  [OfflineModelInfo.family] as declared in res/raw/offline_models.json. */
    private const val GEMMA_FAMILY = "Gemma 4"

    /** Gemma 4's own reasoning-channel wrapper (see this section's doc): the literal control token
     *  that turns reasoning on, and the literal start/end text the model wraps its chain-of-thought
     *  in, before its real answer, in the same turn. */
    private const val THINK_ENABLE_TOKEN = "<|think|>"
    private const val THOUGHT_CHANNEL_NAME = "thinking"
    private const val THOUGHT_START = "<|channel>thought"
    private const val THOUGHT_END = "<channel|>"

    private class ReasoningPlan(
        val systemInstructionPrefix: String,
        val thinkingConfig: ThinkingConfig?,
        val channels: List<Channel>,
        val maxOutputToken: Int,
        val inferenceTimeoutMs: Long
    )

    private val NO_REASONING = ReasoningPlan(
        systemInstructionPrefix = "",
        thinkingConfig = null,
        channels = emptyList(),
        maxOutputToken = MAX_OUTPUT_TOKENS,
        inferenceTimeoutMs = INFERENCE_TIMEOUT_MS
    )

    /** Everything [analyze]/[analyzeStreaming] need to run one query at [effort] against [model] —
     *  [NO_REASONING] (a no-op, byte-identical-to-before plan) for every model but Gemma, or when
     *  [effort] is [REASONING_NONE]. Budgets are real token counts, not just an on/off switch, in
     *  case thinkingTokenBudget turns out to be enforced (see this section's doc); [maxOutputToken]
     *  and [inferenceTimeoutMs] are both widened well past the reasoning budget so a thinking pass
     *  doesn't eat into, or time out, the answer that's meant to follow it. */
    private fun reasoningPlanFor(model: OfflineModelInfo, effort: String): ReasoningPlan {
        if (model.family != GEMMA_FAMILY || effort == REASONING_NONE) return NO_REASONING
        // Token budget and timeout are both set per tier directly, not derived from one another by
        // a formula: [INFERENCE_TIMEOUT_MS]'s own 90s for 200 tokens is dominated by a mostly-fixed
        // image-prefill cost, not a flat per-token rate, so naively scaling it by token count would
        // wildly overestimate what a longer response actually needs — and REASONING_HIGH's budget is
        // deliberately a large finite number to begin with (2000, not -1/unbounded), for the same
        // "don't let an actually-unbounded pass trip the low-memory-thrash timeout" reason the
        // timeout itself has to stay bounded (see this object's class doc for what that timeout is
        // guarding against).
        val (thinkingTokenBudget, timeoutMs) = when (effort) {
            REASONING_LOW -> 256 to 150_000L
            REASONING_MEDIUM -> 1024 to 240_000L
            else -> 2000 to 360_000L // REASONING_HIGH
        }
        return ReasoningPlan(
            systemInstructionPrefix = THINK_ENABLE_TOKEN,
            thinkingConfig = ThinkingConfig(enableThinking = true, thinkingTokenBudget = thinkingTokenBudget),
            channels = listOf(Channel(channelName = THOUGHT_CHANNEL_NAME, start = THOUGHT_START, end = THOUGHT_END)),
            maxOutputToken = MAX_OUTPUT_TOKENS + thinkingTokenBudget,
            inferenceTimeoutMs = timeoutMs
        )
    }

    /** Removes every *complete* `<|channel>thought ... <channel|>` span from [text] — the building
     *  block [visiblePrefix] uses below. Does *not* by itself handle a span that opens and never
     *  closes (see [visiblePrefix] for why that additional case matters and how it's covered); call
     *  [visiblePrefix], not this, from [analyze]/[analyzeStreaming]. */
    private fun stripThoughtChannel(text: String): String =
        text.replace(Regex(Regex.escape(THOUGHT_START) + ".*?" + Regex.escape(THOUGHT_END), RegexOption.DOT_MATCHES_ALL), "")

    /** How much of [rawCumulative] (everything received so far — the full response, for
     *  [analyze]'s single-shot case, or the running total for one [analyzeStreaming] call) is safe
     *  to show the user right now, when [plan] has reasoning enabled: [stripThoughtChannel] applied
     *  first, then everything up to — but not including — any thought-channel span that *hasn't*
     *  closed, and never a trailing fragment that could still turn into the start of one with more
     *  text. That "unclosed span" case is deliberately handled the same way whether or not more text
     *  is still coming: [analyzeStreaming] must not leak a chain-of-thought fragment before this
     *  function has enough text to recognize a split marker, and [analyze] must not leak the *rest of
     *  a whole response* if the model exhausts its output budget mid-thought and never reaches
     *  `<channel|>` at all — both failure modes are "read raw reasoning aloud to a blind user" if
     *  left unhandled, so both get the same defensive backstop, not just the SDK's own
     *  [Channel]-based separation (which should normally make this whole function a no-op — it only
     *  matters if that separation doesn't fire for some reason: an SDK version drift, a model
     *  emitting a variant). Identity when [plan] has no reasoning enabled — true of every call
     *  except a Gemma one with a non-"none" [PreferencesManager.reasoningEffort] — so every other
     *  model and prompt is untouched by any of this. */
    private fun visiblePrefix(rawCumulative: String, plan: ReasoningPlan): String {
        if (plan.thinkingConfig == null) return rawCumulative
        val cleaned = stripThoughtChannel(rawCumulative)
        val openIdx = cleaned.indexOf(THOUGHT_START)
        val truncated = if (openIdx >= 0) cleaned.substring(0, openIdx) else cleaned
        for (len in minOf(THOUGHT_START.length, truncated.length) downTo 1) {
            if (truncated.endsWith(THOUGHT_START.substring(0, len))) return truncated.substring(0, truncated.length - len)
        }
        return truncated
    }

    /**
     * Runs one query against [model] with the given image(s) and prompt. Returns the model's
     * text response, or an "ERROR: ..." string on failure — matching [com.appricodes.lens_whisper.network.LocalServerEngine]'s
     * convention so callers can handle both the same way.
     */
    suspend fun analyze(
        context: Context,
        model: OfflineModelInfo,
        images: List<Bitmap>,
        userPrompt: String,
        systemInstruction: String,
        maxImageDimension: Int
    ): String = withContext(Dispatchers.IO) {
        val prefs = PreferencesManager(context.applicationContext)
        val plan = reasoningPlanFor(model, prefs.reasoningEffort)

        suspend fun runOnce(): String {
            val engine = ensureEngine(context, model)
            val conversationConfig = ConversationConfig(
                systemInstruction = Contents.of(Content.Text(plan.systemInstructionPrefix + systemInstruction)),
                maxOutputToken = plan.maxOutputToken,
                thinkingConfig = plan.thinkingConfig,
                channels = plan.channels
            )
            return engine.createConversation(conversationConfig).use { conversation ->
                val contents = mutableListOf<Content>()
                for (bitmap in images) {
                    val scaled = ImageScaling.scaleToMax(bitmap, maxImageDimension)
                    val stream = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    contents.add(Content.ImageBytes(stream.toByteArray()))
                }
                // Text after image(s), matching the model's expected token ordering.
                contents.add(Content.Text(userPrompt))

                val inferenceJob = engineScope.async { conversation.sendMessage(Contents.of(contents)).toString() }
                val rawResult = withTimeoutOrNull(plan.inferenceTimeoutMs) { inferenceJob.await() }
                    ?: run {
                        Log.w(TAG, "Inference on ${model.id} exceeded ${plan.inferenceTimeoutMs}ms — likely a low-memory thrash")
                        inferenceJob.invokeOnCompletion { /* nothing owns the result; let it be discarded */ }
                        throw IllegalStateException(
                            "Analysis with ${model.displayName} is taking much longer than usual — your device " +
                                "likely doesn't have enough free memory for this model right now."
                        )
                    }
                // See visiblePrefix's doc — a no-op unless reasoning was actually enabled for this
                // call, and expected to be a no-op even then (the Channel config above should
                // already keep thought-channel text out of rawResult).
                val visible = visiblePrefix(rawResult, plan)
                // The model spent its whole budget still "thinking" and never reached a visible
                // answer (see visiblePrefix's doc on why an unclosed thought span suppresses
                // everything after it, not just the span itself) — silently returning "" here would
                // read to the caller as a normal, empty response instead of the real cause, and
                // AnalysisSessionController's speak() silently drops an empty string, so a blind user
                // would get no answer and no explanation either. Say so instead.
                if (visible.isBlank() && rawResult.isNotBlank()) {
                    "Reasoning used the full response budget with no visible answer. Try again, or lower Reasoning Effort in Settings."
                } else {
                    visible
                }
            }
        }

        // Some GPU/vision incompatibilities only surface once a real conversation with an actual
        // image runs — not at engine-init time (see ensureEngine's own, earlier fallback, and
        // PreferencesManager.getVisionBackendOverride) — confirmed on Exynos 2100/Mali with
        // `lfm2_5_vl_450m` (2026-08-24): engine.initialize() succeeded, createConversation() didn't.
        // Retry once on CPU before giving up, and remember it so every later load on this device
        // skips straight to CPU instead of re-failing on GPU every time.
        suspend fun runWithCpuFallback(): String = try {
            runOnce()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (prefs.getVisionBackendOverride(model.id) == "CPU") throw e
            Log.w(TAG, "Vision inference failed for ${model.id}, retrying once on CPU", e)
            prefs.setVisionBackendOverride(model.id, "CPU")
            release()
            runOnce()
        }

        try {
            runWithCpuFallback()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not touching loadState here: if this came from ensureEngine() failing to load,
            // it already set LoadState.Failed itself before throwing. If the model loaded fine
            // and only this one query failed (including the inference timeout above), the
            // engine is still perfectly usable for the next query — loadState should stay
            // Ready, not regress to Failed and misleadingly suggest the *model* is broken. This
            // "ERROR: ..." string is what actually surfaces the failure to the user, via
            // AnalysisSessionController's existing error dialog.
            Log.e(TAG, "On-device analysis failed for model ${model.id}", e)
            "ERROR: On-device analysis failed. ${e.localizedMessage ?: "Unknown error"}"
        }
    }

    /**
     * Streaming counterpart to [analyze] — emits the model's response incrementally as it's
     * generated, instead of waiting for the whole thing. Each emission is a text *delta* (the new
     * fragment only, safe to just append) — never mind that [com.google.ai.edge.litertlm.Conversation.sendMessageAsync]'s
     * `Flow<Message>` doesn't document whether each `Message` is cumulative-so-far or a fresh
     * delta; the self-correcting check below (`text.startsWith(cumulativeSoFar)`) works out the
     * delta correctly either way, from the first emission on.
     *
     * Same CPU/GPU vision fallback as [analyze] (see its doc), just applied around the whole
     * collection instead of a single call — if streaming fails partway through on GPU and this
     * model hasn't been pinned to CPU yet, the whole request is retried once, from scratch, on CPU.
     * A caller can't tell mid-stream whether a retry happened; it only ever sees a clean sequence
     * of deltas or a thrown exception (unlike [analyze], errors are NOT converted to an "ERROR: ..."
     * string here — the caller decides how to surface a failure that happened after some deltas
     * already went out).
     */
    fun analyzeStreaming(
        context: Context,
        model: OfflineModelInfo,
        images: List<Bitmap>,
        userPrompt: String,
        systemInstruction: String,
        maxImageDimension: Int
    ): Flow<String> = flow {
        val prefs = PreferencesManager(context.applicationContext)
        val plan = reasoningPlanFor(model, prefs.reasoningEffort)

        suspend fun collectOnce(emit: suspend (String) -> Unit) {
            val engine = ensureEngine(context, model)
            val conversationConfig = ConversationConfig(
                systemInstruction = Contents.of(Content.Text(plan.systemInstructionPrefix + systemInstruction)),
                maxOutputToken = plan.maxOutputToken,
                thinkingConfig = plan.thinkingConfig,
                channels = plan.channels
            )
            engine.createConversation(conversationConfig).use { conversation ->
                val contents = mutableListOf<Content>()
                for (bitmap in images) {
                    val scaled = ImageScaling.scaleToMax(bitmap, maxImageDimension)
                    val stream = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    contents.add(Content.ImageBytes(stream.toByteArray()))
                }
                contents.add(Content.Text(userPrompt))

                var cumulativeSoFar = ""   // raw SDK text, exactly as received — see below.
                // How much of visiblePrefix(cumulativeSoFar, plan) has already been emitted. When
                // plan.thinkingConfig is null (every call except a Gemma one with reasoning on),
                // visiblePrefix is the identity function, so this behaves exactly like the plain
                // delta-of-cumulativeSoFar logic this replaced — see visiblePrefix's own doc.
                var emittedVisibleLength = 0
                val completed = try {
                    withTimeoutOrNull(plan.inferenceTimeoutMs) {
                        conversation.sendMessageAsync(Contents.of(contents)).collect { message ->
                            val text = message.toString()
                            // Message.toString() isn't documented as cumulative-so-far vs. a fresh
                            // delta — this self-corrects either way, from the first emission on.
                            val rawDelta = if (text.startsWith(cumulativeSoFar)) text.substring(cumulativeSoFar.length) else text
                            cumulativeSoFar += rawDelta
                            if (rawDelta.isEmpty()) return@collect
                            val visible = visiblePrefix(cumulativeSoFar, plan)
                            if (visible.length > emittedVisibleLength) {
                                val delta = visible.substring(emittedVisibleLength)
                                emittedVisibleLength = visible.length
                                // Verifies this is genuinely streaming, not one lump at the end — compare
                                // consecutive timestamps of this exact line in logcat.
                                Log.d(TAG, "STREAM[offline/${model.id}] chunk (${delta.length} chars): ${delta.take(60).replace("\n", "\\n")}")
                                emit(delta)
                            }
                        }
                        true
                    }
                } catch (e: CancellationException) {
                    // A caller cancelling this collection mid-stream (e.g. "Explore and Describe"
                    // replacing an in-flight tap's analysis with a new one — see
                    // SegmentationExploreActivity) propagates a CancellationException through here,
                    // straight into this use{} block's implicit close() right after this rethrows.
                    // Closing the native Conversation while generation is still active crashes the
                    // whole process with a native SIGSEGV inside the GPU delegate — confirmed
                    // on-device, twice, identical signature both times. cancelProcess() halts
                    // generation immediately and synchronously (per litertlm-android's own contract:
                    // "you do not need to destroy and recreate the session to free up resources"),
                    // so by the time close() actually runs, there's nothing active left to tear down
                    // out from under.
                    conversation.cancelProcess()
                    throw e
                }
                if (completed != true) {
                    Log.w(TAG, "Streaming inference on ${model.id} exceeded ${plan.inferenceTimeoutMs}ms — likely a low-memory thrash")
                    throw IllegalStateException(
                        "Analysis with ${model.displayName} is taking much longer than usual — your device " +
                            "likely doesn't have enough free memory for this model right now."
                    )
                }
                // Same "spent the whole budget still thinking" case analyze() guards against — see
                // its own comment. Here it means the loop above never emitted anything at all.
                if (emittedVisibleLength == 0 && cumulativeSoFar.isNotBlank()) {
                    emit("Reasoning used the full response budget with no visible answer. Try again, or lower Reasoning Effort in Settings.")
                }
            }
        }

        try {
            collectOnce { emit(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (prefs.getVisionBackendOverride(model.id) == "CPU") throw e
            Log.w(TAG, "Streaming vision inference failed for ${model.id}, retrying once on CPU", e)
            prefs.setVisionBackendOverride(model.id, "CPU")
            release()
            collectOnce { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    /** Releases the loaded model's resources, e.g. when the user picks a different one. */
    fun release() {
        loadedEngine?.close()
        loadedEngine = null
        loadedModelId = null
        _loadState.value = LoadState.Idle
        _lowMemoryPressure.value = false
    }
}
