package com.seema.assistant

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** One readable notification line. */
data class NotificationItem(
    val app: String,
    val title: String,
    val text: String,
    val postTime: Long,
    /** True for chat messages (WhatsApp, Telegram, SMS…), false for other notifications. */
    val isChat: Boolean = false
)

/** A chat whose notification offers an inline reply box. */
class ReplyTarget(
    val app: String,
    val chat: String,
    val action: Notification.Action
)

/**
 * Gives SEEMA access to the phone's notifications. The user enables it under
 * Settings → Notification access. Reading is on demand ([activeItems]); new chat messages
 * are announced by sender only ([announcements]), never by content.
 */
class SeemaNotificationListener : NotificationListenerService() {

    /** Notification key → timestamp of the newest message already announced. */
    private val announcedUpTo = ConcurrentHashMap<String, Long>()
    @Volatile private var lastAnnouncementAt = 0L

    override fun onListenerConnected() {
        instance = this
        Log.i(TAG, "Notification access connected")
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        announcedUpTo.remove(sbn.key)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!ANNOUNCE_NEW_MESSAGES || !isReadable(sbn)) return
        // Respect Do Not Disturb.
        if (currentInterruptionFilter != INTERRUPTION_FILTER_ALL) return

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
            ?: return
        val latest = style.messages.lastOrNull() ?: return

        // Chat apps re-post the same notification with every new message; announce only new ones.
        if (latest.timestamp <= (announcedUpTo[sbn.key] ?: 0L)) return
        announcedUpTo[sbn.key] = latest.timestamp

        val now = System.currentTimeMillis()
        if (now - lastAnnouncementAt < ANNOUNCE_COOLDOWN_MS) return
        lastAnnouncementAt = now

        val app = appLabel(sbn.packageName)
        val sender = latest.person?.name?.toString() ?: style.conversationTitle?.toString() ?: "someone"
        val group = style.conversationTitle?.toString()?.takeIf { style.isGroupConversation && it != sender }
        _announcements.tryEmit(
            if (group != null) "New $app message from $sender in $group" else "New $app message from $sender"
        )
    }

    private fun readActive(): List<NotificationItem> =
        runCatching { activeNotifications }.getOrNull().orEmpty()
            .filter(::isReadable)
            .sortedByDescending { it.postTime }
            .flatMap(::toItems)

    private fun toItems(sbn: StatusBarNotification): List<NotificationItem> {
        val app = appLabel(sbn.packageName)

        // Chat apps (WhatsApp, Telegram, Messages…) use MessagingStyle: one line per message.
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
        if (style != null && style.messages.isNotEmpty()) {
            val chat = style.conversationTitle?.toString()
            return style.messages.takeLast(MAX_MESSAGES_PER_CHAT).reversed().map { message ->
                val sender = message.person?.name?.toString()
                val title = when {
                    sender != null && chat != null && style.isGroupConversation -> "$sender in $chat"
                    sender != null -> sender
                    else -> chat ?: app
                }
                NotificationItem(app, title, message.text?.toString().orEmpty(), message.timestamp, isChat = true)
            }
        }

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return emptyList()
        return listOf(NotificationItem(app, title, text, sbn.postTime))
    }

    private fun readReplyTargets(): List<ReplyTarget> =
        runCatching { activeNotifications }.getOrNull().orEmpty()
            .filter(::isReadable)
            .sortedByDescending { it.postTime }
            .mapNotNull { sbn ->
                val action = findReplyAction(sbn.notification) ?: return@mapNotNull null
                val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
                val chat = style?.conversationTitle?.toString()
                    ?: style?.messages?.lastOrNull()?.person?.name?.toString()
                    ?: sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                    ?: return@mapNotNull null
                ReplyTarget(appLabel(sbn.packageName), chat, action)
            }
            .distinctBy { it.app to it.chat }

    /** The notification's free-text reply action (also checks the Wear OS actions apps add). */
    private fun findReplyAction(notification: Notification): Notification.Action? {
        val candidates = notification.actions.orEmpty().toList() +
            Notification.WearableExtender(notification).actions
        val replyable = candidates.filter { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        }
        return replyable.firstOrNull {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY
        } ?: replyable.firstOrNull()
    }

    /** Skips SEEMA itself, ongoing ones (music, navigation, calls) and group summaries. */
    private fun isReadable(sbn: StatusBarNotification): Boolean =
        sbn.packageName != packageName &&
            !sbn.isOngoing &&
            sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0

    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    companion object {
        private const val TAG = "SeemaNotifications"
        private const val MAX_MESSAGES_PER_CHAT = 3

        /** Speak "New WhatsApp message from Rahul" when a chat message arrives. */
        private const val ANNOUNCE_NEW_MESSAGES = true
        /** Minimum gap between two announcements, so a busy group chat does not flood. */
        private const val ANNOUNCE_COOLDOWN_MS = 15_000L

        @Volatile private var instance: SeemaNotificationListener? = null

        private val _announcements = MutableSharedFlow<String>(
            extraBufferCapacity = 4,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        /** Short "new message from …" lines for SeemaVoiceService to speak. */
        val announcements: SharedFlow<String> = _announcements.asSharedFlow()

        /** Current notifications, newest first, or null when notification access is off. */
        fun activeItems(): List<NotificationItem>? = instance?.readActive()

        /** Chats that can be answered from their notification, or null when access is off. */
        fun replyTargets(): List<ReplyTarget>? = instance?.readReplyTargets()

        /**
         * Changes Do Not Disturb through notification access (no separate DND permission needed).
         * [filter] uses the NotificationManager.INTERRUPTION_FILTER_* values. Returns false when
         * notification access is off.
         */
        fun setInterruptionFilter(filter: Int): Boolean {
            val listener = instance ?: return false
            listener.requestInterruptionFilter(filter)
            return true
        }
    }
}
