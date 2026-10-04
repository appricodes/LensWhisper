package com.appricodes.lens_whisper.inference

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
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
 * Memory safety net: right before a load starts — once the previous model has been closed and
 * its memory has had a moment to come back — [startLoadLocked] measures free memory and refuses a
 * model that can't fit ([DeviceMemoryDetector.canLoadNow]). Any load that fails, for that or any
 * other reason, switches the app back to Basic tasks ([failLoad]); so does a load during which the
 * system killed the app ([recoverFromInterruptedLoad]). Beyond that, the LiteRT-LM engine itself
 * gives no cancellation or progress API, and a genuinely
 * out-of-memory device doesn't throw a catchable exception here — it thrashes (heavy swapping)
 * for minutes rather than failing fast, which is indistinguishable from a real hang from the
 * caller's side. Model loading ([runLoad]) and inference are both wrapped with a generous
 * timeout as a *during* check: if loading/inference is taking dramatically longer than normal,
 * that's treated as a probable low-memory thrash and reported to the user, rather than leaving
 * the UI stuck indefinitely. This can't force-stop a native load (there's no API for that) — the
 * given-up load keeps running and keeps occupying [Slot] until it returns, then is closed — but
 * it does stop the *app* from being unresponsive for minutes on end.
 */
object OfflineInferenceEngine {
    private const val TAG = "OfflineInferenceEngine"
    private const val LOAD_TIMEOUT_MS = 60_000L
    // Widened from 90s alongside MAX_OUTPUT_TOKENS below (200 -> 800) - the fixed image-prefill
    // cost dominates the *first* couple hundred tokens' worth of the old 90s budget (see
    // reasoningPlanFor's doc for why the reasoning tiers deliberately don't scale their own
    // timeout by a formula either), but generation time past that is still roughly linear in
    // token count, so a 4x larger output cap needs meaningfully more than the old flat 90s to
    // avoid this timeout aborting a genuinely-still-working generation before it naturally
    // finishes or hits the loop detector. The +60s bump is a deliberately conservative guess, not
    // a measured number (an earlier pass here tried extrapolating the full 4x token ratio into a
    // +150s bump — undoing that, since nobody actually asked for a ~4-minute worst-case wait and
    // it was never profiled on-device). Revisit with real timing if 800-token responses are
    // routinely hitting this wall in practice.
    private const val INFERENCE_TIMEOUT_MS = 150_000L

    /** Caps every on-device response at 800 tokens (roughly 600-650 words for these models). This
     *  is a hard backstop on top of the word limits in [com.appricodes.lens_whisper.utils.OfflinePromptBuilder]
     *  ("under 20/150 words"), which the model is free to ignore — small models routinely run past a
     *  word count asked for in the prompt. This caps generation time and battery cost on-device
     *  regardless of whether the model cooperates; the streaming path also has its own loop
     *  detector (see `AnalysisSessionController.runStreamingAnalysis`'s trailing-repeat check) for
     *  the more common failure mode of a small model looping well before this token cap is reached.
     *
     *  This is the baseline for every call. A Gemma call with reasoning enabled uses a higher,
     *  effort-scaled cap instead — see [reasoningPlanFor] — since the model's chain-of-thought has
     *  to fit inside the same budget as its actual answer. */
    private const val MAX_OUTPUT_TOKENS = 800

    /** Published so the UI can show a blocking "Loading <model>…" alert while a model is
     *  actually loading into memory, and auto-dismiss once it's ready — whether loading was
     *  triggered eagerly by [preload] (right when the model is selected) or lazily by the first
     *  [analyze] call. */
    sealed class LoadState {
        data object Idle : LoadState()
        data class Loading(val modelId: String) : LoadState()
        data class Ready(val modelId: String) : LoadState()
        /** [switchedToBasic]: the failed model was the selected one, and the selection has been
         *  moved to Basic tasks — so the UI must show this even though it's no longer selected. */
        data class Failed(val modelId: String, val message: String, val switchedToBasic: Boolean = false) : LoadState()
    }

    private val _loadState = MutableStateFlow<LoadState>(LoadState.Idle)
    val loadState: StateFlow<LoadState> = _loadState

    /** True while the OS has told the app memory is critically low (via [registerMemoryPressureMonitor]),
     *  reset back to false the next time a load starts. The UI can show a stronger warning than
     *  "loading…" when this is true partway through, since it's a real signal from the system —
     *  not a guess based on elapsed time like the timeouts above. */
    private val _lowMemoryPressure = MutableStateFlow(false)
    val lowMemoryPressure: StateFlow<Boolean> = _lowMemoryPressure

    /**
     * The single place a model can live in memory. Every model-memory decision goes through this
     * one field (always read/written under [mutex]), and a new load only ever starts from [Empty] —
     * so there is never a moment where one model is loading or loaded while another is too.
     *
     * Why a [Loading] state at all, rather than just "loaded engine or null": a native load can't be
     * cancelled, and it's owned by *this* object, not by whoever asked for it. Previously a caller
     * cancelled mid-load (a LaunchedEffect whose activity got recreated around a lock/unlock, the
     * model picker's remembered scope going away, backing out of Manage Models) left its load
     * running untracked in the background while the slot still read "nothing loaded" — so the next
     * request started a *second* full load next to it, and found no memory for it.
     */
    private sealed class Slot {
        data object Empty : Slot()

        class Loading(val modelId: String) : Slot() {
            /** null = loaded fine, otherwise the user-facing failure message. Completes as soon as
             *  the outcome is known — for a timed-out load that's well before the native call
             *  actually returns, which is what [done] is for. */
            val outcome = CompletableDeferred<String?>()
            /** Completes once the native load has really returned and the slot has moved on. */
            val done = CompletableDeferred<Unit>()
            /** Set when nobody wants this model any more (released, superseded by a different model,
             *  or given up on after a timeout): close it the moment it finishes instead of keeping it. */
            @Volatile var closeWhenDone = false
        }

        class Loaded(val modelId: String, val engine: Engine) : Slot()
    }

    private var slot: Slot = Slot.Empty
    private val mutex = Mutex()

    /** When a model was last closed — see [SETTLE_AFTER_CLOSE_MS]. */
    @Volatile private var lastClosedAt = 0L

    /** How long after closing a model the next load waits before measuring free memory: the
     *  engine frees its memory on close, but the system's free-memory figure (and some GPU driver
     *  allocations) catch up a moment later, and measuring too early would refuse a model that
     *  fits. */
    private const val SETTLE_AFTER_CLOSE_MS = 700L

    /** How many conversations are running on the [Slot.Loaded] engine right now. Incremented only
     *  under [mutex] (in [acquireEngine]), decremented without it (see [withEngine]) — so while
     *  [mutex] is held and this reads 0, nobody can start using the engine, and it's safe to close. */
    private val activeUsers = MutableStateFlow(0)

    // Deliberately not tied to any caller's coroutine scope: a load/inference attempt must keep
    // running here independent of whatever the UI does next — see the class doc and [Slot].
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
     *  Except when the screen merely turned off: UI_HIDDEN fires on locking the phone too, and
     *  locking it while waiting for (or listening to) an answer is routine for a screen-reader
     *  user — releasing there meant every unlock cost a full reload for a model the user never
     *  actually left. So with the screen off the model stays loaded; if the system then genuinely
     *  needs the memory it says so with [ComponentCallbacks2.TRIM_MEMORY_BACKGROUND] (still
     *  delivered on API 34+, unlike the finer-grained levels), which does release it.
     *
     *  Trade-off worth knowing: a brief switch to another app and straight back (checking a
     *  notification, say) still costs a full reload — up to [LOAD_TIMEOUT_MS] worst case, ~10s
     *  normally — on return. That's the deliberate exchange for not holding a 1-3GB model in
     *  memory for a backgrounded app the user isn't looking at. */
    fun registerMemoryPressureMonitor(app: android.app.Application) {
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                // Exact match, not >=: UI_HIDDEN (20) and BACKGROUND (40) are numerically above
                // RUNNING_CRITICAL (15) but say nothing about memory being critically low.
                if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                    _lowMemoryPressure.value = true
                    Log.w(TAG, "System reported critical memory pressure while loadState=${_loadState.value}")
                }
                val screenOn = app.getSystemService(PowerManager::class.java)?.isInteractive ?: true
                when {
                    level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                        Log.i(TAG, "System is reclaiming memory from the backgrounded app (level=$level) — releasing")
                        release()
                    }
                    level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && screenOn -> {
                        Log.i(TAG, "App backgrounded (all UI hidden) — releasing")
                        release()
                    }
                    level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ->
                        Log.i(TAG, "Screen turned off — keeping the model loaded")
                }
            }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            @Deprecated("Deprecated in Java") override fun onLowMemory() { _lowMemoryPressure.value = true }
        })
    }

    /** Runs [block] against [model]'s engine, loading it first only if it isn't already the one in
     *  memory, and keeps the engine from being closed until [block] returns. Every conversation
     *  must go through this — see [activeUsers]. */
    private suspend fun <T> withEngine(context: Context, model: OfflineModelInfo, block: suspend (Engine) -> T): T {
        val engine = acquireEngine(context, model)
        try {
            return block(engine)
        } finally {
            activeUsers.update { it - 1 }
        }
    }

    /** Returns [model]'s engine with [activeUsers] already incremented — the caller must decrement
     *  it when done ([withEngine] does). Never awaits anything while holding [mutex] (the load job
     *  needs it to install its result); every wait happens outside it, then the slot is re-checked
     *  from scratch, since anything may have changed in between. */
    private suspend fun acquireEngine(context: Context, model: OfflineModelInfo): Engine {
        while (true) {
            val wait: suspend () -> Unit = mutex.withLock {
                when (val s = slot) {
                    is Slot.Loaded -> when {
                        s.modelId == model.id -> {
                            activeUsers.update { it + 1 }
                            _loadState.value = LoadState.Ready(model.id)
                            return s.engine
                        }
                        // A different model, still answering something: wait for it to finish
                        // before closing it. Callers that switch models already cancel their own
                        // analysis first, so this is normally instant.
                        activeUsers.value > 0 -> suspend { activeUsers.first { it == 0 } }
                        else -> {
                            // Close first, then go round again: the Empty branch below waits for the
                            // freed memory to show before startLoadLocked measures it, so the check
                            // sees memory as it is once the old model is really gone.
                            closeLoadedLocked(s)
                            suspend { }
                        }
                    }
                    is Slot.Loading ->
                        if (s.modelId == model.id) {
                            // Same model already on its way in (e.g. its first caller was cancelled
                            // mid-load): join it rather than starting a second copy. Un-mark a
                            // release that came in meanwhile — someone wants it again — unless the
                            // load was already given up on.
                            if (!s.outcome.isCompleted) s.closeWhenDone = false
                            suspend { awaitOwnLoad(s) }
                        } else {
                            // A different model is mid-load and can't be interrupted: let it finish,
                            // close it, and only then load this one.
                            s.closeWhenDone = true
                            _loadState.value = LoadState.Loading(model.id)
                            suspend { awaitPreviousLoad(s, model) }
                        }
                    Slot.Empty -> {
                        val sinceClose = SystemClock.elapsedRealtime() - lastClosedAt
                        if (sinceClose < SETTLE_AFTER_CLOSE_MS) {
                            _loadState.value = LoadState.Loading(model.id)
                            suspend {
                                Runtime.getRuntime().gc()
                                delay(SETTLE_AFTER_CLOSE_MS - sinceClose)
                            }
                        } else {
                            val loading = startLoadLocked(context, model)
                            suspend { awaitOwnLoad(loading) }
                        }
                    }
                }
            }
            wait()
        }
    }

    private suspend fun awaitOwnLoad(loading: Slot.Loading) {
        loading.outcome.await()?.let { throw ModelNotReady(it) }
    }

    private suspend fun awaitPreviousLoad(previous: Slot.Loading, model: OfflineModelInfo) {
        val finished = try {
            withTimeoutOrNull(LOAD_TIMEOUT_MS) { previous.done.await() }
        } catch (e: CancellationException) {
            // acquireEngine showed Loading(model) for this wait; nobody's waiting any more, and
            // no load of this model has started, so don't leave that dialog up with nothing behind it.
            _loadState.compareAndSet(LoadState.Loading(model.id), LoadState.Idle)
            throw e
        }
        if (finished == null) {
            val message = "The previous model is still loading and can't be interrupted. Try ${model.displayName} again in a moment."
            _loadState.value = LoadState.Failed(model.id, message)
            throw ModelNotReady(message)
        }
    }

    /** Must hold [mutex], with [activeUsers] at 0. */
    private fun closeLoadedLocked(loaded: Slot.Loaded) {
        Log.i(TAG, "Closing ${loaded.modelId}")
        loaded.engine.close()
        lastClosedAt = SystemClock.elapsedRealtime()
        slot = Slot.Empty
        _loadState.value = LoadState.Idle
    }

    /** Reports [model]'s load as failed and, if it is the selected model, switches the app to
     *  Basic tasks — a model that couldn't load mustn't stay selected with nothing behind it. By
     *  the time this is called nothing of it is kept in memory, or (for a native load still
     *  running past its timeout) it is marked to be closed the moment it returns. */
    private fun failLoad(context: Context, model: OfflineModelInfo, message: String) {
        val switched = switchToBasicIfSelected(context, model.id)
        // A second caller failing the same model (selecting one preloads it from both the picker
        // and the screen) finds the selection already moved, and must not replace the failure that
        // says so — the screen would never learn of the switch.
        val current = _loadState.value
        if (!switched && current is LoadState.Failed && current.modelId == model.id && current.switchedToBasic) return
        _loadState.value = LoadState.Failed(model.id, message, switched)
    }

    private fun switchToBasicIfSelected(context: Context, modelId: String): Boolean {
        val prefs = PreferencesManager(context)
        if (prefs.selectedModelId != modelId) return false
        prefs.selectedModelId = "mlkit"
        Log.i(TAG, "Switched to Basic tasks after $modelId failed to load")
        return true
    }

    /** Lets the screen that showed a failure clear it, so the next screen doesn't show it again. */
    fun acknowledgeFailure(failure: LoadState.Failed) {
        _loadState.compareAndSet(failure, LoadState.Idle)
    }

    /**
     * Call once at app start. If the previous run was killed while a model was loading — what a
     * load that runs the phone out of memory usually looks like, since that doesn't throw — this
     * switches to Basic tasks and reports it through [loadState] like any other failed load, so
     * the app doesn't try the same model again on launch and get killed again.
     */
    fun recoverFromInterruptedLoad(context: Context) {
        val prefs = PreferencesManager(context)
        val modelId = prefs.modelLoadInProgress ?: return
        prefs.modelLoadInProgress = null
        if (!lastExitLooksLikeLoadFailure(context)) return
        val name = OfflineModelsRepository(context).loadModels().find { it.id == modelId }?.displayName ?: "The model"
        val switched = switchToBasicIfSelected(context, modelId)
        _loadState.value = LoadState.Failed(
            modelId,
            "$name could not be loaded: the phone closed the app while loading it, most likely because it ran out of memory.",
            switched
        )
    }

    /** Whether the last time the app's process ended was the system (or a crash) rather than the
     *  user closing it. Below Android 11 there's no way to tell, so assume the worse. */
    private fun lastExitLooksLikeLoadFailure(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        val exit = runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
        }.getOrNull() ?: return true
        return exit.reason in setOf(
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
        )
    }

    /** Must hold [mutex], with [slot] [Slot.Empty]. Starts [model]'s load on [engineScope] and
     *  returns its [Slot.Loading]; the load installs its own result into [slot] when it finishes,
     *  whether or not anyone is still waiting for it. */
    private fun startLoadLocked(context: Context, model: OfflineModelInfo): Slot.Loading {
        // The feasibility check, right before actually starting and with nothing else loaded (the
        // Empty branch of acquireEngine has already let a just-closed model's memory come back):
        // a model that can't fit fails here, at once and with a clear message, instead of after a
        // minute of thrashing — or by getting the app killed.
        if (!DeviceMemoryDetector.canLoadNow(context, model)) {
            val gb = { bytes: Long -> "%.1f".format(bytes / 1_000_000_000.0) }
            val message = "There isn't enough free memory on this phone to load ${model.displayName} right now: " +
                "it needs about ${gb(DeviceMemoryDetector.bytesNeededToLoad(model))} GB and " +
                "${gb(DeviceMemoryDetector.availableRamBytes(context))} GB is free. Closing other apps may help."
            failLoad(context, model, message)
            throw ModelNotReady(message)
        }

        // Cleared when the load ends either way; still set at the next launch only if the app
        // was killed mid-load — see recoverFromInterruptedLoad.
        PreferencesManager(context).modelLoadInProgress = model.id
        val loading = Slot.Loading(model.id)
        slot = loading
        _loadState.value = LoadState.Loading(model.id)
        _lowMemoryPressure.value = false
        Log.i(TAG, "Loading ${model.id}")
        engineScope.launch { runLoad(context.applicationContext, model, loading) }
        return loading
    }

    /** Wraps [loadAndInstall] so that, whatever goes wrong in it, [Slot.Loading.outcome] and
     *  [Slot.Loading.done] always complete and the slot always moves on — every waiter depends on
     *  that, and a slot stuck in Loading would block every later load and keep the undismissable
     *  "Loading…" dialog up for good. */
    private suspend fun runLoad(context: Context, model: OfflineModelInfo, loading: Slot.Loading) {
        try {
            loadAndInstall(context, model, loading)
        } finally {
            PreferencesManager(context).modelLoadInProgress = null
            if (!loading.done.isCompleted) {
                Log.e(TAG, "Loading ${model.id} ended unexpectedly — clearing its slot")
                val message = "Loading ${model.displayName} failed unexpectedly."
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (slot === loading) slot = Slot.Empty
                        if (_loadState.value == LoadState.Loading(model.id)) failLoad(context, model, message)
                    }
                }
                loading.outcome.complete(message)
                loading.done.complete(Unit)
            }
        }
    }

    private suspend fun loadAndInstall(context: Context, model: OfflineModelInfo, loading: Slot.Loading) {
        val modelFile = OfflineModelsRepository(context).localFileFor(model)

        // The native call can't be interrupted, so the timeout here only decides when to stop
        // *waiting* and tell the user. A load that's taking dramatically longer than normal is
        // treated as a probable low-memory thrash — but it still occupies the slot until the
        // native call really returns, so nothing else can start loading next to it meanwhile.
        suspend fun attemptLoad(useCpuVision: Boolean): Engine {
            val engineConfig = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                visionBackend = if (useCpuVision) Backend.CPU() else Backend.GPU(),
                cacheDir = context.cacheDir.path
            )
            val nativeLoad = engineScope.async { Engine(engineConfig).also { it.initialize() } }
            withTimeoutOrNull(LOAD_TIMEOUT_MS) { nativeLoad.await() }?.let { return it }

            Log.w(TAG, "Loading ${model.id} exceeded ${LOAD_TIMEOUT_MS}ms — likely a low-memory thrash, giving up")
            val message = "Loading ${model.displayName} is taking much longer than usual — this phone " +
                "most likely doesn't have enough free memory for it right now."
            loading.closeWhenDone = true
            failLoad(context, model, message)
            loading.outcome.complete(message)
            runCatching { nativeLoad.await() }.getOrNull()?.close()
            lastClosedAt = SystemClock.elapsedRealtime()
            throw LoadTimedOut(message)
        }

        // GPU is tried first for every model on every device — a per-(model, device) preference
        // learned from a real failure (see PreferencesManager.getVisionBackendOverride) is the only
        // thing that skips straight to CPU. If GPU fails right here at load time (rather than later,
        // at conversation time — see analyze()'s own fallback below for that case) and this is the
        // first time we're seeing it, retry once on CPU and remember the result. A timeout isn't a
        // GPU incompatibility, so it doesn't trigger that retry.
        val prefs = PreferencesManager(context)
        var failure: String? = null
        val engine: Engine? = try {
            if (prefs.getVisionBackendOverride(model.id) == "CPU") {
                attemptLoad(useCpuVision = true)
            } else {
                try {
                    attemptLoad(useCpuVision = false)
                } catch (e: LoadTimedOut) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "GPU vision init failed for ${model.id}, retrying once on CPU", e)
                    prefs.setVisionBackendOverride(model.id, "CPU")
                    attemptLoad(useCpuVision = true)
                }
            }
        } catch (e: Exception) {
            if (e !is LoadTimedOut) Log.e(TAG, "Loading ${model.id} failed", e)
            failure = e.localizedMessage ?: "Failed to load ${model.displayName}"
            null
        }

        mutex.withLock {
            // Nothing else replaces a Loading slot — only this job moves it on — so the slot is
            // still `loading` here, and loadState changes below can't stomp a newer request's
            // state except where checked.
            if (engine != null && !loading.closeWhenDone) {
                slot = Slot.Loaded(model.id, engine)
                _loadState.value = LoadState.Ready(model.id)
                Log.i(TAG, "Loaded ${model.id}")
            } else {
                engine?.let {
                    runCatching { it.close() }
                    lastClosedAt = SystemClock.elapsedRealtime()
                }
                slot = Slot.Empty
                val current = _loadState.value
                if (failure != null && !loading.outcome.isCompleted) {
                    failLoad(context, model, "Couldn't load ${model.displayName}: $failure")
                } else if (current is LoadState.Loading && current.modelId == model.id) {
                    _loadState.value = LoadState.Idle
                }
                Log.i(TAG, "Discarded ${model.id} (failure=$failure, closeWhenDone=${loading.closeWhenDone})")
            }
        }
        loading.outcome.complete(if (engine != null && !loading.closeWhenDone) null else failure ?: "Loading ${model.displayName} was cancelled.")
        loading.done.complete(Unit)
    }

    private class LoadTimedOut(message: String) : IllegalStateException(message)

    /** The model couldn't be made ready (not enough memory, load failed/timed out/was dropped, an
     *  earlier load still in the way) — as opposed to something going wrong *during* a
     *  conversation. [analyze]/[analyzeStreaming]'s GPU→CPU fallback must not treat this as a
     *  vision-backend failure: [runLoad] already does its own GPU→CPU retry at load time, and
     *  pinning the model to CPU for good because, say, the user pressed Home mid-load would be wrong. */
    private class ModelNotReady(message: String) : IllegalStateException(message)

    /** Inference ran past its timeout — a probable low-memory thrash, not a GPU incompatibility, so
     *  [analyze]/[analyzeStreaming]'s GPU→CPU fallback must rethrow it, not pin the model to CPU for
     *  good and then make the user wait out a second full timeout on the retry. Same reasoning as
     *  [LoadTimedOut] at load time. */
    private class InferenceTimedOut(message: String) : IllegalStateException(message)

    /** Loads [model] into memory right away (rather than waiting for the first [analyze] call) so
     *  selecting a model shows its loading cost immediately, via [loadState], instead of surfacing
     *  as unexplained lag the first time the user taps a button. A no-op if it's already loaded,
     *  and joins the existing load if it's already loading — so it's safe to call on every screen
     *  open. Cancelling the caller only stops it waiting; the load itself carries on and is kept. */
    suspend fun preload(context: Context, model: OfflineModelInfo) {
        try {
            acquireEngine(context, model)
            activeUsers.update { it - 1 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not touching loadState: every failure path that should show the user something has
            // already set LoadState.Failed itself. The one that hasn't — this model's load being
            // dropped because a different model was asked for meanwhile — must not overwrite that
            // newer model's state.
            Log.e(TAG, "Preload failed for model ${model.id}", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reasoning effort — Gemma 4 only.
    //
    // LiteRT-LM's Thinking Mode (ai.google.dev/gemma/docs/core/prompt-formatting-gemma4) is a
    // Gemma-4-family feature, not a generic LiteRT-LM one: enabled by putting the literal control
    // token "<|think|>" at the start of the system turn, after which the model emits its reasoning
    // wrapped as "<|channel>thought ... <channel|>" before its real answer, in the same turn.
    // Qwen2-VL-2B (this predates Qwen3's own hybrid thinking mode) and SmolVLM2 have no such
    // mode — nothing in their model cards, litert-community's docs, or LiteRT-LM's own
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
        // a formula: [INFERENCE_TIMEOUT_MS]'s own budget for MAX_OUTPUT_TOKENS is dominated by a
        // mostly-fixed image-prefill cost, not a flat per-token rate, so naively scaling it by
        // token count would wildly overestimate what a longer response actually needs — and
        // REASONING_HIGH's budget is deliberately a large finite number to begin with (2000, not
        // -1/unbounded), for the same "don't let an actually-unbounded pass trip the
        // low-memory-thrash timeout" reason the timeout itself has to stay bounded (see this
        // object's class doc for what that timeout is guarding against).
        //
        // Each tier's timeout carries the same +60s [INFERENCE_TIMEOUT_MS] itself just got bumped
        // by (90s -> 150s) when MAX_OUTPUT_TOKENS moved 200 -> 800 - a real 4x-larger token budget
        // genuinely does cost meaningfully more generation time even though the fixed prefill cost
        // doesn't scale, and every tier's maxOutputToken below is MAX_OUTPUT_TOKENS + its own
        // thinkingTokenBudget, so all three now need the same extra headroom the baseline does.
        // Deliberately conservative and unmeasured, same caveat as INFERENCE_TIMEOUT_MS's own doc -
        // revisit with real numbers if a reasoning-enabled call is timing out in practice.
        val (thinkingTokenBudget, timeoutMs) = when (effort) {
            REASONING_LOW -> 256 to 210_000L
            REASONING_MEDIUM -> 1024 to 300_000L
            else -> 2000 to 420_000L // REASONING_HIGH
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

        suspend fun runOnce(): String = withEngine(context, model) { engine ->
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
                // Text after image(s), matching the model's expected token ordering.
                contents.add(Content.Text(userPrompt))

                val inferenceJob = engineScope.async { conversation.sendMessage(Contents.of(contents)).toString() }
                // Leaving this block in any way but a normal finish (timeout, or the caller being
                // cancelled) closes the conversation — and, once withEngine returns, lets the engine
                // itself be closed — while sendMessage may still be generating on engineScope.
                // cancelProcess() first, for the same native-crash reason as analyzeStreaming's own
                // cancellation handler.
                val rawResult = try {
                    withTimeoutOrNull(plan.inferenceTimeoutMs) { inferenceJob.await() }
                } catch (e: CancellationException) {
                    conversation.cancelProcess()
                    throw e
                }
                    ?: run {
                        Log.w(TAG, "Inference on ${model.id} exceeded ${plan.inferenceTimeoutMs}ms — likely a low-memory thrash")
                        conversation.cancelProcess()
                        throw InferenceTimedOut(
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
        // image runs — not at engine-init time (see runLoad's own, earlier fallback, and
        // PreferencesManager.getVisionBackendOverride) — confirmed on Exynos 2100/Mali with
        // `lfm2_5_vl_450m` (2026-08-24): engine.initialize() succeeded, createConversation() didn't.
        // Retry once on CPU before giving up, and remember it so every later load on this device
        // skips straight to CPU instead of re-failing on GPU every time.
        suspend fun runWithCpuFallback(): String = try {
            runOnce()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelNotReady) {
            throw e
        } catch (e: InferenceTimedOut) {
            throw e
        } catch (e: Exception) {
            if (prefs.getVisionBackendOverride(model.id) == "CPU") throw e
            Log.w(TAG, "Vision inference failed for ${model.id}, retrying once on CPU", e)
            prefs.setVisionBackendOverride(model.id, "CPU")
            // Awaited, not fire-and-forget release(): otherwise runOnce() below can grab the
            // still-loaded GPU engine first and have the queued release close it mid-use.
            releaseSuspending()
            runOnce()
        }

        try {
            runWithCpuFallback()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not touching loadState here: if this came from the model failing to load,
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

        suspend fun collectOnce(emit: suspend (String) -> Unit) = withEngine(context, model) { engine ->
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
                    // Same as the cancellation case above: about to close the conversation, and
                    // possibly the engine after it, so make sure generation has really stopped.
                    conversation.cancelProcess()
                    throw InferenceTimedOut(
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

        // Once any text has gone out, a retry can't be made invisible: it would start the answer over
        // from scratch, and the caller would speak those sentences a second time. Only a failure
        // before the first delta (where a GPU incompatibility actually shows up) is retried.
        var emittedAny = false
        try {
            collectOnce { emittedAny = true; emit(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelNotReady) {
            throw e
        } catch (e: InferenceTimedOut) {
            throw e
        } catch (e: Exception) {
            if (emittedAny || prefs.getVisionBackendOverride(model.id) == "CPU") throw e
            Log.w(TAG, "Streaming vision inference failed for ${model.id}, retrying once on CPU", e)
            prefs.setVisionBackendOverride(model.id, "CPU")
            releaseSuspending() // awaited — see analyze()'s identical fallback
            collectOnce { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    /** Releases the loaded model's resources — e.g. when the app backgrounds
     *  ([registerMemoryPressureMonitor]) or the user switches to "mlkit"/Local Server, neither of
     *  which needs an on-device engine resident. Not itself suspending (so it can be called from
     *  the non-suspend [onTrimMemory] callback below); fire-and-forget onto [engineScope]/[mutex]
     *  instead — see [releaseSuspending] for exactly what releasing does in each state. */
    fun release() {
        engineScope.launch { releaseSuspending() }
    }

    /** Suspending counterpart to [release]. Prefer this over [release] whenever the caller is
     *  already in a coroutine that can await it — e.g. a Composable's `LaunchedEffect` switching
     *  away from an on-device model.
     *
     *  - Loaded and idle: closed right away.
     *  - Loaded and still answering something: waits for that to finish first (never closes an
     *    engine out from under a running conversation), then closes it.
     *  - Still loading: the native load can't be interrupted, so it's marked to be closed the moment
     *    it finishes instead of being kept — and until then it still occupies the slot, so no other
     *    model can start loading next to it. */
    suspend fun releaseSuspending() {
        while (true) {
            val wait: suspend () -> Unit = mutex.withLock {
                when (val s = slot) {
                    Slot.Empty -> return
                    is Slot.Loading -> {
                        s.closeWhenDone = true
                        return
                    }
                    is Slot.Loaded ->
                        if (activeUsers.value > 0) {
                            suspend { activeUsers.first { it == 0 } }
                        } else {
                            closeLoadedLocked(s)
                            _lowMemoryPressure.value = false
                            return
                        }
                }
            }
            wait()
        }
    }
}
