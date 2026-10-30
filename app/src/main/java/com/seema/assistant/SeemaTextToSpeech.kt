package com.seema.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Suspending wrapper around Android's TextToSpeech. Create on the main thread. */
class SeemaTextToSpeech(context: Context) {

    private val appContext = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(audioAttributes)
        .build()

    private val tts = TextToSpeech(appContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setAudioAttributes(audioAttributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) = complete(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = complete(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "TTS error $errorCode")
                complete(utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) = complete(utteranceId)
        })
    }

    /** Speaks [text] and suspends until playback finishes (or fails). */
    suspend fun speak(text: String) {
        val initialized = withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } ?: false
        if (!initialized) {
            Log.e(TAG, "TextToSpeech engine not available")
            return
        }

        applyVoiceSettings(text)
        val utteranceId = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[utteranceId] = done

        audioManager.requestAudioFocus(focusRequest)
        try {
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TextToSpeech.speak() rejected the utterance")
                return
            }
            val maxDuration = maxOf(MIN_SPEAK_TIMEOUT_MS, text.length * MS_PER_CHAR)
            withTimeoutOrNull(maxDuration) { done.await() }
        } finally {
            // Also runs on cancellation, so speech never outlives the request.
            if (!done.isCompleted) tts.stop()
            pending.remove(utteranceId)
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    /**
     * The chosen voice for English replies (Hindi replies keep a Hindi voice), plus pitch and
     * speed. Read on every reply, so changes in Settings apply immediately.
     */
    private fun applyVoiceSettings(text: String) {
        val settings = VoiceSettings(appContext)
        val hindi = text.any { it in '\u0900'..'\u097F' }
        val chosen = settings.voiceName?.let { name -> tts.voices?.firstOrNull { it.name == name } }
        // A network voice cannot speak offline; fall back to the built-in voice for the language.
        val usable = chosen?.takeUnless { it.isNetworkConnectionRequired && !NetworkStatus.isOnline(appContext) }
        if (usable != null && !hindi) tts.setVoice(usable) else tts.setLanguage(localeFor(text))
        tts.setPitch(settings.pitch)
        tts.setSpeechRate(settings.speechRate)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private fun complete(utteranceId: String?) {
        utteranceId?.let { pending.remove(it)?.complete(Unit) }
    }

    /** Hindi voice for Devanagari replies, Indian English otherwise. */
    private fun localeFor(text: String): Locale {
        val preferred = if (text.any { it in 'ऀ'..'ॿ' }) HINDI else INDIAN_ENGLISH
        return if (tts.isLanguageAvailable(preferred) >= TextToSpeech.LANG_AVAILABLE) preferred else Locale.US
    }

    private companion object {
        const val TAG = "SeemaTTS"
        const val INIT_TIMEOUT_MS = 5_000L
        const val MIN_SPEAK_TIMEOUT_MS = 10_000L
        const val MS_PER_CHAR = 150L
        val HINDI: Locale = Locale.forLanguageTag("hi-IN")
        val INDIAN_ENGLISH: Locale = Locale.forLanguageTag("en-IN")
    }
}
