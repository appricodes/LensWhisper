package com.appricodes.lens_whisper.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.appricodes.lens_whisper.data.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Everything the app says goes through here: through TalkBack announcements (the default, and how
 * the app always worked) or, once the user picks one in Settings, straight to a text-to-speech
 * engine ([PreferencesManager.speechEngine]). The engine's own voice and language are whatever the
 * user set in that engine's settings — this never calls `setLanguage`.
 *
 * - [speak] queues: a model's answer arrives sentence by sentence and every sentence must be heard.
 * - [stop] silences whatever is playing or queued, in both modes. Every button, a new prompt, the
 *   microphone, Stop and a model switch call it first, so their own announcement follows the stop
 *   instead of being cut off by it or waiting behind the old text.
 * - [awaitSilence] lets a repeating task (continuous Read Text) wait until the last result has
 *   been heard out before it looks again. TalkBack doesn't say when it's done, so in that mode it
 *   returns at once.
 */
object SpeechOutput {

    private const val TAG = "SpeechOutput"
    const val TALKBACK = "talkback"

    /** An engine that never reports finishing mustn't freeze a repeating task forever. */
    private const val SILENCE_TIMEOUT_MS = 5 * 60_000L

    private val SPEECH_AUDIO_ATTRIBUTES = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val lock = Any()
    private var tts: TextToSpeech? = null
    private var ttsEngine: String? = null
    private var ready = false
    /** Texts asked for before the engine finished starting; spoken (or handed to TalkBack, if it
     *  fails to start) once it has. */
    private val waiting = ArrayList<String>()

    /** Bumped by every [stop]: callbacks from utterances queued before it are ignored. */
    private var generation = 0
    private var nextUtterance = 0
    private val playing = HashSet<String>()
    private val speaking = MutableStateFlow(false)

    /** Says [text] after whatever is already being said. */
    fun speak(context: Context, text: String) {
        if (text.isBlank()) return
        val engine = PreferencesManager(context).speechEngine
        if (engine == TALKBACK) {
            announceThroughTalkBack(context, text)
            return
        }
        synchronized(lock) {
            val instance = ttsFor(context.applicationContext, engine)
            if (!ready) {
                waiting += text
                speaking.value = true
                return
            }
            enqueueLocked(instance, text)
        }
    }

    /** Silences everything being said or waiting to be said, through either path. */
    fun stop(context: Context) {
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (accessibilityManager.isEnabled) accessibilityManager.interrupt()
        synchronized(lock) {
            generation++
            playing.clear()
            waiting.clear()
            speaking.value = false
            tts?.stop()
        }
    }

    /** Suspends until nothing is being said (at most [SILENCE_TIMEOUT_MS]). Immediate in TalkBack
     *  mode, which gives no such signal. */
    suspend fun awaitSilence(context: Context) {
        if (PreferencesManager(context).speechEngine == TALKBACK) return
        withTimeoutOrNull(SILENCE_TIMEOUT_MS) { speaking.first { !it } }
    }

    /** The installed text-to-speech engines, as (package name, label). */
    fun installedEngines(context: Context): List<Pair<String, String>> {
        val probe = TextToSpeech(context.applicationContext, null)
        return try {
            probe.engines.map { it.name to it.label }.sortedBy { it.second.lowercase() }
        } finally {
            probe.shutdown()
        }
    }

    /** Android's own text-to-speech page: speech rate, pitch and, for many engines, language. */
    fun openSystemSettings(context: Context) {
        try {
            context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            announceThroughTalkBack(context, "This phone has no text-to-speech settings screen to open.")
        } catch (e: SecurityException) {
            announceThroughTalkBack(context, "This phone has no text-to-speech settings screen to open.")
        }
    }

    /** Opens [enginePackage]'s own settings (voices, voice data), or the system's text-to-speech
     *  settings if the engine doesn't declare a settings screen. */
    fun openEngineSettings(context: Context, enginePackage: String) {
        val own = engineSettingsActivity(context, enginePackage)?.let { Intent().setClassName(enginePackage, it) }
        for (intent in listOfNotNull(own, Intent("com.android.settings.TTS_SETTINGS"))) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No activity for $intent")
            } catch (e: SecurityException) {
                Log.w(TAG, "Not allowed to open $intent")
            }
        }
        announceThroughTalkBack(context, "This phone has no text-to-speech settings screen to open.")
    }

    /** Must hold [lock]. */
    private fun ttsFor(context: Context, engine: String): TextToSpeech {
        tts?.let { if (ttsEngine == engine) return it }
        tts?.shutdown()
        ready = false
        ttsEngine = engine
        // onInit can come synchronously from inside the constructor (no engine could be bound at
        // all), before `created` exists; such a status is kept and handled once it does.
        var created: TextToSpeech? = null
        var earlyStatus: Int? = null
        created = TextToSpeech(context, { status ->
            val instance = created
            if (instance == null) earlyStatus = status else onInit(context, instance, status)
        }, engine)
        created.setAudioAttributes(SPEECH_AUDIO_ATTRIBUTES)
        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finished(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = finished(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = finished(utteranceId)
        })
        tts = created
        earlyStatus?.let { status -> onInit(context, created, status) }
        return created
    }

    /** Lets go of the engine while TalkBack is chosen, so it isn't kept bound for nothing. */
    fun release() {
        synchronized(lock) {
            tts?.shutdown()
            tts = null
            ttsEngine = null
            ready = false
            waiting.clear()
            playing.clear()
            speaking.value = false
        }
    }

    private fun onInit(context: Context, instance: TextToSpeech, status: Int) {
        val held: List<String>
        synchronized(lock) {
            // An engine replaced (by a change of setting) before it finished starting.
            if (instance !== tts) {
                instance.shutdown()
                return
            }
            held = waiting.toList()
            waiting.clear()
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                held.forEach { enqueueLocked(instance, it) }
                if (playing.isEmpty()) speaking.value = false
                return
            }
            Log.w(TAG, "Text-to-speech engine $ttsEngine failed to start ($status)")
            instance.shutdown()
            tts = null
            ttsEngine = null
            speaking.value = false
        }
        // The chosen engine won't start (uninstalled, broken): don't lose what was to be said.
        held.forEach { announceThroughTalkBack(context, it) }
    }

    /** Must hold [lock]. */
    private fun enqueueLocked(instance: TextToSpeech, text: String) {
        for (chunk in chunks(text, TextToSpeech.getMaxSpeechInputLength() - 1)) {
            val id = "$generation:${nextUtterance++}"
            playing += id
            speaking.value = true
            if (instance.speak(chunk, TextToSpeech.QUEUE_ADD, Bundle(), id) != TextToSpeech.SUCCESS) {
                playing -= id
            }
        }
        if (playing.isEmpty()) speaking.value = false
    }

    private fun finished(utteranceId: String) {
        synchronized(lock) {
            if (utteranceId.substringBefore(':') != generation.toString()) return
            playing -= utteranceId
            if (playing.isEmpty() && waiting.isEmpty()) speaking.value = false
        }
    }

    /** Splits text longer than the engine accepts in one go at a space, so nothing is cut off. */
    private fun chunks(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val parts = ArrayList<String>()
        var rest = text
        while (rest.length > max) {
            val cut = rest.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
            parts += rest.substring(0, cut)
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }

    private fun announceThroughTalkBack(context: Context, text: String) {
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (!accessibilityManager.isEnabled) return
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT).apply {
            className = javaClass.name
            packageName = context.packageName
            this.text.add(text)
        }
        accessibilityManager.sendAccessibilityEvent(event)
    }

    /** The `settingsActivity` an engine declares in its `android.speech.tts` metadata, if any —
     *  the same place the system's own text-to-speech settings look. */
    private fun engineSettingsActivity(context: Context, enginePackage: String): String? = runCatching {
        val pm = context.packageManager
        val service = pm.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).setPackage(enginePackage),
            PackageManager.GET_META_DATA
        ).firstOrNull()?.serviceInfo ?: return null
        val parser: XmlResourceParser = service.loadXmlMetaData(pm, TextToSpeech.Engine.SERVICE_META_DATA) ?: return null
        parser.use {
            while (it.next() != XmlResourceParser.END_DOCUMENT) {
                if (it.eventType == XmlResourceParser.START_TAG && it.name == "tts-engine") {
                    val name = it.getAttributeValue("http://schemas.android.com/apk/res/android", "settingsActivity") ?: return null
                    return if (name.startsWith(".")) enginePackage + name else name
                }
            }
        }
        null
    }.getOrNull()
}
