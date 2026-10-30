package com.seema.assistant

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Reads notifications collected by [SeemaNotificationListener]. KSP generates `generatedTools()`. */
class SeemaNotificationTools(context: Context) {

    private val appContext = context.applicationContext

    @Tool(
        description = "Reads the phone's current notifications, newest first: WhatsApp and other chat " +
            "messages, emails, app alerts. Optionally only from one app."
    )
    fun readNotifications(
        @Param("Optional app name, e.g. 'WhatsApp', 'Gmail', 'Instagram'") appName: String? = null,
        @Param("Maximum number to read, 1 to 10. Omit for 5.") count: Int? = null
    ): Map<String, String> {
        val items = SeemaNotificationListener.activeItems()
            ?: return mapOf(
                "status" to "error",
                "error" to "Notification access is off. Tell the user to enable SEEMA under " +
                    "Settings, Notification access."
            )
        val limit = (count ?: DEFAULT_COUNT).coerceIn(1, MAX_COUNT)
        val wanted = appName?.let(::normalize)?.takeIf { it.isNotEmpty() }
        val matching = items.filter { item ->
            val app = normalize(item.app)
            wanted == null || app.contains(wanted) || wanted.contains(app)
        }

        if (matching.isEmpty()) {
            return mapOf(
                "status" to "ok",
                "count" to "0",
                "notifications" to if (appName.isNullOrBlank()) "No notifications." else "No notifications from $appName."
            )
        }
        val lines = matching.take(limit).mapIndexed { i, item ->
            val title = item.title.ifBlank { item.app }
            "${i + 1}. ${item.app}, $title (${describeTime(item.postTime)}): ${item.text.take(MAX_TEXT_CHARS)}"
        }
        return mapOf(
            "status" to "ok",
            "count" to lines.size.toString(),
            "total_available" to matching.size.toString(),
            "notifications" to lines.joinToString("; ")
        )
    }

    @Tool(
        description = "Replies to a WhatsApp (or other chat app) conversation through its notification's " +
            "reply button. Only works for chats that currently have a notification. Only call this " +
            "after the user has confirmed the recipient and the exact message."
    )
    fun replyToMessage(
        @Param("Contact or group name as shown in the chat, e.g. 'Rahul', 'Family'") contactName: String,
        @Param("The exact reply text") message: String,
        @Param("Optional app name, e.g. 'WhatsApp'. Omit to search all chat apps.") appName: String? = null
    ): Map<String, String> {
        val targets = SeemaNotificationListener.replyTargets()
            ?: return failure("Notification access is off. Tell the user to enable SEEMA under Settings, Notification access.")
        val text = message.trim()
        if (text.isEmpty()) return failure("The reply is empty.")

        val wantedApp = appName?.let(::normalize)?.takeIf { it.isNotEmpty() }
        val wantedChat = normalize(contactName)
        if (wantedChat.isEmpty()) return failure("No contact name given.")
        val inApp = targets.filter { wantedApp == null || normalize(it.app).contains(wantedApp) }
        val exact = inApp.filter { normalize(it.chat) == wantedChat }
        val matches = exact.ifEmpty {
            inApp.filter {
                val chat = normalize(it.chat)
                chat.isNotEmpty() && (chat.contains(wantedChat) || wantedChat.contains(chat))
            }
        }

        val target = when {
            matches.isEmpty() -> return failure(
                "No open chat notification from '$contactName'. Replies only work while the chat's " +
                    "notification is showing; otherwise suggest sending an SMS instead."
            )
            matches.size > 1 -> return failure(
                "Several chats match: ${matches.joinToString(", ") { "${it.chat} (${it.app})" }}. Ask which one."
            )
            else -> matches.single()
        }

        val remoteInputs = target.action.remoteInputs
        val results = Bundle().apply { remoteInputs.forEach { putCharSequence(it.resultKey, text) } }
        val fillIn = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(remoteInputs, fillIn, results)

        return try {
            target.action.actionIntent.send(appContext, 0, fillIn)
            mapOf("status" to "ok", "replied_to" to target.chat, "app" to target.app)
        } catch (e: PendingIntent.CanceledException) {
            failure("That notification has expired. Ask the user to try again after a new message arrives.")
        }
    }

    private fun failure(message: String): Map<String, String> = mapOf("status" to "error", "error" to message)

    private fun normalize(name: String): String = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun describeTime(epochMillis: Long): String {
        val time = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
        val prefix = if (time.toLocalDate() == LocalDate.now()) "" else "${time.format(DAY_FORMAT)} "
        return prefix + time.format(TIME_FORMAT)
    }

    private companion object {
        const val DEFAULT_COUNT = 5
        const val MAX_COUNT = 10
        const val MAX_TEXT_CHARS = 200

        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    }
}
