package com.seema.assistant

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.AtomicFile
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileNotFoundException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One scheduled reminder; [repeatDays] non-empty means it repeats at the same time on those days. */
data class Reminder(
    val id: String,
    val message: String,
    val triggerAt: Long,
    val repeatDays: Set<DayOfWeek> = emptySet()
) {
    val isRepeating: Boolean get() = repeatDays.isNotEmpty()
}

/** First moment strictly after [after] at [time] on one of [days]. */
fun nextOccurrence(after: ZonedDateTime, time: LocalTime, days: Set<DayOfWeek>): ZonedDateTime =
    (0L..7L).asSequence()
        .map { after.toLocalDate().plusDays(it) }
        .filter { it.dayOfWeek in days }
        .map { ZonedDateTime.of(it, time, after.zone) }
        .first { it.isAfter(after) }

/**
 * Persistent reminders: stored as JSON (like [SeemaMemory]) and scheduled with AlarmManager.
 * When one fires, [ReminderReceiver] posts a notification and SEEMA speaks it if she is running.
 */
object SeemaReminders {

    private const val TAG = "SeemaReminders"
    private const val FILE_NAME = "seema_reminders.json"
    const val CHANNEL_ID = "seema_reminders"
    const val EXTRA_REMINDER_ID = "reminder_id"

    private lateinit var file: AtomicFile
    private val reminders = mutableListOf<Reminder>()

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))
        reminders += load()
    }

    /** Pending reminders, soonest first. */
    @Synchronized
    fun all(): List<Reminder> = reminders.sortedBy { it.triggerAt }

    @Synchronized
    fun get(id: String): Reminder? = reminders.firstOrNull { it.id == id }

    /** Saves and schedules a reminder. Returns it and whether the alarm is exact. */
    fun add(
        context: Context,
        message: String,
        triggerAt: Long,
        repeatDays: Set<DayOfWeek> = emptySet()
    ): Pair<Reminder, Boolean> {
        val reminder = Reminder(UUID.randomUUID().toString(), message.trim(), triggerAt, repeatDays)
        synchronized(this) {
            reminders += reminder
            save()
        }
        return reminder to schedule(context, reminder)
    }

    fun remove(context: Context, reminder: Reminder) {
        synchronized(this) {
            reminders.removeAll { it.id == reminder.id }
            save()
        }
        alarmManager(context).cancel(pendingIntent(context, reminder))
    }

    /** Moves a repeating reminder to its next occurrence (same clock time) and schedules it. */
    fun advance(context: Context, reminder: Reminder) {
        val zone = ZoneId.systemDefault()
        val time = Instant.ofEpochMilli(reminder.triggerAt).atZone(zone).toLocalTime()
        val next = nextOccurrence(ZonedDateTime.now(zone), time, reminder.repeatDays)
        val updated = reminder.copy(triggerAt = next.toInstant().toEpochMilli())
        synchronized(this) {
            reminders.replaceAll { if (it.id == reminder.id) updated else it }
            save()
        }
        schedule(context, updated)
    }

    /** Alarms do not survive a reboot; re-register them. Overdue ones fire right away. */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        all().forEach { reminder ->
            schedule(context, if (reminder.triggerAt > now) reminder else reminder.copy(triggerAt = now + OVERDUE_DELAY_MS))
        }
        Log.i(TAG, "Rescheduled ${all().size} reminders")
    }

    /** Schedules the alarm; returns false if Android only allowed an inexact one. */
    private fun schedule(context: Context, reminder: Reminder): Boolean {
        val alarmManager = alarmManager(context)
        val operation = pendingIntent(context, reminder)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (exactAllowed) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAt, operation)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAt, operation)
        }
        return exactAllowed
    }

    private fun pendingIntent(context: Context, reminder: Reminder): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            reminder.id.hashCode(),
            Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_REMINDER_ID, reminder.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun alarmManager(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun load(): List<Reminder> = try {
        val array = JSONArray(String(file.readFully(), Charsets.UTF_8))
        (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val repeat = item.optJSONArray("repeat")
            Reminder(
                item.getString("id"),
                item.getString("message"),
                item.getLong("triggerAt"),
                if (repeat == null) emptySet() else (0 until repeat.length()).map { DayOfWeek.of(repeat.getInt(it)) }.toSet()
            )
        }
    } catch (e: FileNotFoundException) {
        emptyList()
    } catch (e: JSONException) {
        Log.e(TAG, "Reminder file is corrupt; starting empty", e)
        emptyList()
    }

    private fun save() {
        val array = JSONArray()
        reminders.forEach {
            val item = JSONObject().put("id", it.id).put("message", it.message).put("triggerAt", it.triggerAt)
            if (it.isRepeating) item.put("repeat", JSONArray(it.repeatDays.map { day -> day.value }.sorted()))
            array.put(item)
        }
        val stream = file.startWrite()
        try {
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            Log.e(TAG, "Could not save reminders", e)
        }
    }

    private const val OVERDUE_DELAY_MS = 10_000L
}

/** Fires a reminder: notification (always) + spoken by SEEMA (when her service is running). */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        SeemaReminders.init(context)
        val id = intent.getStringExtra(SeemaReminders.EXTRA_REMINDER_ID) ?: return
        val reminder = SeemaReminders.get(id) ?: return
        if (reminder.isRepeating) SeemaReminders.advance(context, reminder) else SeemaReminders.remove(context, reminder)

        showReminderNotification(context, reminder.id.hashCode(), "⏰ Reminder", reminder.message)
        SeemaState.announce("Reminder: ${reminder.message}")
        Log.i("SeemaReminders", "⏰ ${reminder.message}")
    }
}

/** High-priority reminder notification, shared by time and location reminders. */
fun showReminderNotification(context: Context, notificationId: Int, title: String, message: String) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(SeemaReminders.CHANNEL_ID, "SEEMA Reminders", NotificationManager.IMPORTANCE_HIGH)
    )
    val notification = NotificationCompat.Builder(context, SeemaReminders.CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setContentTitle(title)
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(
            PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()
    manager.notify(notificationId, notification)
}

/** Re-registers reminder alarms and location geofences after the phone restarts. */
class ReminderBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        SeemaReminders.init(context)
        SeemaReminders.rescheduleAll(context)
        SeemaPlaces.init(context)
        SeemaGeofences.registerAll(context)
    }
}
