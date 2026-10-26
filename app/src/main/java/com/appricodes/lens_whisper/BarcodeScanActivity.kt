package com.appricodes.lens_whisper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.appricodes.lens_whisper.data.BarcodeNoteStore
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.data.ProductDatabase
import com.appricodes.lens_whisper.media.VoiceNoteRecorder
import com.appricodes.lens_whisper.network.UpcLookupClient
import com.appricodes.lens_whisper.network.UpcLookupResult
import com.appricodes.lens_whisper.offline.BarcodeKey
import com.appricodes.lens_whisper.offline.OfflineBarcodeScanner
import com.appricodes.lens_whisper.offline.ScannedBarcode
import com.appricodes.lens_whisper.ui.components.BarcodeScanCamera
import com.appricodes.lens_whisper.ui.theme.LensWhisperTheme
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.appricodes.lens_whisper.utils.ProcessingSoundPlayer
import kotlinx.coroutines.launch

/**
 * "Scan Barcode": points the camera at whatever the user is holding, keeps looking until a barcode
 * turns up, then tells them what it is and lets them leave themselves a note about it.
 *
 * **Why a live stream rather than a photo every second.** The feature was sketched as "take a 720p
 * photo, check it, wait a second, repeat", with a note to consider video instead. Video (really: a
 * CameraX `ImageAnalysis` stream — see [BarcodeScanCamera]) wins clearly and is what this
 * implements: no shutter click and no flash burst once per second for as long as the user is
 * hunting for the label, no camera open/close cycle per attempt, a hit the instant the barcode
 * crosses the frame instead of up to a second later, and it is the access pattern ML Kit's barcode
 * detector is designed around. The 720p resolution from the original sketch is kept.
 *
 * **What leaves the device.** A 2D code (QR, Data Matrix, Aztec, PDF417) carries its own payload,
 * so its content is simply shown — nothing is transmitted, ever. Only a 1D *product* barcode is an
 * identifier that means nothing on its own, and only that one can be looked up against UPCitemdb
 * (see [UpcLookupClient]) — and only after the user has accepted the one-time notice in
 * [BarcodeConsentDialog], which also warns that the returned information is not guaranteed to be
 * correct. Declining is not a dead end: the scan still works, the description just reads
 * "Unknown, or no information available", exactly as it does for a barcode the database has never
 * heard of or when the lookup fails.
 *
 * **Notes.** Anything the user records or dictates is filed under [BarcodeKey.of] — one string
 * used both as the notes database's primary key and as the voice recording's filename, so scanning
 * the same package again next month brings back what they left on it: the recording plays, or the
 * typed note is read out and shown. See [BarcodeNoteStore].
 *
 * Can also be opened with a barcode already in hand (the `BARCODE_VALUE`/`BARCODE_FORMAT`/
 * `BARCODE_IS_2D` extras), which is how Media Describer's file mode reaches this screen: that host
 * has a still image and no live camera, so it runs a one-shot detect of its own and jumps straight
 * to the result — see [com.appricodes.lens_whisper.ui.models.MlkitButtonProvider].
 */
class BarcodeScanActivity : ComponentActivity() {

    companion object {
        const val EXTRA_BARCODE_VALUE = "BARCODE_VALUE"
        const val EXTRA_BARCODE_FORMAT = "BARCODE_FORMAT"
        const val EXTRA_BARCODE_IS_2D = "BARCODE_IS_2D"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PreferencesManager(this)
        val preScanned = intent?.getStringExtra(EXTRA_BARCODE_VALUE)?.takeIf { it.isNotBlank() }?.let { value ->
            ScannedBarcode(
                value = value,
                format = intent.getIntExtra(EXTRA_BARCODE_FORMAT, 0),
                isTwoDimensional = intent.getBooleanExtra(EXTRA_BARCODE_IS_2D, false),
                areaPx = 0
            )
        }

        setContent {
            LensWhisperTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BarcodeScanScreen(prefs = prefs, preScanned = preScanned, onClose = { finish() })
                }
            }
        }
    }
}

/** Which of the screen's three states is showing. Kept as a plain enum plus a handful of separate
 *  state values rather than a sealed hierarchy — the transitions here are linear (scan → look up →
 *  result, or straight to result) and every field is genuinely shared across them. */
private enum class ScanPhase { SCANNING, LOOKING_UP, RESULT }

@Composable
private fun BarcodeScanScreen(
    prefs: PreferencesManager,
    preScanned: ScannedBarcode?,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val camera = remember { BarcodeScanCamera(context) }
    val recorder = remember { VoiceNoteRecorder(context) }

    var phase by remember { mutableStateOf(if (preScanned != null) ScanPhase.LOOKING_UP else ScanPhase.SCANNING) }
    var barcode by remember { mutableStateOf(preScanned) }
    var infoFields by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    // Where the description came from — worth saying out loud, because the two sources have very
    // different privacy properties (one never left the phone) and the user has no other way to tell
    // them apart.
    var infoSource by remember { mutableStateOf<String?>(null) }
    var noteText by remember { mutableStateOf<String?>(null) }
    var hasAudioNote by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var statusLine by remember { mutableStateOf("Scanning. Point the camera at the barcode.") }

    // True only between ON_RESUME and ON_PAUSE. Two things on this screen would otherwise keep
    // running after the user navigates away: the periodic "still scanning" tone (a LaunchedEffect
    // is not lifecycle-aware, so its loop would go on beeping in the background) and any recording
    // or playback in flight. CameraX unbinds the analysis stream by itself when the lifecycle drops
    // below STARTED, so the camera needs nothing here.
    var isForeground by remember { mutableStateOf(true) }

    // Non-null while the one-time "may we send this barcode to UPCitemdb?" notice is open; holds
    // the barcode the answer applies to, so accepting can carry straight on with its lookup.
    var consentFor by remember { mutableStateOf<ScannedBarcode?>(null) }
    // Non-null while a dictated note is waiting for the user to confirm it before it is stored.
    var pendingDictation by remember { mutableStateOf<String?>(null) }
    var confirmDeleteAudio by remember { mutableStateOf(false) }
    var confirmDeleteText by remember { mutableStateOf(false) }

    var cameraPermissionGranted by remember {
        mutableStateOf(hasCameraPermission(context))
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraPermissionGranted = granted
        if (!granted) {
            statusLine = "Camera permission is required to scan a barcode."
            AccessibilityUtils.announceToTalkBack(context, statusLine)
        }
    }
    // Requested lazily, only when the user actually reaches for the microphone: the app already
    // asks for RECORD_AUDIO once on first launch (see CameraActivity), but that request can have
    // been denied, and a silently failing recorder is the worst possible outcome on a screen whose
    // user cannot see that nothing happened.
    var pendingRecordAfterPermission by remember { mutableStateOf(false) }
    var pendingDictateAfterPermission by remember { mutableStateOf(false) }

    val currentKey = barcode?.let { BarcodeKey.of(it) }

    fun announce(text: String) = AccessibilityUtils.announceToTalkBack(context, text)

    // --- note loading / playback ----------------------------------------------------------

    suspend fun refreshNotes(key: String) {
        noteText = BarcodeNoteStore.getNote(context, key)?.text
        hasAudioNote = BarcodeNoteStore.hasAudioNote(context, key)
    }

    fun playAudioNote(key: String) {
        val file = BarcodeNoteStore.audioFileFor(context, key)
        isPlaying = recorder.play(file) { isPlaying = false }
        if (!isPlaying) announce("Could not play the voice note.")
    }

    // --- the scan itself ------------------------------------------------------------------

    /** Everything that happens once a barcode is in hand, whether it came from the live scan or
     *  from the pre-scanned extra: load any note the user left on it last time, then work out what
     *  the code actually says (payload for a 2D code, a lookup for a product barcode). */
    suspend fun resolveBarcode(found: ScannedBarcode) {
        val key = BarcodeKey.of(found)
        refreshNotes(key)

        if (found.isTwoDimensional || !UpcLookupClient.isLookupCandidate(found.value)) {
            // A 2D code's payload is the information itself; a 1D label that is not a run of digits
            // (an inventory tag in Code 39, say) is not a product code any database could answer
            // for. Neither is ever sent anywhere, so neither is worth asking consent about.
            infoFields = if (found.isTwoDimensional) listOf("Content" to found.value) else emptyList()
            infoSource = null
            phase = ScanPhase.RESULT
            return
        }

        // The offline database first, always. When it has the product, the answer is better on
        // every axis that matters here: instant, works with no signal, and the barcode never leaves
        // the phone — so there is nothing to ask consent for and no reason to go online at all.
        // See ProductDatabase; it is only present if the user chose to download it in Settings.
        val offline = ProductDatabase.lookup(context, found.value)
        if (offline != null) {
            infoFields = offline
            infoSource = "From the product database on this phone."
            phase = ScanPhase.RESULT
            return
        }

        if (!prefs.hasAcceptedBarcodeLookup) {
            // Stop here and ask. The result screen is shown either way once the dialog is answered.
            consentFor = found
        } else {
            infoFields = when (val result = UpcLookupClient.lookup(found.value)) {
                is UpcLookupResult.Found -> result.fields
                UpcLookupResult.NotFound -> emptyList()
            }
            infoSource = if (infoFields.isEmpty()) null else "From an online product database."
            phase = ScanPhase.RESULT
        }
    }

    /** Announces the result and plays back whatever the user left on this barcode before. Split
     *  from [resolveBarcode] so it runs exactly once per arrival at the result screen, driven by a
     *  keyed effect below rather than by whichever code path got there. */
    fun announceResult(found: ScannedBarcode, key: String) {
        ProcessingSoundPlayer.playScreenOpened(context)
        val description = when {
            found.isTwoDimensional -> "${found.formatName}. Content: ${found.value}"
            infoFields.isNotEmpty() -> "${found.formatName} ${found.value}. " +
                infoFields.joinToString(". ") { (label, value) -> "$label: $value" } +
                (infoSource?.let { ". $it" } ?: "")
            else -> "${found.formatName} ${found.value}. Unknown, or no information available."
        }
        announce(description)
        val savedNote = noteText
        when {
            // A recording is the more personal of the two and takes precedence; the typed note is
            // still shown on screen and readable by touch exploration either way.
            hasAudioNote -> {
                announce("Playing your saved voice note.")
                playAudioNote(key)
            }
            !savedNote.isNullOrBlank() -> announce("Your saved note: $savedNote")
        }
    }

    fun onBarcodesFound(found: List<ScannedBarcode>) {
        if (phase != ScanPhase.SCANNING) return
        val best = OfflineBarcodeScanner.pickBest(found) ?: return
        phase = ScanPhase.LOOKING_UP
        camera.stop()
        statusLine = "Barcode found. Reading it."
        announce(
            if (found.size > 1) "Several barcodes found. One was selected."
            else "Barcode found."
        )
        barcode = best
        coroutineScope.launch { resolveBarcode(best) }
    }

    fun restartScan() {
        if (isRecording) {
            recorder.cancelRecording()
            isRecording = false
        }
        recorder.stopPlayback()
        isPlaying = false
        barcode = null
        infoFields = emptyList()
        infoSource = null
        noteText = null
        hasAudioNote = false
        statusLine = "Scanning. Point the camera at the barcode."
        phase = ScanPhase.SCANNING
    }

    // Binds the camera exactly while the screen is in its scanning phase, and unbinds on every
    // other transition (result, lookup, leaving the screen) — the camera is never held open a
    // moment longer than the scan itself, matching CameraController's own contract elsewhere.
    LaunchedEffect(phase, cameraPermissionGranted) {
        if (phase != ScanPhase.SCANNING) {
            camera.stop()
            return@LaunchedEffect
        }
        if (!cameraPermissionGranted) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return@LaunchedEffect
        }
        announce("Scanning for a barcode. Point the camera at it. Press back to stop.")
        try {
            camera.start(lifecycleOwner, torch = prefs.flashEnabled) { found -> onBarcodesFound(found) }
        } catch (e: Exception) {
            statusLine = "Could not start the camera."
            announce(statusLine)
        }
    }

    // A periodic tone while scanning, so a user who cannot see the screen knows the scan is still
    // running and has not silently given up. Cancelled with the phase it belongs to.
    LaunchedEffect(phase, isForeground) {
        if (phase != ScanPhase.SCANNING || !isForeground) return@LaunchedEffect
        // Stops the moment the phase changes — i.e. the instant a barcode is found — instead of
        // ticking on over the announcement.
        ProcessingSoundPlayer.loopClockWaitingTone(context, startImmediately = false)
    }

    LaunchedEffect(phase, barcode) {
        val found = barcode
        if (phase == ScanPhase.RESULT && found != null) announceResult(found, BarcodeKey.of(found))
    }

    // The pre-scanned entry point (Media Describer's file mode) has no scanning phase to run.
    LaunchedEffect(Unit) {
        if (preScanned != null) resolveBarcode(preScanned)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isForeground = true
                }
                Lifecycle.Event.ON_PAUSE -> {
                    isForeground = false
                    if (isRecording) {
                        recorder.cancelRecording()
                        isRecording = false
                    }
                    recorder.stopPlayback()
                    isPlaying = false
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        onDispose {
            camera.shutdown()
            recorder.release()
        }
    }

    BackHandler {
        // Stopping an in-progress recording is a different intent from leaving the screen — a
        // careless Back should not silently discard what the user just said.
        if (isRecording) {
            recorder.cancelRecording()
            isRecording = false
            announce("Recording cancelled.")
        } else {
            onClose()
        }
    }

    // --- note actions ---------------------------------------------------------------------

    fun startRecording() {
        val key = currentKey ?: return
        if (!hasAudioPermission(context)) {
            pendingRecordAfterPermission = true
            return
        }
        recorder.stopPlayback()
        isPlaying = false
        val target = BarcodeNoteStore.audioFileFor(context, key)
        if (recorder.startRecording(target)) {
            isRecording = true
            announce("Recording. Double tap Stop Recording when you are done.")
        } else {
            announce("Could not start recording.")
        }
    }

    fun stopRecording() {
        val saved = recorder.stopRecording()
        isRecording = false
        coroutineScope.launch {
            val key = currentKey
            hasAudioNote = key != null && BarcodeNoteStore.hasAudioNote(context, key)
            announce(if (saved != null) "Voice note saved." else "Nothing was recorded.")
        }
    }

    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    DisposableEffect(speechRecognizer) { onDispose { speechRecognizer.destroy() } }

    fun startDictation() {
        if (!hasAudioPermission(context)) {
            pendingDictateAfterPermission = true
            return
        }
        recorder.stopPlayback()
        isPlaying = false
        announce("Listening. Say your note now.")
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                announce("Nothing was heard. Please try again.")
            }
            override fun onResults(results: Bundle?) {
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (heard.isNullOrBlank()) {
                    announce("Nothing was heard. Please try again.")
                } else {
                    pendingDictation = heard
                    announce("You said: $heard. Confirm to save it, or try again.")
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer.startListening(
            android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.speechLocaleCode)
            }
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val wantedRecord = pendingRecordAfterPermission
        val wantedDictate = pendingDictateAfterPermission
        pendingRecordAfterPermission = false
        pendingDictateAfterPermission = false
        if (!granted) {
            announce("Microphone permission is required to add a note by voice.")
        } else if (wantedRecord) {
            startRecording()
        } else if (wantedDictate) {
            startDictation()
        }
    }
    LaunchedEffect(pendingRecordAfterPermission, pendingDictateAfterPermission) {
        if (pendingRecordAfterPermission || pendingDictateAfterPermission) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // --- UI -------------------------------------------------------------------------------

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Scan Barcode",
                fontSize = 26.sp,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }
            )
            TextButton(
                onClick = onClose,
                modifier = Modifier.clearAndSetSemantics { contentDescription = "Close barcode scanning and go back." }
            ) { Text("Close", fontSize = 18.sp) }
        }

        when (phase) {
            ScanPhase.SCANNING -> {
                InfoCard(title = "Status", body = statusLine)
                Button(
                    onClick = onClose,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics { contentDescription = "Stop scanning and go back." }
                ) { Text("Stop Scanning", fontSize = 18.sp) }
            }

            ScanPhase.LOOKING_UP -> {
                InfoCard(title = "Status", body = "Reading the barcode. Please wait.")
            }

            ScanPhase.RESULT -> {
                val found = barcode
                val key = currentKey
                if (found != null && key != null) {
                    InfoCard(
                        title = "Barcode",
                        body = "${found.formatName}\n${found.value}"
                    )

                    InfoCard(
                        title = "Information",
                        body = when {
                            found.isTwoDimensional ->
                                "Read directly from the code. Nothing was sent anywhere.\n\n${found.value}"
                            infoFields.isNotEmpty() -> {
                                val body = infoFields.joinToString("\n") { (label, value) -> "$label: $value" }
                                if (infoSource != null) "$body\n\n$infoSource" else body
                            }
                            else -> "Unknown, or no information available."
                        }
                    )

                    InfoCard(
                        title = "Your Note",
                        body = when {
                            !noteText.isNullOrBlank() && hasAudioNote -> "Voice note saved.\n\n$noteText"
                            hasAudioNote -> "Voice note saved."
                            !noteText.isNullOrBlank() -> noteText!!
                            else -> "No note saved for this barcode yet."
                        }
                    )

                    ActionButton(
                        label = if (isRecording) "Stop Recording" else "Record a Voice Note",
                        description = if (isRecording) {
                            "Stop recording and save this voice note for this barcode."
                        } else if (hasAudioNote) {
                            "Record a voice note for this barcode. This replaces the voice note already saved."
                        } else {
                            "Record a voice note for this barcode, in your own voice."
                        },
                        onClick = { if (isRecording) stopRecording() else startRecording() }
                    )

                    if (hasAudioNote && !isRecording) {
                        ActionButton(
                            label = if (isPlaying) "Stop Playing" else "Play Voice Note",
                            description = if (isPlaying) "Stop playing the saved voice note." else "Play the voice note saved for this barcode.",
                            onClick = {
                                if (isPlaying) {
                                    recorder.stopPlayback()
                                    isPlaying = false
                                } else {
                                    playAudioNote(key)
                                }
                            }
                        )
                        ActionButton(
                            label = "Delete Voice Note",
                            description = "Delete the voice note saved for this barcode. You will be asked to confirm.",
                            outlined = true,
                            onClick = { confirmDeleteAudio = true }
                        )
                    }

                    ActionButton(
                        label = "Add a Note by Voice",
                        description = if (noteText.isNullOrBlank()) {
                            "Speak a note. It is turned into text, shown to you to confirm, then saved for this barcode."
                        } else {
                            "Speak a new note. It is turned into text, shown to you to confirm, then replaces the note saved for this barcode."
                        },
                        onClick = { startDictation() }
                    )

                    if (!noteText.isNullOrBlank()) {
                        ActionButton(
                            label = "Delete Text Note",
                            description = "Delete the written note saved for this barcode. You will be asked to confirm.",
                            outlined = true,
                            onClick = { confirmDeleteText = true }
                        )
                    }

                    ActionButton(
                        label = "Scan Another Barcode",
                        description = "Start scanning again for a different barcode.",
                        outlined = true,
                        onClick = { restartScan() }
                    )
                }
            }
        }
    }

    // --- dialogs --------------------------------------------------------------------------

    consentFor?.let { pending ->
        BarcodeConsentDialog(
            barcodeValue = pending.value,
            onAccept = {
                consentFor = null
                prefs.hasAcceptedBarcodeLookup = true
                coroutineScope.launch {
                    infoFields = when (val result = UpcLookupClient.lookup(pending.value)) {
                        is UpcLookupResult.Found -> result.fields
                        UpcLookupResult.NotFound -> emptyList()
                    }
                    infoSource = if (infoFields.isEmpty()) null else "From an online product database."
                    phase = ScanPhase.RESULT
                }
            },
            onDecline = {
                consentFor = null
                // Declining is not a failure — the barcode itself is still perfectly useful, and
                // the user can still attach their own note to it.
                infoFields = emptyList()
                infoSource = null
                phase = ScanPhase.RESULT
            }
        )
    }

    pendingDictation?.let { heard ->
        AlertDialog(
            onDismissRequest = { pendingDictation = null },
            title = { Text("Save This Note?") },
            text = { Text(heard, fontSize = 18.sp) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDictation = null
                    val found = barcode
                    val key = currentKey
                    if (found != null && key != null) {
                        coroutineScope.launch {
                            BarcodeNoteStore.saveNote(context, key, found.value, found.formatName, heard)
                            noteText = heard
                            announce("Note saved.")
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingDictation = null
                        startDictation()
                    }) { Text("Try Again") }
                    TextButton(onClick = { pendingDictation = null }) { Text("Cancel") }
                }
            }
        )
    }

    if (confirmDeleteAudio) {
        ConfirmDialog(
            title = "Delete Voice Note?",
            message = "The voice note saved for this barcode will be deleted. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDeleteAudio = false
                currentKey?.let { key ->
                    recorder.stopPlayback()
                    isPlaying = false
                    coroutineScope.launch {
                        BarcodeNoteStore.deleteAudioNote(context, key)
                        hasAudioNote = false
                        announce("Voice note deleted.")
                    }
                }
            },
            onDismiss = { confirmDeleteAudio = false }
        )
    }

    if (confirmDeleteText) {
        ConfirmDialog(
            title = "Delete Text Note?",
            message = "The written note saved for this barcode will be deleted. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDeleteText = false
                currentKey?.let { key ->
                    coroutineScope.launch {
                        BarcodeNoteStore.deleteNote(context, key)
                        noteText = null
                        announce("Note deleted.")
                    }
                }
            },
            onDismiss = { confirmDeleteText = false }
        )
    }
}

/**
 * The one-time notice shown before the very first product-barcode lookup — the app's only outbound
 * transmission of something the camera saw. Says plainly what is sent and to whom, and that the
 * information that comes back carries no guarantee of being correct, per the feature's own
 * requirement (and consistent with how [com.appricodes.lens_whisper.ui.components.DisclaimerDialog]
 * frames AI output). Answering it is remembered in
 * [PreferencesManager.hasAcceptedBarcodeLookup]; declining leaves that false, so the question is
 * asked again next time rather than silently locking the feature off forever.
 */
@Composable
private fun BarcodeConsentDialog(
    barcodeValue: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("Look This Barcode Up Online?") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "To find out what this product is, the barcode number $barcodeValue would be sent " +
                        "over the internet to UPCitemdb, a product database run by another company.",
                    fontSize = 16.sp
                )
                Text(
                    "Only the barcode number is sent. No photo, no location, and nothing that identifies " +
                        "you or your device.",
                    fontSize = 16.sp
                )
                Text(
                    "The information that comes back is supplied by that database and is not guaranteed " +
                        "to be correct or up to date. Do not rely on it for anything important — " +
                        "particularly not for allergies, medicines, or safety.",
                    fontSize = 16.sp
                )
                Text(
                    "If you decline, scanning still works. The barcode number is still read out and you " +
                        "can still save your own note about it — there just won't be a product description.",
                    fontSize = 16.sp
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onAccept,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "I accept. Send the barcode number and look it up."
                }
            ) { Text("I Accept") }
        },
        dismissButton = {
            TextButton(
                onClick = onDecline,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "Do not send anything. Continue without a product description."
                }
            ) { Text("Don't Send") }
        }
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, fontSize = 16.sp) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** A titled block of read-only text. One accessibility node for the pair (via
 *  `clearAndSetSemantics` on the outer Column), so TalkBack reads "Barcode. EAN-13. 5901234123457"
 *  as one stop instead of landing on a bare value with no idea what it belongs to — the same
 *  convention [com.appricodes.lens_whisper.SettingsActivity]'s rows use. */
@Composable
private fun InfoCard(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "$title. $body" },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Text(text = body, fontSize = 18.sp)
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    description: String,
    outlined: Boolean = false,
    onClick: () -> Unit
) {
    val modifier = Modifier
        .fillMaxWidth()
        .clearAndSetSemantics { contentDescription = description }
    if (outlined) {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label, fontSize = 18.sp) }
    } else {
        Button(onClick = onClick, modifier = modifier) { Text(label, fontSize = 18.sp) }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
