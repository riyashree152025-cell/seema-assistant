package com.seema.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.google.adk.kt.annotations.Tool
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One-call daily briefing built from the other tools and phone data.
 * KSP generates `SeemaBriefingTools.generatedTools()`.
 */
class SeemaBriefingTools(
    context: Context,
    private val weatherTools: SeemaWeatherTools,
    private val clockTools: SeemaClockTools
) {

    private val appContext = context.applicationContext

    @Tool(
        description = "Collects a daily briefing: date, weather, next alarm, unread chat messages, " +
            "unread SMS, missed calls and battery. Use for 'good morning', 'daily briefing', " +
            "'what's my day like'."
    )
    fun getDailyBriefing(): Map<String, String> = buildMap {
        put("status", "ok")
        put("part_of_day", partOfDay())
        put("date", LocalDate.now().format(DATE_FORMAT))
        put("weather", weatherSummary())
        put("next_alarm", clockTools.getNextAlarm()["next_alarm"] ?: "none")
        put("unread_chats", chatSummary())
        put("unread_sms", unreadSmsSummary())
        put("missed_calls", missedCallsSummary())
        put(
            "battery_percent",
            appContext.getSystemService(BatteryManager::class.java)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toString()
        )
    }

    private fun partOfDay(): String = when (LocalTime.now().hour) {
        in 4..11 -> "morning"
        in 12..16 -> "afternoon"
        in 17..21 -> "evening"
        else -> "night"
    }

    private fun weatherSummary(): String {
        val weather = weatherTools.getWeather()
        if (weather["status"] != "ok") return "unavailable (${weather["error"]})"
        return weather.filterKeys { it != "status" }.entries.joinToString(", ") { "${it.key}: ${it.value}" }
    }

    /** "WhatsApp: 4 from Rahul, Family; Gmail: 2" from the notification shade. */
    private fun chatSummary(): String {
        val items = SeemaNotificationListener.activeItems() ?: return "unknown (notification access is off)"
        if (items.isEmpty()) return "none"
        return items.groupBy { it.app }.entries.joinToString("; ") { (app, appItems) ->
            val senders = appItems.map { it.title }.filter { it.isNotBlank() }.distinct().take(MAX_NAMES)
            if (senders.isEmpty()) "$app: ${appItems.size}" else "$app: ${appItems.size} from ${senders.joinToString(", ")}"
        }
    }

    private fun unreadSmsSummary(): String {
        if (!hasPermission(Manifest.permission.READ_SMS)) return "unknown (SMS permission missing)"
        val senders = mutableListOf<String>()
        var count = 0
        appContext.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS),
            "${Telephony.Sms.READ} = 0",
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            count = cursor.count
            while (senders.size < MAX_NAMES && cursor.moveToNext()) {
                val address = cursor.getString(0).orEmpty()
                senders += ContactNames.lookup(appContext, address) ?: address
            }
        }
        if (count == 0) return "none"
        return "$count from ${senders.distinct().joinToString(", ")}"
    }

    private fun missedCallsSummary(): String {
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) return "unknown (call log permission missing)"
        val callers = mutableListOf<String>()
        var count = 0
        appContext.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME),
            "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.NEW} = 1",
            arrayOf(CallLog.Calls.MISSED_TYPE.toString()),
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            count = cursor.count
            while (callers.size < MAX_NAMES && cursor.moveToNext()) {
                val number = cursor.getString(0).orEmpty()
                callers += cursor.getString(1)?.takeIf { it.isNotBlank() }
                    ?: ContactNames.lookup(appContext, number)
                    ?: "unknown number"
            }
        }
        if (count == 0) return "none"
        return "$count from ${callers.distinct().joinToString(", ")}"
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val MAX_NAMES = 3
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)
    }
}
