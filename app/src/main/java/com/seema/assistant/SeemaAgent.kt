package com.seema.assistant

import android.content.Context
import android.util.Log
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The "brain": a Google ADK LlmAgent backed by Gemini. Call [init] before [runAgent]. */
object SeemaAgent {

    private const val TAG = "SeemaAgent"
    private const val APP_NAME = "Seema"
    private const val USER_ID = "owner"
    /** From local.properties (SEEMA_GEMINI_MODEL); defaults to gemini-flash-latest. */
    private val MODEL_NAME = BuildConfig.SEEMA_GEMINI_MODEL

    private val SYSTEM_PROMPT = """
        You are SEEMA, a personal AI assistant living on your owner's Android phone, inspired by JARVIS.
        Your replies are spoken aloud, so:
        - Answer in 1 to 3 short sentences.
        - Never use markdown, bullet points, emoji or code blocks.
        - Be warm, confident and concise, with a light touch of wit.
        You can control the phone with tools. Always use a tool instead of guessing or claiming
        you did something:
        - getCurrentTime for the time or date anywhere.
        - setFlashlight to turn the torch on or off.
        - getBatteryStatus for battery level, charging or temperature.
        - openApp to open an app by name.
        - setMediaVolume to change music or video volume.
        - findContacts, makePhoneCall and sendSms for calls and text messages.
        - readMessages to read SMS aloud ("read my last message", "any messages from Papa?").
          Say who it is from and when, then the message. Summarise long promotional texts
          in one sentence instead of reading them word for word.
        - getWeather for weather and rain questions. Omit the city to use the phone's location.
          Answer the actual question first ("Yes, rain is likely from 4 PM to 7 PM"), then
          add the temperature. Mention an umbrella if rain is likely.
        - readNotifications for notifications and WhatsApp or other chat messages
          ("read my notifications", "what did Rahul say on WhatsApp?"). Group lines from the
          same chat together and skip obvious promotional noise unless asked.
        - replyToMessage to answer a WhatsApp or other chat by voice. Read back the chat name
          and the exact reply, and wait for a yes before calling it. It only works while that
          chat's notification is showing; if it fails, offer to send an SMS instead.
        - runRoutine, createRoutine, listRoutines and deleteRoutine for routines (macros). When the
          user's words match a routine trigger phrase listed below, call runRoutine first and then
          perform its steps; this takes priority over everything else, including the briefing.
          To change a routine, call createRoutine again with the same name and the full new steps.
          scheduleRoutine and unscheduleRoutine make a routine run automatically at a set time.
        - setLocationRoutine, setPowerRoutine, listAutomations and removeAutomation for
          automations: run a routine whenever the user arrives at or leaves a place, or on
          plugged in, unplugged, battery low or fully charged, optionally within hours
          ("good night when I plug in after 11 PM" = plugged in, fromHour 23, toHour 6).
        - getDailyBriefing when the user greets you with "good morning" and no routine matches (or asks for a briefing
          or how their day looks). Turn it into a warm spoken briefing of 4 to 6 sentences:
          greeting with the date, weather and whether to carry an umbrella, next alarm,
          missed calls and unread messages by sender, and battery only if it is below 30 percent.
          Skip anything that is "none" or "unknown".
        - getPhoneSettings, setWifi, setBluetooth, setDoNotDisturb, setRingerMode, setBrightness
          and setAutoBrightness for phone settings. If a tool returns status "needs_user",
          Android needs one tap from the user: say exactly which switch or button to tap.
          "Silent" or "phone silent karo" means setRingerMode silent; if that fails, offer
          vibrate or Do Not Disturb.
        - rememberFact, recallMemories, forgetMemory and forgetEverything for long-term memory.
          Save a fact when the user asks you to remember something, or clearly states a lasting
          personal fact (family names, birthdays, preferences, where they parked). Write it as one
          short sentence about the user. Do not save one-off requests or small talk.
          Before forgetEverything, ask for confirmation.
        - controlMedia, getNowPlaying and playMusic for music in any app ("next song", "pause",
          "what's playing", "play Arijit Singh on Spotify"). Music pauses automatically while
          you talk with the user and resumes afterwards, so never say it is paused unless the
          user paused it. After starting music, reply in a few words.
        - setReminder, listReminders and cancelReminder for reminders. "Remind me…" means
          setReminder (you will speak it and show a notification); "wake me up" or "alarm"
          means setAlarm. Use the current local time below for relative times. For "every day",
          "every weekday" or "every Monday", pass repeatDays.
        - setDailyBriefing, setRainAlerts and getProactiveSettings for automatic background
          features: a spoken daily briefing at a chosen time, and alerts when rain starts or is
          about to start.
        - savePlace, listPlaces, forgetPlace, setLocationReminder, listLocationReminders and
          cancelLocationReminder for places and location reminders ("remind me to buy milk when
          I reach home", "remember this place as office"). If the place is not saved and is not
          an address, offer to save the current location under that name first.
        - setAlarm, setTimer, getNextAlarm and showAlarms for alarms and timers.
          Convert spoken times to 24-hour format (7 AM = 7, 7 PM = 19, midnight = 0).
          For relative alarms like "wake me in two hours", call getLocalTime first and add.
          "Timer for 10 minutes" means setTimer with 600 seconds. If the user says only
          "alarm for 7" and it is unclear whether AM or PM, ask.
          To delete or change alarms, use showAlarms; you cannot delete them yourself.
        Calls and messages must never go to the wrong person:
        - Always run findContacts first when the user names a person. Use only numbers it
          returns, or digits the user dictated. Never invent a number.
        - If there are several matches, read out the names and ask which one.
        - Before makePhoneCall, ask "Shall I call <name>?" and wait for a yes.
        - Before sendSms, read back the recipient and the exact message and wait for a yes.
          Send the message text exactly as the user dictated it, without rewording.
        - If the user says no, do not call the tool.
        If a tool returns status "error", tell the user briefly what went wrong.
        This is a spoken conversation that continues without the wake word, so you may end
        with a short follow-up question when it is genuinely useful.
        If the user speaks Hindi or Hinglish, reply in the same language.
    """.trimIndent()

    @Volatile private var appContext: Context? = null

    private val agent by lazy {
        val context = checkNotNull(appContext) { "SeemaAgent.init(context) was not called" }
        val clockTools = SeemaClockTools(context)
        val weatherTools = SeemaWeatherTools(context)
        LlmAgent(
            name = "Seema",
            description = "Personal JARVIS-style voice assistant.",
            model = Gemini(name = MODEL_NAME, apiKey = BuildConfig.GEMINI_API_KEY),
            // Rebuilt every turn, so new memories and the current time are always included.
            instruction = Instruction { _ -> Content(parts = listOf(Part(text = buildInstruction()))) },
            tools = SeemaTools(context).generatedTools() +
                SeemaCommsTools(context).generatedTools() +
                clockTools.generatedTools() +
                weatherTools.generatedTools() +
                SeemaNotificationTools(context).generatedTools() +
                SeemaBriefingTools(context, weatherTools, clockTools).generatedTools() +
                SeemaSettingsTools(context).generatedTools() +
                SeemaMemoryTools().generatedTools() +
                SeemaMediaTools(context).generatedTools() +
                SeemaReminderTools(context).generatedTools() +
                SeemaPlaceTools(context).generatedTools() +
                SeemaProactiveTools(context).generatedTools() +
                SeemaRoutineTools(context).generatedTools() +
                SeemaTriggerTools(context).generatedTools()
        )
    }

    // InMemoryRunner creates the session on first use, so conversation memory
    // lasts until resetConversation() or the process dies.
    private val runner by lazy { InMemoryRunner(agent = agent, appName = APP_NAME) }

    private val mutex = Mutex()

    @Volatile private var sessionId = newSessionId()

    /** Provides the context the phone-control tools need. Safe to call more than once. */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        SeemaMemory.init(context)
        SeemaReminders.init(context)
        SeemaPlaces.init(context)
        SeemaRoutines.init(context)
        SeemaTriggers.init(context)
    }

    /** Sends [userQuery] to SEEMA and returns the final spoken-style reply. Never throws (except cancellation). */
    suspend fun runAgent(userQuery: String): String {
        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            return "ERROR: GEMINI_API_KEY is missing in local.properties"
        }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    collectReply(userQuery)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Agent run failed", e)
                    "ERROR: ${e.message ?: e::class.java.simpleName}"
                }
            }
        }
    }

    /** True when [reply] is Gemini refusing because the API key's quota (free tier: per day) is used up. */
    fun isQuotaError(reply: String): Boolean =
        reply.startsWith("ERROR:") && QUOTA_MARKERS.any { reply.contains(it, ignoreCase = true) }

    private val QUOTA_MARKERS = listOf("RESOURCE_EXHAUSTED", "429", "quota", "rate limit")

    /** Starts a fresh conversation with no memory of earlier turns. */
    fun resetConversation() {
        sessionId = newSessionId()
    }

    private suspend fun collectReply(userQuery: String): String {
        val reply = StringBuilder()
        var lastError: String? = null

        Log.d(TAG, "👤 $userQuery")
        runner.runAsync(
            userId = USER_ID,
            sessionId = sessionId,
            newMessage = Content.fromText(Role.USER, userQuery)
        ).collect { event ->
            event.errorMessage?.let {
                lastError = it
                Log.w(TAG, "Agent error ${event.errorCode}: $it")
            }
            event.functionCalls().forEach { call ->
                Log.d(TAG, "🛠 Tool call: ${call.name}(${call.args})")
            }
            event.functionResponses().forEach { response ->
                Log.d(TAG, "🛠 Tool result: ${response.name} → ${response.response}")
            }
            if (event.isFinalResponse) {
                event.content?.parts
                    ?.filter { it.thought != true }
                    ?.mapNotNull { it.text }
                    ?.forEach { reply.append(it) }
            }
        }

        val text = reply.toString().trim()
        Log.d(TAG, "🤖 $text")
        return when {
            text.isNotEmpty() -> text
            lastError != null -> "ERROR: $lastError"
            else -> "I didn't catch a response. Please try again."
        }
    }

    /** System prompt + current local time + everything SEEMA remembers (newest last). */
    private fun buildInstruction(): String = buildString {
        append(SYSTEM_PROMPT)
        append("\n\nCurrent local time: ")
        append(ZonedDateTime.now().format(NOW_FORMAT))
        val reminders = SeemaReminders.all()
        if (reminders.isNotEmpty()) {
            append("\n\nUpcoming reminders you have set:")
            reminders.take(MAX_PROMPT_REMINDERS).forEach { append("\n- ${it.message} (${describeReminder(it)})") }
        }
        val locationReminders = SeemaPlaces.reminders()
        if (locationReminders.isNotEmpty()) {
            append("\n\nLocation reminders you have set:")
            locationReminders.forEach {
                append("\n- ${it.message} (${if (it.onArrive) "on arriving at" else "on leaving"} ${it.placeName})")
            }
        }
        val places = SeemaPlaces.places()
        if (places.isNotEmpty()) {
            append("\n\nSaved places: ${places.joinToString(", ") { it.name }}")
        }
        val routines = SeemaRoutines.all()
        if (routines.isNotEmpty()) {
            append("\n\nRoutines (call runRoutine when the user says a trigger phrase):")
            routines.forEach { routine ->
                val schedule = routine.schedule?.let { "; runs automatically ${describeSchedule(it)}" } ?: ""
                append("\n- ${routine.name} (say: ${routine.triggers.joinToString(", ")}$schedule): ")
                append(routine.steps.mapIndexed { i, step -> "${i + 1}) $step" }.joinToString("; "))
            }
        }
        val automations = SeemaTriggers.locationTriggers().map { describeAutomation(it) } +
            SeemaTriggers.powerTriggers().map { describeAutomation(it) }
        if (automations.isNotEmpty()) {
            append("\n\nAutomations (routines that start by themselves):")
            automations.forEach { append("\n- $it") }
        }
        val memories = SeemaMemory.all().takeLast(MAX_PROMPT_MEMORIES)
        if (memories.isNotEmpty()) {
            append("\n\nThings you remember about the user (use them naturally; use recallMemories for older ones):")
            memories.forEach { memory ->
                val saved = Instant.ofEpochMilli(memory.savedAt).atZone(ZoneId.systemDefault()).format(SAVED_FORMAT)
                append("\n- ${memory.text} (saved $saved)")
            }
        }
    }

    private val NOW_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH)
    private val SAVED_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private const val MAX_PROMPT_MEMORIES = 100
    private const val MAX_PROMPT_REMINDERS = 20

    private fun newSessionId(): String = "session-${UUID.randomUUID()}"
}
