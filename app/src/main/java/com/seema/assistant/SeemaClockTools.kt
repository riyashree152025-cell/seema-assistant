package com.seema.assistant

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/**
 * Alarm and timer tools. They hand off to the phone's Clock app through AlarmClock intents,
 * so alarms ring reliably and can be managed in the Clock app.
 * KSP generates `SeemaClockTools.generatedTools()`.
 */
class SeemaClockTools(context: Context) {

    private val appContext = context.applicationContext

    @Tool(
        description = "Returns the phone's current local time, date and timezone. Use it to work " +
            "out absolute times for requests like 'wake me in two hours'."
    )
    fun getLocalTime(): Map<String, String> {
        val now = ZonedDateTime.now()
        return mapOf(
            "status" to "ok",
            "time" to now.format(TIME_FORMAT),
            "time_24h" to now.format(TIME_24H_FORMAT),
            "date" to now.format(DATE_FORMAT),
            "timezone" to now.zone.id
        )
    }

    @Tool(description = "Sets an alarm in the phone's Clock app.")
    fun setAlarm(
        @Param("Hour in 24-hour format, 0 to 23 (7 AM = 7, 7 PM = 19)") hour: Int,
        @Param("Minute, 0 to 59") minute: Int,
        @Param("Optional alarm label, e.g. 'Gym'") label: String? = null,
        @Param(
            "Optional comma-separated days for a repeating alarm, e.g. 'mon,tue,wed,thu,fri'. " +
                "Omit for a one-time alarm."
        ) repeatDays: String? = null
    ): Map<String, String> {
        if (hour !in 0..23 || minute !in 0..59) {
            return failure("Invalid time $hour:$minute. Hour must be 0-23 and minute 0-59.")
        }
        val days = repeatDays?.let { parseDays(it) ?: return failure("Could not understand days '$it'.") }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label?.takeIf { it.isNotBlank() } ?: DEFAULT_LABEL)
            .putExtra(AlarmClock.EXTRA_VIBRATE, true)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!days.isNullOrEmpty()) {
            intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map { it.toCalendarDay() }))
        }

        launchClock(intent)?.let { return failure(it) }

        val time = LocalTime.of(hour, minute)
        return buildMap {
            put("status", "ok")
            put("alarm_time", time.format(TIME_FORMAT))
            if (days.isNullOrEmpty()) {
                put("rings", describeNextOccurrence(time))
            } else {
                put("repeats_on", days.joinToString(", ") { it.name.lowercase(Locale.ROOT) })
            }
        }
    }

    @Tool(description = "Starts a countdown timer in the phone's Clock app.")
    fun setTimer(
        @Param("Timer length in seconds, from 1 to 86400 (10 minutes = 600)") seconds: Int,
        @Param("Optional timer label, e.g. 'Pasta'") label: String? = null
    ): Map<String, String> {
        if (seconds !in 1..MAX_TIMER_SECONDS) {
            return failure("Timer must be between 1 second and 24 hours.")
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label?.takeIf { it.isNotBlank() } ?: DEFAULT_LABEL)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)

        launchClock(intent)?.let { return failure(it) }

        val endsAt = ZonedDateTime.now().plusSeconds(seconds.toLong())
        return mapOf(
            "status" to "ok",
            "duration" to describeDuration(seconds),
            "ends_at" to endsAt.format(TIME_FORMAT)
        )
    }

    @Tool(description = "Returns when the next scheduled alarm on the phone will ring, if any.")
    fun getNextAlarm(): Map<String, String> {
        val next = appContext.getSystemService(AlarmManager::class.java).nextAlarmClock
            ?: return mapOf("status" to "ok", "next_alarm" to "none")
        val ringsAt = Instant.ofEpochMilli(next.triggerTime).atZone(ZoneId.systemDefault())
        return mapOf(
            "status" to "ok",
            "next_alarm" to "${dayWord(ringsAt.toLocalDate())} at ${ringsAt.format(TIME_FORMAT)}"
        )
    }

    @Tool(description = "Opens the Clock app's alarm list so the user can view, edit or delete alarms.")
    fun showAlarms(): Map<String, String> {
        launchClock(Intent(AlarmClock.ACTION_SHOW_ALARMS))?.let { return failure(it) }
        return mapOf("status" to "ok", "opened" to "alarm list")
    }

    // --------------------------------------------------------------- helpers

    /** Starts [intent] in the Clock app. Returns an error message, or null on success. */
    private fun launchClock(intent: Intent): String? {
        // Android 10+ blocks activity launches from background services unless this is granted.
        if (!Settings.canDrawOverlays(appContext)) {
            return "Permission missing. Tell the user to enable 'Display over other apps' for SEEMA in Settings."
        }
        if (!hasHandler(intent)) {
            return "No clock app on this phone supports this. Install Google Clock."
        }
        appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }

    private fun hasHandler(intent: Intent): Boolean {
        val packageManager = appContext.packageManager
        val match = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(
                intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return match != null
    }

    /** Parses "mon,tue" / "monday, friday" / "weekdays" / "weekends" / "daily"; null if any part is unknown. */
    private fun parseDays(raw: String): List<DayOfWeek>? {
        val days = linkedSetOf<DayOfWeek>()
        val allDays = DayOfWeek.values().toList()
        val tokens = raw.lowercase(Locale.ROOT).split(',', ' ').filter { it.isNotBlank() && it != "and" }
        for (token in tokens) {
            when (token) {
                "weekdays", "weekday" -> days += allDays.take(5)
                "weekends", "weekend" -> days += listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
                "daily", "everyday", "all" -> days += allDays
                else -> days += allDays.firstOrNull { it.name.lowercase(Locale.ROOT).startsWith(token.take(3)) }
                    ?: return null
            }
        }
        return days.sorted()
    }

    private fun DayOfWeek.toCalendarDay(): Int = when (this) {
        DayOfWeek.MONDAY -> Calendar.MONDAY
        DayOfWeek.TUESDAY -> Calendar.TUESDAY
        DayOfWeek.WEDNESDAY -> Calendar.WEDNESDAY
        DayOfWeek.THURSDAY -> Calendar.THURSDAY
        DayOfWeek.FRIDAY -> Calendar.FRIDAY
        DayOfWeek.SATURDAY -> Calendar.SATURDAY
        DayOfWeek.SUNDAY -> Calendar.SUNDAY
    }

    /** "today at 7:00 AM" or "tomorrow at 7:00 AM", matching how the Clock app schedules it. */
    private fun describeNextOccurrence(time: LocalTime): String {
        val now = ZonedDateTime.now()
        val date = if (time.isAfter(now.toLocalTime())) now.toLocalDate() else now.toLocalDate().plusDays(1)
        return "${dayWord(date)} at ${time.format(TIME_FORMAT)}"
    }

    private fun dayWord(date: LocalDate): String {
        val today = LocalDate.now()
        return when (date) {
            today -> "today"
            today.plusDays(1) -> "tomorrow"
            else -> date.format(DAY_FORMAT)
        }
    }

    private fun describeDuration(totalSeconds: Int): String {
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return listOfNotNull(
            hours.takeIf { it > 0 }?.let { "$it hour${if (it > 1) "s" else ""}" },
            minutes.takeIf { it > 0 }?.let { "$it minute${if (it > 1) "s" else ""}" },
            seconds.takeIf { it > 0 }?.let { "$it second${if (it > 1) "s" else ""}" }
        ).joinToString(" ")
    }

    private fun failure(message: String): Map<String, String> =
        mapOf("status" to "error", "error" to message)

    private companion object {
        const val DEFAULT_LABEL = "SEEMA"
        const val MAX_TIMER_SECONDS = 24 * 60 * 60

        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
        val TIME_24H_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
        val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
    }
}
