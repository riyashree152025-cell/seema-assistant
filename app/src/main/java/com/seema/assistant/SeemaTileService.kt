package com.seema.assistant

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: one tap starts a SEEMA conversation without the wake word.
 * If SEEMA is not running yet, it opens the app, which starts the voice service.
 */
class SeemaTileService : TileService() {

    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        if (SeemaState.serviceRunning.value) {
            SeemaState.send(SeemaCommand.START_CONVERSATION)
        } else {
            openApp()
        }
        refresh()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val running = SeemaState.serviceRunning.value
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (running) "Tap to talk" else "Offline"
        }
        tile.updateTile()
    }
}
