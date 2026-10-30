package com.seema.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** How SEEMA sounds and listens; edited in the in-app Settings dialog, read on every reply. */
class VoiceSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("seema_voice", Context.MODE_PRIVATE)

    /** TTS voice name (e.g. "en-gb-x-rjs-local"); null = automatic by language. */
    var voiceName: String?
        get() = prefs.getString("voice_name", null)
        set(value) = prefs.edit().putString("voice_name", value).apply()

    /** 1.0 is normal; lower is deeper. */
    var pitch: Float
        get() = prefs.getFloat("pitch", 1.0f)
        set(value) = prefs.edit().putFloat("pitch", value.coerceIn(MIN, MAX)).apply()

    /** 1.0 is normal; higher is faster. */
    var speechRate: Float
        get() = prefs.getFloat("speech_rate", 1.0f)
        set(value) = prefs.edit().putFloat("speech_rate", value.coerceIn(MIN, MAX)).apply()

    /** Use on-device speech recognition even when online (faster, private, needs the offline pack). */
    var preferOfflineRecognition: Boolean
        get() = prefs.getBoolean("prefer_offline_stt", false)
        set(value) = prefs.edit().putBoolean("prefer_offline_stt", value).apply()

    companion object {
        const val MIN = 0.5f
        const val MAX = 2.0f

        /** Calm, slightly deep British delivery. */
        const val JARVIS_PITCH = 0.85f
        const val JARVIS_RATE = 1.05f

        /** Google TTS male en-GB voices, best first; the first installed one wins. */
        val JARVIS_VOICE_PREFIXES = listOf("en-gb-x-rjs", "en-gb-x-gbd", "en-gb-x-gbb")
    }
}

/** True when there is a network that actually reaches the internet. */
object NetworkStatus {
    fun isOnline(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
