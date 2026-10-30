package com.seema.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

sealed interface SpeechResult {
    data class Text(val text: String) : SpeechResult
    data object NoSpeech : SpeechResult
    data class Error(val message: String) : SpeechResult
}

/** One-shot speech-to-text on top of Android's SpeechRecognizer. */
class SeemaSpeechRecognizer(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext) || hasOnDeviceRecognizer()

    /** Android 12+ fully on-device recognizer (needs the offline language pack). */
    private fun hasOnDeviceRecognizer(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

    /**
     * Listens for a single utterance. [onPartial] receives live transcripts on the main thread.
     * [onLevel] receives the microphone level from 0 to 1, roughly 10–20 times a second.
     * With [speechStartTimeoutMs], gives up with [SpeechResult.NoSpeech] if the user has not
     * started speaking by then. Cancelling the calling coroutine aborts recognition and frees
     * the microphone.
     */
    suspend fun listenOnce(
        languageTag: String,
        speechStartTimeoutMs: Long? = null,
        offline: Boolean = false,
        onLevel: (Float) -> Unit = {},
        onPartial: (String) -> Unit
    ): SpeechResult = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            // Offline: the on-device recognizer when available, else the normal one told to prefer offline.
            val recognizer = if (offline && hasOnDeviceRecognizer()) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            } else {
                SpeechRecognizer.createSpeechRecognizer(appContext)
            }
            val destroyed = AtomicBoolean(false)
            lateinit var speechStartTimeout: Runnable

            fun destroy() {
                if (destroyed.compareAndSet(false, true)) recognizer.destroy()
            }

            fun finish(result: SpeechResult) {
                mainHandler.removeCallbacks(speechStartTimeout)
                if (!continuation.isActive) return
                destroy()
                continuation.resume(result)
            }

            speechStartTimeout = Runnable {
                Log.d(TAG, "No speech started within ${speechStartTimeoutMs}ms")
                recognizer.cancel()
                finish(SpeechResult.NoSpeech)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "Ready for speech")
                    speechStartTimeoutMs?.let { mainHandler.postDelayed(speechStartTimeout, it) }
                }

                override fun onBeginningOfSpeech() {
                    mainHandler.removeCallbacks(speechStartTimeout)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults.firstResult()?.takeIf { it.isNotBlank() }?.let(onPartial)
                }

                override fun onResults(results: Bundle?) {
                    val text = results.firstResult()?.trim()
                    finish(if (text.isNullOrEmpty()) SpeechResult.NoSpeech else SpeechResult.Text(text))
                }

                override fun onError(error: Int) {
                    Log.w(TAG, "Recognition error $error: ${describe(error)}")
                    finish(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechResult.NoSpeech
                            else -> SpeechResult.Error(describe(error))
                        }
                    )
                }

                override fun onRmsChanged(rmsdB: Float) {
                    onLevel(((rmsdB - RMS_SILENCE_DB) / (RMS_LOUD_DB - RMS_SILENCE_DB)).coerceIn(0f, 1f))
                }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            continuation.invokeOnCancellation {
                mainHandler.post {
                    mainHandler.removeCallbacks(speechStartTimeout)
                    recognizer.cancel()
                    destroy()
                }
            }

            recognizer.startListening(buildIntent(languageTag, offline))
        }
    }

    private fun buildIntent(languageTag: String, offline: Boolean) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    @SuppressLint("InlinedApi")
    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout (download the offline speech pack to listen without internet)"
        SpeechRecognizer.ERROR_NETWORK -> "Network error (download the offline speech pack to listen without internet)"
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_SERVER -> "Recognition server error"
        SpeechRecognizer.ERROR_CLIENT -> "Recognizer client error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
        SpeechRecognizer.ERROR_NO_MATCH -> "Speech not recognized"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Recognition service disconnected"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language pack not downloaded"
        else -> "Unknown error ($error)"
    }

    private companion object {
        const val TAG = "SeemaSTT"

        // Typical onRmsChanged range on Google's recognizer.
        const val RMS_SILENCE_DB = -2f
        const val RMS_LOUD_DB = 10f
    }
}
