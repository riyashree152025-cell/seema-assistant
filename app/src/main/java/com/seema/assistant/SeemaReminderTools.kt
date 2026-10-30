package com.seema.assistant

import android.content.Context
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Spoken reminders backed by [SeemaReminders]. KSP generates `generatedTools()`. */
class SeemaReminderTools(context: Context) {

    private val appContext = context.applicationContext

    @Tool(
        description = "Sets a reminder that SEEMA speaks aloud and shows as a notification. Give either " +
            "inMinutes (for 'in 20 minutes'), or hour and minute (for 'at 6 PM'), optionally with dayOffset. " +
            "For repeating reminders ('every day at 9 PM') give hour, minute and repeatDays."
    )
    fun setReminder(
        @Param("What to remind about, e.g. 'Call Papa'") message: String,
        @Param("Hour in 24-hour format, 0 to 23 (6 PM = 18)") hour: Int? = null,
        @Param("Minute, 0 to 59") minute: Int? = null,
        @Param("Days from today: 0 today, 1 tomorrow. Omit to pick the next time this clock time occurs.") dayOffset: Int? = null,
        @Param("Minutes from now, for relative reminders like 'in 20 minutes'") inMinutes: Int? = null,
        @Param(
            "Optional repeat: 'daily', 'weekdays', 'weekends' or comma-separated days like 'mon,wed,fri'. " +
                "Needs hour and minute."
        ) repeatDays: String? = null
    ): Map<String, String> {
        if (message.isBlank()) return failure("What should I remind you about?")
        val now = ZonedDateTime.now()

        val repeat = repeatDays?.takeIf { it.isNotBlank() }?.let {
            parseRepeatDays(it) ?: return failure("Could not understand the days '$it'.")
        }.orEmpty()
        if (repeat.isNotEmpty()) {
            if (hour == null) return failure("Repeating reminders need a time of day, like 9 PM.")
            val min = minute ?: 0
            if (hour !in 0..23 || min !in 0..59) return failure("Invalid time $hour:$min.")
            val first = nextOccurrence(now, LocalTime.of(hour, min), repeat)
            val (reminder, exact) = SeemaReminders.add(appContext, message, first.toInstant().toEpochMilli(), repeat)
            return buildMap {
                put("status", "ok")
                put("reminder", reminder.message)
                put("when", describeReminder(reminder))
                if (!exact) put("note", "Android only allowed an approximate alarm; it may be a few minutes late.")
            }
        }

        val triggerAt = when {
            inMinutes != null -> {
                if (inMinutes !in 1..MAX_MINUTES) return failure("Reminders can be 1 minute to 30 days away.")
                now.plusMinutes(inMinutes.toLong())
            }
            hour != null -> {
                val min = minute ?: 0
                if (hour !in 0..23 || min !in 0..59) return failure("Invalid time $hour:$min.")
                val time = LocalTime.of(hour, min)
                val date = when {
                    dayOffset != null -> now.toLocalDate().plusDays(dayOffset.toLong())
                    time.isAfter(now.toLocalTime()) -> now.toLocalDate()
                    else -> now.toLocalDate().plusDays(1)
                }
                ZonedDateTime.of(date, time, now.zone)
            }
            else -> return failure("When should I remind you? Give a time or a number of minutes.")
        }
        if (!triggerAt.isAfter(now)) return failure("That time has already passed today.")

        val (reminder, exact) = SeemaReminders.add(appContext, message, triggerAt.toInstant().toEpochMilli())
        return buildMap {
            put("status", "ok")
            put("reminder", reminder.message)
            put("when", describe(reminder.triggerAt))
            if (!exact) put("note", "Android only allowed an approximate alarm; it may be a few minutes late.")
        }
    }

    @Tool(description = "Lists upcoming reminders, soonest first.")
    fun listReminders(): Map<String, String> {
        val reminders = SeemaReminders.all()
        if (reminders.isEmpty()) return mapOf("status" to "ok", "reminders" to "none")
        return mapOf(
            "status" to "ok",
            "count" to reminders.size.toString(),
            "reminders" to reminders.joinToString("; ") { "${it.message} (${describeReminder(it)})" }
        )
    }

    @Tool(
        description = "Cancels a reminder matching the description. If several match, returns them " +
            "so the user can choose."
    )
    fun cancelReminder(
        @Param("Words from the reminder, e.g. 'call Papa'") description: String
    ): Map<String, String> {
        val wanted = SeemaMemory.normalize(description).split(' ').filter { it.length >= 3 }
        if (wanted.isEmpty()) return failure("Which reminder should I cancel?")
        val matches = SeemaReminders.all().filter { reminder ->
            val text = SeemaMemory.normalize(reminder.message)
            wanted.all { text.contains(it) }
        }
        return when {
            matches.isEmpty() -> failure("No reminder matches '$description'.")
            matches.size > 1 -> failure(
                "Several reminders match: ${matches.joinToString("; ") { "${it.message} (${describeReminder(it)})" }}. Ask which one."
            )
            else -> {
                SeemaReminders.remove(appContext, matches.single())
                mapOf("status" to "ok", "cancelled" to matches.single().message)
            }
        }
    }

    private fun describe(epochMillis: Long): String = describeReminderTime(epochMillis)

    private fun failure(message: String): Map<String, String> = mapOf("status" to "error", "error" to message)

    private companion object {
        const val MAX_MINUTES = 30 * 24 * 60
    }
}

/** "every day at 9:00 PM" for repeating reminders, otherwise the one-off time. */
fun describeReminder(reminder: Reminder): String {
    if (!reminder.isRepeating) return describeReminderTime(reminder.triggerAt)
    val dayText = describeDays(reminder.repeatDays)
    val time = Instant.ofEpochMilli(reminder.triggerAt).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
    return "$dayText at $time, next ${describeReminderTime(reminder.triggerAt)}"
}

/** "every day", "every weekday", "every weekend" or "every Monday, Friday". */
fun describeDays(days: Set<DayOfWeek>): String = when {
    days.size == 7 -> "every day"
    days == WEEKDAYS -> "every weekday"
    days == WEEKEND -> "every weekend"
    else -> "every " + days.sorted().joinToString(", ") {
        it.name.lowercase(Locale.ROOT).replaceFirstChar { c -> c.uppercase() }
    }
}

private val WEEKDAYS = DayOfWeek.values().take(5).toSet()
private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

/** Parses "daily", "weekdays", "weekends" or "mon,wed,fri"; null if any part is unknown. */
fun parseRepeatDays(raw: String): Set<DayOfWeek>? {
    val days = mutableSetOf<DayOfWeek>()
    val tokens = raw.lowercase(Locale.ROOT).split(',', ' ').filter { it.isNotBlank() && it != "and" }
    for (token in tokens) {
        when (token) {
            "daily", "everyday", "all" -> days += DayOfWeek.values()
            "weekdays", "weekday" -> days += WEEKDAYS
            "weekends", "weekend" -> days += WEEKEND
            else -> days += DayOfWeek.values().firstOrNull {
                it.name.lowercase(Locale.ROOT).startsWith(token.take(3))
            } ?: return null
        }
    }
    return days.takeIf { it.isNotEmpty() }
}

/** "today at 6:00 PM", "tomorrow at 7:30 AM" or "Monday 3 November at 9:00 AM". */
fun describeReminderTime(epochMillis: Long): String {
    val time = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val day = when (time.toLocalDate()) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> time.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH))
    }
    return "$day at ${time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))}"
}
