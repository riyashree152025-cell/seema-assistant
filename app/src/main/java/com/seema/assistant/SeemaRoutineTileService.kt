package com.seema.assistant

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * Second Quick Settings tile: tap to pick a routine from a list and run it right away.
 */
class SeemaRoutineTileService : TileService() {

    override fun onStartListening() {
        SeemaRoutines.init(this)
        val tile = qsTile ?: return
        val running = SeemaState.serviceRunning.value
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (running) "${SeemaRoutines.all().size} routines" else "Offline"
        }
        tile.updateTile()
    }

    override fun onClick() {
        if (!SeemaState.serviceRunning.value) {
            openApp()
            return
        }
        // The picker is a dialog over Quick Settings; on the lock screen, unlock first.
        if (isLocked) unlockAndRun { showRoutinePicker() } else showRoutinePicker()
    }

    private fun showRoutinePicker() {
        SeemaRoutines.init(this)
        val routines = SeemaRoutines.all()
        if (routines.isEmpty()) {
            Toast.makeText(this, "No routines yet. Ask SEEMA to create one.", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = routines.map { routine ->
            routine.name.replaceFirstChar { it.uppercase() } +
                (routine.schedule?.let { "  ·  ${describeSchedule(it)}" } ?: "")
        }.toTypedArray()

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Run a SEEMA routine")
            .setItems(labels) { _, which -> SeemaState.requestRoutine(routines[which].name) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        showDialog(dialog)
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
