package com.seema.assistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Announces incoming callers ("Mummy is calling") while the phone rings.
 * Needs READ_PHONE_STATE for ring events and READ_CALL_LOG for the caller's number.
 */
class CallAnnouncer(
    context: Context,
    private val scope: CoroutineScope,
    private val textToSpeech: SeemaTextToSpeech,
    private val canAnnounce: () -> Boolean
) {

    private val appContext = context.applicationContext
    private var announceJob: Job? = null
    private var registered = false

    // Runs on the main thread, so announceJob needs no extra synchronisation.
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    @Suppress("DEPRECATION")
                    val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                    // With READ_CALL_LOG, Android sends a second broadcast that carries the number.
                    if (number == null && hasPermission(Manifest.permission.READ_CALL_LOG)) return
                    onRinging(number)
                }
                // Answered (OFFHOOK) or ended (IDLE): stop talking immediately.
                else -> cancelAnnouncement()
            }
        }
    }

    fun register() {
        if (registered) return
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        registered = true
    }

    fun unregister() {
        cancelAnnouncement()
        if (registered) appContext.unregisterReceiver(receiver)
        registered = false
    }

    private fun onRinging(number: String?) {
        if (announceJob?.isActive == true || !canAnnounce()) return
        val text = announcementFor(number)
        Log.i(TAG, "📞 $text")
        announceJob = scope.launch {
            repeat(ANNOUNCE_REPEATS) { index ->
                if (index > 0) delay(ANNOUNCE_GAP_MS)
                SeemaState.setResponse("📞 $text")
                textToSpeech.speak(text)
            }
        }
    }

    private fun cancelAnnouncement() {
        announceJob?.cancel()
        announceJob = null
    }

    private fun announcementFor(number: String?): String {
        if (number.isNullOrBlank()) return "Incoming call"
        ContactNames.lookup(appContext, number)?.let { return "$it is calling" }
        val lastDigits = number.filter { it.isDigit() }.takeLast(4)
        return if (lastDigits.isEmpty()) {
            "Call from a private number"
        } else {
            "Call from an unknown number ending in ${lastDigits.toList().joinToString(" ")}"
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "SeemaCallerId"
        const val ANNOUNCE_REPEATS = 2
        const val ANNOUNCE_GAP_MS = 3_000L
    }
}
