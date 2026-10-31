package com.seema.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordDetection
import com.rementia.openwakeword.lib.model.WakeWordModel
import java.util.Locale
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Always-on SEEMA voice loop:
 * wake word (openWakeWord) → command (SpeechRecognizer) → agent (ADK) → reply (TextToSpeech).
 */
class SeemaVoiceService : Service() {

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            // AudioRecord failures (mic held by another app, etc.) surface here.
            Log.e(TAG, "Voice pipeline failed", error)
            scheduleRestart()
        }
    )

    @Volatile private var engine: WakeWordEngine? = null
    @Volatile private var listeningSince = 0L
    /** True when no wake word model is installed; SEEMA then works through TALK, tile and widget. */
    @Volatile private var wakeWordUnavailable = false
    private var engineInitJob: Job? = null
    private var restartJob: Job? = null
    @Volatile private var wakeWordJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var speechRecognizer: SeemaSpeechRecognizer
    private lateinit var textToSpeech: SeemaTextToSpeech
    private lateinit var callAnnouncer: CallAnnouncer
    private lateinit var powerTriggers: PowerTriggerMonitor
    private lateinit var offlineBrain: OfflineBrain
    private lateinit var audioManager: AudioManager

    /** Held for a whole conversation so music pauses while SEEMA listens, then resumes. */
    private val conversationFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SeemaAgent.init(this)
        SeemaReminders.init(this)
        SeemaPlaces.init(this)
        // Geofences are dropped when Location is toggled; re-adding on start keeps them alive.
        SeemaGeofences.registerAll(this)
        ProactiveScheduler.ensureScheduled(this)
        RoutineScheduler.ensureScheduled(this)
        audioManager = getSystemService(AudioManager::class.java)
        speechRecognizer = SeemaSpeechRecognizer(this)
        textToSpeech = SeemaTextToSpeech(this)
        offlineBrain = OfflineBrain(this)
        // Stay quiet about callers while a conversation is using the mic and speaker.
        callAnnouncer = CallAnnouncer(this, serviceScope, textToSpeech) { wakeWordJob?.isActive != true }
        callAnnouncer.register()
        SeemaTriggers.init(this)
        powerTriggers = PowerTriggerMonitor(this).also { it.register() }

        serviceScope.launch {
            // One collector, so a reminder and a chat announcement never talk over each other.
            merge(SeemaNotificationListener.announcements, SeemaState.alerts)
                .collect { text -> announce(text) }
        }
        serviceScope.launch {
            SeemaState.commands.collect { command ->
                when (command) {
                    SeemaCommand.START_CONVERSATION -> launchConversation()
                    SeemaCommand.DAILY_BRIEFING -> launchConversation(initialCommand = BRIEFING_QUERY)
                }
            }
        }
        serviceScope.launch {
            SeemaState.routineRequests.collect { name -> launchConversation(initialCommand = "Run my $name routine.") }
        }
        SeemaState.setServiceRunning(true)

        serviceScope.launch {
            // Routines are part of the widget too (shortcut buttons), so redraw when they change.
            combine(SeemaState.state, SeemaState.serviceRunning, SeemaRoutines.routinesFlow) { state, running, _ ->
                state to running
            }.collect { (state, running) -> SeemaWidgetProvider.updateAll(this@SeemaVoiceService, state, running) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        if (engineInitJob == null) initEngine()
        // Headset button / assistant gesture started us: talk right away.
        if (intent?.action == ACTION_TALK) launchConversation()
        return START_STICKY
    }

    override fun onDestroy() {
        val current = engine
        engine = null
        current?.stop()
        callAnnouncer.unregister()
        powerTriggers.unregister()
        serviceScope.cancel()
        textToSpeech.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        SeemaState.update(AgentState.IDLE)
        SeemaState.setServiceRunning(false)
        SeemaWidgetProvider.updateAll(this, AgentState.IDLE, running = false)
        // Give in-flight inference a moment to finish before closing ONNX sessions.
        if (current != null) {
            CoroutineScope(Dispatchers.Default).launch {
                delay(ENGINE_RELEASE_DELAY_MS)
                current.release()
            }
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- engine

    private fun initEngine() {
        engineInitJob = serviceScope.launch {
            val modelPath = resolveWakeWordModel() ?: return@launch

            // Loads three ONNX sessions; kept off the main thread.
            val newEngine = WakeWordEngine(
                context = applicationContext,
                models = listOf(
                    WakeWordModel(
                        name = WAKE_WORD_NAME,
                        modelPath = modelPath,
                        threshold = WAKE_WORD_THRESHOLD
                    )
                ),
                detectionMode = DetectionMode.SINGLE_BEST,
                detectionCooldownMs = DETECTION_COOLDOWN_MS,
                scope = serviceScope
            )
            engine = newEngine
            Log.i(TAG, "Wake word engine ready with model '$modelPath'")

            launch {
                newEngine.detections.collect { detection -> dispatchWakeWord(detection) }
            }
            // A conversation may already hold the mic (started by the headset button); it re-arms when done.
            if (wakeWordJob?.isActive != true) startListening()
        }
    }

    private fun startListening() {
        val current = engine
        if (current == null) {
            // No wake word model: stay usable through TALK, the tile, the widget and the headset button.
            if (wakeWordUnavailable) {
                SeemaState.update(AgentState.LISTENING)
                updateNotification("Wake word off · tap TALK to speak")
            }
            return
        }
        try {
            current.start()
            listeningSince = SystemClock.elapsedRealtime()
            SeemaState.update(AgentState.LISTENING)
            updateNotification("Listening for \"$WAKE_WORD_NAME\"")
            Log.i(TAG, "Listening for wake word")
        } catch (e: Exception) {
            Log.e(TAG, "Could not start listening", e)
            scheduleRestart()
        }
    }

    private fun scheduleRestart() {
        if (restartJob?.isActive == true) return
        engine?.stop()
        SeemaState.update(AgentState.IDLE)
        updateNotification("Microphone unavailable — retrying…")
        restartJob = serviceScope.launch {
            delay(RESTART_DELAY_MS)
            startListening()
        }
    }

    /** Stops the wake word engine and waits until its AudioRecord has been released. */
    private suspend fun releaseMicrophone() {
        engine?.stop()
        delay(MIC_HANDOFF_DELAY_MS)
    }

    /** Picks seema.onnx, falling back to the stock "hey jarvis" model for testing. */
    private fun resolveWakeWordModel(): String? {
        val available = assets.list("")?.toSet().orEmpty()

        val missingFeatures = FEATURE_MODELS.filterNot { it in available }
        if (missingFeatures.isNotEmpty()) {
            reportFatal("Missing in assets: ${missingFeatures.joinToString()}")
            return null
        }

        val model = WAKE_WORD_MODELS.firstOrNull { it in available }
        if (model == null) {
            // Not fatal: everything except the spoken wake word still works.
            Log.w(TAG, "No wake word model in assets")
            wakeWordUnavailable = true
            SeemaState.setResponse(
                "Wake word off: add seema.onnx or hey_jarvis_v0.1.onnx to app/src/main/assets. " +
                    "Until then, tap TALK."
            )
            startListening()
            return null
        }
        if (model != WAKE_WORD_MODELS.first()) {
            Log.w(TAG, "${WAKE_WORD_MODELS.first()} not found, using fallback model '$model'")
        }
        return model
    }

    private fun reportFatal(message: String) {
        Log.e(TAG, message)
        SeemaState.update(AgentState.IDLE)
        SeemaState.setResponse("ERROR: $message")
        updateNotification(message)
        stopSelf()
    }

    // ------------------------------------------------------------ voice loop

    private fun dispatchWakeWord(detection: WakeWordDetection) {
        // Ignore new triggers while the previous one is still being handled.
        if (wakeWordJob?.isActive == true) return
        // The engine keeps ~1.3 s of audio features across restarts; ignore re-triggers from them.
        if (SystemClock.elapsedRealtime() - listeningSince < REARM_GUARD_MS) {
            Log.d(TAG, "Ignoring stale detection right after re-arming")
            return
        }
        Log.i(TAG, "🎤 SEEMA DETECTED! (score=${"%.3f".format(detection.score)})")
        launchConversation()
    }

    /**
     * Starts a conversation unless one is already running. Triggered by the wake word, the
     * Quick Settings tile or the in-app buttons; [initialCommand] skips listening for the first turn.
     */
    @Synchronized
    private fun launchConversation(initialCommand: String? = null) {
        if (wakeWordJob?.isActive == true) return
        wakeWordJob = serviceScope.launch {
            audioManager.requestAudioFocus(conversationFocus)
            releaseMicrophone()
            try {
                runConversation(initialCommand)
            } finally {
                // Back to always-on wake word listening, whatever happened above.
                startListening()
                audioManager.abandonAudioFocusRequest(conversationFocus)
            }
        }
    }

    /**
     * One wake-word command, then follow-up commands without the wake word until the user
     * stays silent, says goodbye, an error occurs, a tool such as a phone call ends it, or
     * [MAX_CONVERSATION_TURNS] is reached.
     */
    private suspend fun runConversation(initialCommand: String? = null) {
        SeemaState.consumeConversationEndRequest()
        var command = initialCommand ?: captureCommand(followUp = false) ?: return
        var turns = 0
        while (true) {
            if (isExitPhrase(command)) {
                SeemaState.setResponse("YOU: $command\n\nSEEMA: $GOODBYE_REPLY")
                speak(GOODBYE_REPLY)
                return
            }
            if (!handleCommand(command)) return
            if (SeemaState.consumeConversationEndRequest()) return
            if (++turns >= MAX_CONVERSATION_TURNS) return

            delay(FOLLOW_UP_GAP_MS)
            command = captureCommand(followUp = true) ?: return
        }
    }

    /**
     * Returns the user's spoken command, or null. After the wake word, failures are spoken;
     * in follow-up mode silence simply ends the conversation.
     */
    private suspend fun captureCommand(followUp: Boolean): String? {
        if (!speechRecognizer.isAvailable()) {
            if (!followUp) {
                SeemaState.setResponse("ERROR: No speech recognition service. Enable the Google app.")
                speak("Speech recognition is not available on this phone.")
            }
            return null
        }

        val conversation = if (followUp) SeemaState.lastResponse.value else ""
        SeemaState.update(AgentState.HEARING)
        showTranscript(conversation, "🎙 …")
        updateNotification(if (followUp) "Anything else?" else "Listening to you…")

        val result = try {
            withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
                speechRecognizer.listenOnce(
                    languageTag = SPEECH_LANGUAGE,
                    speechStartTimeoutMs = if (followUp) FOLLOW_UP_WINDOW_MS else null,
                    offline = !NetworkStatus.isOnline(this@SeemaVoiceService) ||
                        VoiceSettings(this@SeemaVoiceService).preferOfflineRecognition,
                    onLevel = SeemaState::setAudioLevel
                ) { partial ->
                    showTranscript(conversation, "🎙 $partial")
                }
            } ?: SpeechResult.NoSpeech
        } finally {
            SeemaState.setAudioLevel(0f)
        }

        return when (result) {
            is SpeechResult.Text -> {
                Log.i(TAG, "👤 Command${if (followUp) " (follow-up)" else ""}: ${result.text}")
                result.text
            }
            SpeechResult.NoSpeech -> {
                if (followUp) {
                    Log.d(TAG, "Follow-up window closed")
                    SeemaState.setResponse(conversation)
                } else {
                    SeemaState.setResponse("No command heard.")
                    speak(NO_SPEECH_REPLY)
                }
                null
            }
            is SpeechResult.Error -> {
                if (followUp) {
                    Log.w(TAG, "Follow-up recognition failed: ${result.message}")
                    SeemaState.setResponse(conversation)
                } else {
                    SeemaState.setResponse("ERROR: Speech recognition — ${result.message}")
                    speak(STT_ERROR_REPLY)
                }
                null
            }
        }
    }

    /** Runs [command] through the agent and speaks the reply. Returns false if the agent failed. */
    private suspend fun handleCommand(command: String): Boolean {
        SeemaState.update(AgentState.THINKING)
        SeemaState.setResponse("YOU: $command")
        updateNotification("Thinking…")

        // No internet: answer basic commands locally. Also used if the agent failed because the
        // connection dropped mid-request.
        var offline = !NetworkStatus.isOnline(this)
        var reply = if (offline) offlineBrain.handle(command) else SeemaAgent.runAgent(command)
        var note = if (offline) " ⚠ OFFLINE" else ""
        when {
            // Gemini's quota for this API key is used up (free tier resets daily): basics still work.
            SeemaAgent.isQuotaError(reply) -> {
                Log.w(TAG, "Gemini quota exhausted: $reply")
                offline = true
                note = " ⚠ GEMINI QUOTA USED UP — BASIC MODE"
                reply = offlineBrain.handle(command)
            }
            !offline && reply.startsWith("ERROR:") && !NetworkStatus.isOnline(this) -> {
                offline = true
                note = " ⚠ OFFLINE"
                reply = offlineBrain.handle(command)
            }
        }
        Log.i(TAG, "🤖 SEEMA${if (offline) " (offline)" else ""}: $reply")
        SeemaState.setResponse("YOU: $command\n\nSEEMA$note: $reply")

        val failed = reply.startsWith("ERROR:")
        speak(if (failed) AGENT_ERROR_REPLY else reply)
        return !failed
    }

    private suspend fun speak(text: String) {
        SeemaState.update(AgentState.SPEAKING)
        updateNotification("Speaking…")
        textToSpeech.speak(text)
    }

    /** Speaks a chat announcement or reminder unless SEEMA is busy or the phone is in a call. */
    private suspend fun announce(text: String) {
        if (wakeWordJob?.isActive == true) return
        if (audioManager.mode != AudioManager.MODE_NORMAL) return
        // Respect Do Not Disturb: the notification still appears, SEEMA just stays quiet.
        val filter = getSystemService(NotificationManager::class.java).currentInterruptionFilter
        if (filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) return
        Log.i(TAG, "💬 $text")
        SeemaState.setResponse("💬 $text")
        textToSpeech.speak(text)
    }

    private fun showTranscript(conversation: String, line: String) {
        SeemaState.setResponse(if (conversation.isEmpty()) line else "$conversation\n\n$line")
    }

    private fun isExitPhrase(command: String): Boolean {
        val normalized = command.lowercase(Locale.ROOT)
            .replace(NON_WORD_CHARS, " ")
            .split(' ')
            .filter { it.isNotEmpty() && it !in WAKE_WORD_MENTIONS }
            .joinToString(" ")
        return normalized in EXIT_PHRASES
    }

    // ------------------------------------------------------ foreground/system

    private fun enterForeground(): Boolean {
        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            Log.e(TAG, "RECORD_AUDIO not granted; cannot start")
            return false
        }
        // Location type lets the weather tool read the location while SEEMA runs in the background.
        // It may only be declared once location permission is granted, or startForeground throws.
        val locationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            (if (locationGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        return try {
            createChannel()
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Initializing…"),
                serviceTypes
            )
            true
        } catch (e: Exception) {
            // Android 14+ only allows microphone FGS to start while the app is visible.
            Log.e(TAG, "Cannot enter foreground — open SEEMA to start listening", e)
            false
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Seema::VoiceService")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SEEMA Voice",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Always-on wake word listener" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SEEMA ONLINE")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

    companion object {
        private const val TAG = "SeemaVoiceService"

        const val CHANNEL_ID = "seema_voice"
        /** Start intent action: begin a conversation immediately (headset button, assistant gesture). */
        const val ACTION_TALK = "com.seema.assistant.action.TALK"
        const val NOTIFICATION_ID = 1001

        private const val WAKE_WORD_NAME = "Seema"
        private const val WAKE_WORD_THRESHOLD = 0.5f
        private const val DETECTION_COOLDOWN_MS = 2000L
        private const val RESTART_DELAY_MS = 3000L
        private const val ENGINE_RELEASE_DELAY_MS = 500L

        /** Time for the engine's AudioRecord loop to exit and release the mic. */
        private const val MIC_HANDOFF_DELAY_MS = 400L
        /** Covers the engine's 16-frame (~1.3 s) feature window after re-arming. */
        private const val REARM_GUARD_MS = 2000L
        /** Upper bound on one spoken command, in case the recognizer never calls back. */
        private const val COMMAND_TIMEOUT_MS = 15_000L
        /** Follow-up mode: how long SEEMA waits for you to start speaking after a reply. */
        private const val FOLLOW_UP_WINDOW_MS = 6_000L
        /** Short pause after speaking so the end of the reply is not picked up by the mic. */
        private const val FOLLOW_UP_GAP_MS = 250L
        /** Safety cap on wake-word-free turns in one conversation. */
        private const val MAX_CONVERSATION_TURNS = 10

        /** "en-IN" understands Indian English and romanised Hinglish; use "hi-IN" for Hindi. */
        private const val SPEECH_LANGUAGE = "en-IN"

        private const val NO_SPEECH_REPLY = "I didn't catch that."
        private const val STT_ERROR_REPLY = "Sorry, I couldn't hear you properly."
        private const val AGENT_ERROR_REPLY = "Sorry, something went wrong. The details are on screen."
        private const val GOODBYE_REPLY = "Anytime. I'll be right here."
        private const val BRIEFING_QUERY = "Good morning! Give me my daily briefing."

        private val NON_WORD_CHARS = Regex("[^\\p{L}\\p{M}\\p{N}' ]")
        private val WAKE_WORD_MENTIONS = setOf("seema", "सीमा")

        /** Whole-utterance phrases that end follow-up mode (wake word and punctuation ignored). */
        private val EXIT_PHRASES = setOf(
            "stop", "stop it", "no", "nope", "nothing", "nothing else", "that's all", "that is all",
            "thanks", "thank you", "thank you so much", "no thanks", "no thank you",
            "never mind", "bye", "goodbye", "ok bye", "okay bye", "ok thanks", "okay thanks",
            "bas", "bas karo", "bas itna hi", "ruko", "theek hai bas", "shukriya",
            "dhanyavad", "dhanyawad", "बस", "बस करो", "रुको", "शुक्रिया", "धन्यवाद"
        )

        /** Loaded internally by the library under these exact names. */
        private val FEATURE_MODELS = listOf("melspectrogram.onnx", "embedding_model.onnx")

        /** First match wins. */
        private val WAKE_WORD_MODELS = listOf("seema.onnx", "hey_jarvis_v0.1.onnx")
    }
}
