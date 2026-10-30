package com.seema.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Invisible entry point for "start the assistant" system intents: a Bluetooth headset's
 * long-press / voice button, a wired headset long-press, and the assistant gesture
 * (long-press power/home) once SEEMA is the default digital assistant. Starts a conversation
 * and closes at once.
 */
class SeemaAssistActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("SeemaAssist", "Assistant trigger: ${intent?.action}")

        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        when {
            !micGranted -> startActivity(Intent(this, MainActivity::class.java))
            SeemaState.serviceRunning.value -> SeemaState.send(SeemaCommand.START_CONVERSATION)
            // This activity is in the foreground, so starting the microphone service is allowed.
            else -> ContextCompat.startForegroundService(
                this,
                Intent(this, SeemaVoiceService::class.java).setAction(SeemaVoiceService.ACTION_TALK)
            )
        }
        finish()
    }
}
