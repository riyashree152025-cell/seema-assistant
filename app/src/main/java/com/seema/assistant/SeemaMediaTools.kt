package com.seema.assistant

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import java.util.Locale

/**
 * Music control for any media app through Android's MediaSession API.
 * Seeing other apps' sessions requires notification access (already granted for notifications).
 * KSP generates `SeemaMediaTools.generatedTools()`.
 */
class SeemaMediaTools(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    @Tool(description = "Controls music or video playing in any app: play, pause, next, previous or stop.")
    fun controlMedia(
        @Param("One of: play, pause, next, previous, stop") action: String
    ): Map<String, String> {
        val command = when (action.trim().lowercase(Locale.ROOT)) {
            "play", "resume", "start" -> Command.PLAY
            "pause" -> Command.PAUSE
            "next", "skip", "next song" -> Command.NEXT
            "previous", "prev", "back", "previous song" -> Command.PREVIOUS
            "stop" -> Command.STOP
            else -> return failure("Unknown action '$action'. Use play, pause, next, previous or stop.")
        }
        // Playback should start once SEEMA releases the speaker, so skip follow-up listening.
        if (command.startsPlayback) SeemaState.requestConversationEnd()

        val controller = activeController()
        if (controller == null) {
            // No visible session: a media key still reaches the app that played audio last.
            sendMediaKey(command.keyCode)
            return mapOf(
                "status" to "ok",
                "action" to command.name.lowercase(Locale.ROOT),
                "app" to "last used media app"
            )
        }
        val controls = controller.transportControls
        when (command) {
            Command.PLAY -> controls.play()
            Command.PAUSE -> controls.pause()
            Command.NEXT -> controls.skipToNext()
            Command.PREVIOUS -> controls.skipToPrevious()
            Command.STOP -> controls.stop()
        }
        return mapOf(
            "status" to "ok",
            "action" to command.name.lowercase(Locale.ROOT),
            "app" to appLabel(controller.packageName)
        )
    }

    @Tool(description = "Returns what is playing: song title, artist, album, app, and playing or paused.")
    fun getNowPlaying(): Map<String, String> {
        val controller = activeController() ?: return mapOf("status" to "ok", "playing" to "nothing")
        val metadata = controller.metadata
        return buildMap {
            put("status", "ok")
            put("app", appLabel(controller.packageName))
            put("state", describeState(controller.playbackState?.state))
            metadata?.text(MediaMetadata.METADATA_KEY_TITLE)?.let { put("title", it) }
            (metadata?.text(MediaMetadata.METADATA_KEY_ARTIST) ?: metadata?.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))
                ?.let { put("artist", it) }
            metadata?.text(MediaMetadata.METADATA_KEY_ALBUM)?.let { put("album", it) }
        }
    }

    @Tool(
        description = "Searches for and plays music in a music app, e.g. 'Arijit Singh songs' on " +
            "Spotify or YouTube Music."
    )
    fun playMusic(
        @Param("What to play: a song, artist, album or genre") query: String,
        @Param("Optional app name, e.g. 'Spotify', 'YouTube Music'. Omit to use the usual music app.") appName: String? = null
    ): Map<String, String> {
        if (query.isBlank()) return failure("Nothing to search for.")
        // Android 10+ blocks activity launches from background services unless this is granted.
        if (!Settings.canDrawOverlays(appContext)) {
            return failure("Permission missing. Tell the user to enable 'Display over other apps' for SEEMA in Settings.")
        }
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, query.trim())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val handlers = resolveActivities(intent)
        if (handlers.isEmpty()) {
            return failure("No installed music app supports voice search. Spotify or YouTube Music do.")
        }
        val chosen = if (appName.isNullOrBlank()) {
            // Prefer the app that played last, so "play some music" stays in the user's usual app.
            val recent = activeController()?.packageName
            handlers.firstOrNull { it.activityInfo.packageName == recent } ?: handlers.first()
        } else {
            val wanted = normalize(appName)
            handlers.firstOrNull { normalize(label(it)).contains(wanted) || wanted.contains(normalize(label(it))) }
                ?: return failure("$appName cannot play by search. Apps that can: ${handlers.joinToString { label(it) }}.")
        }

        intent.setPackage(chosen.activityInfo.packageName)
        appContext.startActivity(intent)
        SeemaState.requestConversationEnd()
        return mapOf("status" to "ok", "searching" to query.trim(), "app" to label(chosen))
    }

    // --------------------------------------------------------------- helpers

    private enum class Command(val keyCode: Int, val startsPlayback: Boolean) {
        PLAY(KeyEvent.KEYCODE_MEDIA_PLAY, true),
        PAUSE(KeyEvent.KEYCODE_MEDIA_PAUSE, false),
        NEXT(KeyEvent.KEYCODE_MEDIA_NEXT, true),
        PREVIOUS(KeyEvent.KEYCODE_MEDIA_PREVIOUS, true),
        STOP(KeyEvent.KEYCODE_MEDIA_STOP, false)
    }

    /** The playing session if any, else the most recent one; null without notification access. */
    private fun activeController(): MediaController? {
        val sessions = try {
            appContext.getSystemService(MediaSessionManager::class.java)
                .getActiveSessions(ComponentName(appContext, SeemaNotificationListener::class.java))
        } catch (e: SecurityException) {
            return null
        }
        return sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
    }

    private fun sendMediaKey(keyCode: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun describeState(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> "loading"
        PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE -> "stopped"
        else -> "unknown"
    }

    private fun MediaMetadata.text(key: String): String? = getString(key)?.takeIf { it.isNotBlank() }

    private fun resolveActivities(intent: Intent): List<ResolveInfo> {
        val packageManager = appContext.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
    }

    private fun label(info: ResolveInfo): String = info.loadLabel(appContext.packageManager).toString()

    private fun appLabel(packageName: String): String = runCatching {
        val packageManager = appContext.packageManager
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun normalize(name: String): String = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun failure(message: String): Map<String, String> = mapOf("status" to "error", "error" to message)
}
