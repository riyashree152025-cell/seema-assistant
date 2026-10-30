package com.seema.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** User choices for the proactive features, kept in SharedPreferences. */
class ProactiveSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("seema_proactive", Context.MODE_PRIVATE)

    var briefingEnabled: Boolean
        get() = prefs.getBoolean("briefing_enabled", true)
        set(value) = prefs.edit().putBoolean("briefing_enabled", value).apply()

    var briefingHour: Int
        get() = prefs.getInt("briefing_hour", 8)
        set(value) = prefs.edit().putInt("briefing_hour", value).apply()

    var briefingMinute: Int
        get() = prefs.getInt("briefing_minute", 0)
        set(value) = prefs.edit().putInt("briefing_minute", value).apply()

    var rainAlertsEnabled: Boolean
        get() = prefs.getBoolean("rain_alerts_enabled", true)
        set(value) = prefs.edit().putBoolean("rain_alerts_enabled", value).apply()

    var lastRainAlertAt: Long
        get() = prefs.getLong("last_rain_alert_at", 0L)
        set(value) = prefs.edit().putLong("last_rain_alert_at", value).apply()
}

/**
 * WorkManager setup. The daily briefing is a one-time job that re-schedules itself for the next
 * day, so it stays pinned to the chosen clock time (a 24 h periodic job would drift).
 * Rain alerts are a periodic hourly job.
 */
object ProactiveScheduler {

    private const val BRIEFING_WORK = "seema_daily_briefing"
    private const val RAIN_WORK = "seema_rain_alerts"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Called whenever SEEMA starts. KEEP leaves already-queued jobs untouched. */
    fun ensureScheduled(context: Context) {
        scheduleBriefing(context, ExistingWorkPolicy.KEEP)
        scheduleRainAlerts(context)
    }

    /**
     * Queues the next briefing at the configured time. Use REPLACE after the user changes the
     * time, and APPEND_OR_REPLACE from inside the running briefing worker.
     */
    fun scheduleBriefing(context: Context, policy: ExistingWorkPolicy) {
        val settings = ProactiveSettings(context)
        val workManager = WorkManager.getInstance(context)
        if (!settings.briefingEnabled) {
            workManager.cancelUniqueWork(BRIEFING_WORK)
            return
        }
        val request = OneTimeWorkRequestBuilder<DailyBriefingWorker>()
            .setInitialDelay(millisUntil(settings.briefingHour, settings.briefingMinute), TimeUnit.MILLISECONDS)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(BRIEFING_WORK, policy, request)
    }

    fun scheduleRainAlerts(context: Context) {
        val workManager = WorkManager.getInstance(context)
        if (!ProactiveSettings(context).rainAlertsEnabled) {
            workManager.cancelUniqueWork(RAIN_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<RainAlertWorker>(1, TimeUnit.HOURS)
            .setConstraints(networkConstraint)
            .build()
        workManager.enqueueUniquePeriodicWork(RAIN_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun millisUntil(hour: Int, minute: Int): Long {
        val now = ZonedDateTime.now()
        var next = now.with(LocalTime.of(hour, minute)).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next).toMillis()
    }
}

/** Builds the briefing with the agent, then shows it and speaks it. Re-schedules itself. */
class DailyBriefingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!ProactiveSettings(context).briefingEnabled) return Result.success()

        SeemaAgent.init(context)
        val briefing = SeemaAgent.runAgent(BRIEFING_REQUEST)
        val failed = briefing.startsWith("ERROR:")
        if (failed && runAttemptCount < MAX_ATTEMPTS) return Result.retry()

        if (failed) {
            Log.w(TAG, "Briefing failed: $briefing")
        } else {
            showProactiveNotification(context, BRIEFING_NOTIFICATION_ID, "☀️ Daily briefing", briefing)
            SeemaState.announce(briefing)
            Log.i(TAG, "☀️ Daily briefing delivered")
        }
        ProactiveScheduler.scheduleBriefing(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    private companion object {
        const val TAG = "SeemaProactive"
        const val MAX_ATTEMPTS = 3
        const val BRIEFING_NOTIFICATION_ID = 7001
        const val BRIEFING_REQUEST =
            "This is your scheduled automatic briefing; the user did not speak. " +
                "Call getDailyBriefing and give the briefing."
    }
}

/** Hourly: alerts once when it starts raining or rain is likely within two hours. */
class RainAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val settings = ProactiveSettings(context)
        if (!settings.rainAlertsEnabled) return Result.success()

        val hour = LocalTime.now().hour
        if (hour < QUIET_END_HOUR || hour >= QUIET_START_HOUR) return Result.success()
        if (System.currentTimeMillis() - settings.lastRainAlertAt < MIN_GAP_MS) return Result.success()

        val alert = withContext(Dispatchers.IO) { SeemaWeatherTools(context).rainAlert() }
            ?: return Result.success()
        settings.lastRainAlertAt = System.currentTimeMillis()
        showProactiveNotification(context, RAIN_NOTIFICATION_ID, "🌧 Rain alert", alert)
        SeemaState.announce(alert)
        Log.i(TAG, "🌧 $alert")
        return Result.success()
    }

    private companion object {
        const val TAG = "SeemaProactive"
        const val RAIN_NOTIFICATION_ID = 7002
        /** No rain alerts between 10 PM and 7 AM. */
        const val QUIET_START_HOUR = 22
        const val QUIET_END_HOUR = 7
        /** At most one rain alert every 4 hours. */
        const val MIN_GAP_MS = 4 * 60 * 60 * 1000L
    }
}

/**
 * Scheduled routines: one unique one-time job per routine, re-queued for its next day after each
 * run (like the daily briefing), so it stays at the chosen clock time.
 */
object RoutineScheduler {

    const val KEY_ROUTINE = "routine"
    const val KEY_REASON = "reason"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Called whenever SEEMA starts; KEEP leaves queued runs untouched. */
    fun ensureScheduled(context: Context) {
        SeemaRoutines.init(context)
        SeemaRoutines.all().filter { it.schedule != null }
            .forEach { schedule(context, it, ExistingWorkPolicy.KEEP) }
    }

    fun schedule(context: Context, routine: Routine, policy: ExistingWorkPolicy) {
        val schedule = routine.schedule ?: return cancel(context, routine.name)
        val now = ZonedDateTime.now()
        val next = nextOccurrence(now, LocalTime.of(schedule.hour, schedule.minute), schedule.days)
        val request = OneTimeWorkRequestBuilder<RoutineWorker>()
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_ROUTINE to routine.name))
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(routine.name), policy, request)
    }

    fun cancel(context: Context, routineName: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(routineName))
    }

    /**
     * Runs a routine once, as soon as possible, because of an automation trigger ([reason] is
     * spoken context like "the user left home"). KEEP drops a duplicate while one is pending.
     */
    fun runNow(context: Context, routineName: String, reason: String) {
        val request = OneTimeWorkRequestBuilder<RoutineWorker>()
            .setInputData(workDataOf(KEY_ROUTINE to routineName, KEY_REASON to reason))
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("seema_trigger_" + workName(routineName), ExistingWorkPolicy.KEEP, request)
    }

    private fun workName(routineName: String) =
        "seema_routine_" + SeemaMemory.normalize(routineName).replace(' ', '_')
}

/**
 * Runs a routine through the agent, shows and speaks the result. Scheduled runs queue their next
 * run; triggered runs (with a reason, from automations) run once.
 */
class RoutineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val name = inputData.getString(RoutineScheduler.KEY_ROUTINE) ?: return Result.failure()
        val reason = inputData.getString(RoutineScheduler.KEY_REASON)
        SeemaAgent.init(context)
        // Deleted, or (for scheduled runs) unscheduled since this run was queued: stop here.
        val routine = SeemaRoutines.find(name)?.takeIf { reason != null || it.schedule != null }
            ?: return Result.success()

        val why = if (reason != null) "was triggered automatically because $reason" else "is running on its schedule"
        val reply = SeemaAgent.runAgent(
            "The '${routine.name}' routine $why; the user did not speak. " +
                "Call runRoutine with '${routine.name}' and perform its steps."
        )
        val failed = reply.startsWith("ERROR:")
        if (failed && runAttemptCount < MAX_ATTEMPTS) return Result.retry()

        if (failed) {
            Log.w(TAG, "Routine '${routine.name}' failed: $reply")
        } else {
            showProactiveNotification(context, ROUTINE_NOTIFICATION_BASE + Math.floorMod(routine.name.hashCode(), 100), "🔁 ${routine.name.replaceFirstChar { it.uppercase() }}", reply)
            SeemaState.announce(reply)
            Log.i(TAG, "🔁 Ran routine '${routine.name}' (${reason ?: "scheduled"})")
        }
        if (reason == null) RoutineScheduler.schedule(context, routine, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    private companion object {
        const val TAG = "SeemaProactive"
        const val MAX_ATTEMPTS = 3
        const val ROUTINE_NOTIFICATION_BASE = 7100
    }
}

private const val PROACTIVE_CHANNEL_ID = "seema_proactive"

fun showProactiveNotification(context: Context, notificationId: Int, title: String, text: String) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(PROACTIVE_CHANNEL_ID, "SEEMA Briefings & Alerts", NotificationManager.IMPORTANCE_DEFAULT)
    )
    val notification = NotificationCompat.Builder(context, PROACTIVE_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setAutoCancel(true)
        .setContentIntent(
            PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        )
        .build()
    manager.notify(notificationId, notification)
}
