package com.seema.assistant

import android.content.Context
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Offline fallback for when Gemini cannot be reached: understands common commands with simple
 * pattern matching and runs the same tool classes directly. English and common Hinglish.
 */
class OfflineBrain(context: Context) {

    private val appContext = context.applicationContext
    private val phone by lazy { SeemaTools(appContext) }
    private val clock by lazy { SeemaClockTools(appContext) }
    private val media by lazy { SeemaMediaTools(appContext) }
    private val settings by lazy { SeemaSettingsTools(appContext) }
    private val reminders by lazy { SeemaReminderTools(appContext) }
    private val comms by lazy { SeemaCommsTools(appContext) }
    private val notifications by lazy { SeemaNotificationTools(appContext) }

    /** A call waiting for "yes" in the next follow-up turn: contact name to number. */
    private var pendingCall: Pair<String, String>? = null

    /** Always returns something to speak; never an "ERROR:" string. */
    fun handle(command: String): String {
        val text = command.lowercase(Locale.ROOT).trim().trimEnd('.', '?', '!')

        pendingCall?.let { (name, number) ->
            pendingCall = null
            return if (YES.any { text.startsWith(it) }) result(comms.makePhoneCall(number), "Calling $name.") else "Okay, I won't call."
        }

        return when {
            has(text, "torch", "flashlight", "flash light") ->
                onOff(text)?.let { result(phone.setFlashlight(it), "Torch ${word(it)}.") } ?: "Say torch on, or torch off."
            has(text, "do not disturb", "dnd") ->
                onOff(text)?.let { result(settings.setDoNotDisturb(it), "Do Not Disturb is ${word(it)}.") } ?: "Say DND on, or DND off."
            has(text, "wifi", "wi-fi", "wi fi") ->
                onOff(text)?.let { result(settings.setWifi(it), "Wi-Fi is ${word(it)}.") } ?: "Say Wi-Fi on, or Wi-Fi off."
            has(text, "bluetooth") ->
                onOff(text)?.let { result(settings.setBluetooth(it), "Bluetooth is ${word(it)}.") } ?: "Say Bluetooth on, or Bluetooth off."
            has(text, "battery") -> battery()
            has(text, "volume") || text == "mute" -> volume(text)
            has(text, "timer") -> timer(text)
            has(text, "remind") -> reminder(text)
            has(text, "alarm") -> alarm(text)
            has(text, "next song", "next track", "skip") -> result(media.controlMedia("next"), "Next track.")
            has(text, "previous song", "previous track", "last song") -> result(media.controlMedia("previous"), "Previous track.")
            has(text, "pause", "stop the music", "stop music", "gaana band", "music band") -> result(media.controlMedia("pause"), "Paused.")
            has(text, "resume", "play music", "play the music", "gaana chalao") -> result(media.controlMedia("play"), "Playing.")
            has(text, "what time", "the time", "kitne baje", "time kya", "samay") -> "It's ${LocalTime.now().format(TIME_FORMAT)}."
            has(text, "date", "what day", "tareekh", "aaj kya din") -> "Today is ${LocalDate.now().format(DATE_FORMAT)}."
            text.startsWith("call ") -> call(text.removePrefix("call ").trim())
            text.endsWith(" ko call karo") -> call(text.removeSuffix(" ko call karo").trim())
            text.startsWith("open ") -> openApp(text.removePrefix("open ").trim())
            text.endsWith(" kholo") -> openApp(text.removeSuffix(" kholo").trim())
            has(text, "notification", "messages") -> readNotifications()
            else -> HELP
        }
    }

    // --------------------------------------------------------------- commands

    private fun battery(): String {
        val status = phone.getBatteryStatus()
        val charging = if (status["charging"] == "true") ", and charging" else ""
        return "Battery is at ${status["level_percent"]} percent$charging."
    }

    private fun volume(text: String): String {
        val percent = if (text == "mute" || has(text, "mute")) 0 else firstNumber(text)
            ?: return "Say a volume percentage, like volume 40."
        return result(phone.setMediaVolume(percent), "Volume set to $percent percent.")
    }

    private fun timer(text: String): String {
        val seconds = DURATION.findAll(text).sumOf { match ->
            val amount = match.groupValues[1].toInt()
            when (match.groupValues[2].first()) {
                'h' -> amount * 3600
                'm' -> amount * 60
                else -> amount
            }
        }
        if (seconds == 0) return "How long? Say, for example, timer for 10 minutes."
        val result = clock.setTimer(seconds)
        return result(result, "Timer set for ${result["duration"]}.")
    }

    private fun reminder(text: String): String {
        val message = text.substringAfter(" to ", "").ifBlank { "Reminder" }.replaceFirstChar { it.uppercase() }
        RELATIVE.find(text)?.let { match ->
            val amount = match.groupValues[1].toInt()
            val minutes = if (match.groupValues[2].startsWith("h")) amount * 60 else amount
            val result = reminders.setReminder(message = message, inMinutes = minutes)
            return result(result, "I'll remind you ${result["when"]}: $message.")
        }
        val (hour, minute) = parseTime(text) ?: return "When? Say, for example, remind me in 20 minutes to call Papa."
        val result = reminders.setReminder(message = message, hour = hour, minute = minute)
        return result(result, "I'll remind you ${result["when"]}: $message.")
    }

    private fun alarm(text: String): String {
        val (hour, minute) = parseTime(text) ?: return "What time? Say, for example, alarm at 7 AM."
        val result = clock.setAlarm(hour, minute)
        return result(result, "Alarm set for ${result["alarm_time"]}, ${result["rings"] ?: ""}.")
    }

    private fun call(name: String): String {
        if (name.isBlank()) return "Who should I call?"
        val found = comms.findContacts(name)
        if (found["status"] != "ok") return found["error"] ?: "I couldn't find that contact."
        if (found["match_count"] != "1") return "I found several contacts named $name. Please say the full name."
        // Format from findContacts: "1. Mummy (mobile): +9198…"
        val line = found["matches"].orEmpty()
        val contact = line.substringAfter(". ").substringBefore(" (")
        pendingCall = contact to line.substringAfterLast(": ")
        return "Shall I call $contact?"
    }

    private fun openApp(name: String): String {
        val result = phone.openApp(name)
        return result(result, "Opening ${result["opened"]}.")
    }

    private fun readNotifications(): String {
        val result = notifications.readNotifications(count = 3)
        if (result["status"] != "ok") return result["error"] ?: "I can't read notifications right now."
        if (result["count"] == "0") return "You have no notifications."
        return "Your latest notifications: ${result["notifications"]}"
    }

    // --------------------------------------------------------------- helpers

    private fun has(text: String, vararg phrases: String) = phrases.any { text.contains(it) }

    /** true for on/chalu/enable, false for off/band/disable, null if neither is said. */
    private fun onOff(text: String): Boolean? = when {
        OFF_WORDS.containsMatchIn(text) -> false
        ON_WORDS.containsMatchIn(text) -> true
        else -> null
    }

    private fun word(on: Boolean) = if (on) "on" else "off"

    private fun firstNumber(text: String): Int? = NUMBER.find(text)?.value?.toInt()

    /** "7", "7:30", "7.30 pm", "6 baje shaam" → hour (0–23) and minute. */
    private fun parseTime(text: String): Pair<Int, Int>? {
        val match = CLOCK_TIME.find(text) ?: return null
        var hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
        val meridiem = match.groupValues[3].replace(".", "")
        val evening = meridiem == "pm" || has(text, "evening", "night", "shaam", "raat")
        if (evening && hour < 12) hour += 12
        if (meridiem == "am" && hour == 12) hour = 0
        return if (hour in 0..23 && minute in 0..59) hour to minute else null
    }

    /** Success text, or the tool's own explanation when it failed or needs the user. */
    private fun result(result: Map<String, String>, success: String): String = when (result["status"]) {
        "ok" -> success
        "needs_user" -> result["message"] ?: "Please finish that on the screen."
        else -> result["error"] ?: "That didn't work."
    }

    private companion object {
        val YES = listOf("yes", "yeah", "yup", "haan", "ha ", "ji", "sure", "ok", "okay", "call")
        val ON_WORDS = Regex("\\b(on|chalu|jalao|enable|start)\\b")
        val OFF_WORDS = Regex("\\b(off|band|bujhao|disable)\\b")
        val NUMBER = Regex("\\d{1,3}")
        val DURATION = Regex("(\\d+)\\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)")
        val RELATIVE = Regex("in (\\d+)\\s*(minutes?|mins?|hours?|hrs?)")
        val CLOCK_TIME = Regex("(\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?")
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)

        const val HELP = "I'm offline right now, so I can only do the basics: time and date, battery, " +
            "torch, volume, Do Not Disturb, alarms, timers, reminders, music controls, opening apps, " +
            "calls and notifications. Everything else comes back when the internet does."
    }
}
