package com.seema.assistant

import android.content.Context
import androidx.work.ExistingWorkPolicy
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Voice control for the WorkManager-based proactive features. KSP generates `generatedTools()`. */
class SeemaProactiveTools(context: Context) {

    private val appContext = context.applicationContext
    private val settings = ProactiveSettings(appContext)

    @Tool(description = "Turns the automatic spoken daily briefing on or off, and optionally sets its time.")
    fun setDailyBriefing(
        @Param("true to turn it on, false to turn it off") enabled: Boolean,
        @Param("Optional hour in 24-hour format, 0 to 23") hour: Int? = null,
        @Param("Optional minute, 0 to 59") minute: Int? = null
    ): Map<String, String> {
        if (hour != null && hour !in 0..23) return failure("Hour must be 0 to 23.")
        if (minute != null && minute !in 0..59) return failure("Minute must be 0 to 59.")
        settings.briefingEnabled = enabled
        hour?.let { settings.briefingHour = it }
        if (hour != null || minute != null) settings.briefingMinute = minute ?: 0
        ProactiveScheduler.scheduleBriefing(appContext, ExistingWorkPolicy.REPLACE)
        return mapOf("status" to "ok", "daily_briefing" to describeBriefing())
    }

    @Tool(description = "Turns automatic rain alerts on or off (alerts when rain starts or is about to start).")
    fun setRainAlerts(@Param("true to turn them on, false to turn them off") enabled: Boolean): Map<String, String> {
        settings.rainAlertsEnabled = enabled
        ProactiveScheduler.scheduleRainAlerts(appContext)
        return mapOf("status" to "ok", "rain_alerts" to if (enabled) "on, 7 AM to 10 PM" else "off")
    }

    @Tool(description = "Returns the current settings of the automatic daily briefing and rain alerts.")
    fun getProactiveSettings(): Map<String, String> = mapOf(
        "status" to "ok",
        "daily_briefing" to describeBriefing(),
        "rain_alerts" to if (settings.rainAlertsEnabled) "on, 7 AM to 10 PM" else "off"
    )

    private fun describeBriefing(): String =
        if (!settings.briefingEnabled) {
            "off"
        } else {
            "on, every day at ${LocalTime.of(settings.briefingHour, settings.briefingMinute).format(TIME_FORMAT)}"
        }

    private fun failure(message: String): Map<String, String> = mapOf("status" to "error", "error" to message)

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    }
}
