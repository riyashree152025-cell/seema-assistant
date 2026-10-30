package com.seema.assistant

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.seema.assistant.ui.components.reactorColor

/**
 * Home screen widget: a mini reactor tinted with SEEMA's current state, a TALK button that
 * starts a conversation without the wake word, and shortcut buttons for the first two routines.
 * SeemaVoiceService pushes state and routine updates.
 */
class SeemaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetManager.updateAppWidget(
            appWidgetIds,
            buildViews(context, SeemaState.state.value, SeemaState.serviceRunning.value)
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val routine = intent.getStringExtra(EXTRA_ROUTINE)
        when {
            intent.action != ACTION_TALK && intent.action != ACTION_ROUTINE -> return
            !SeemaState.serviceRunning.value ->
                // Allowed from the background thanks to "Display over other apps".
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            intent.action == ACTION_ROUTINE && routine != null -> SeemaState.requestRoutine(routine)
            else -> SeemaState.send(SeemaCommand.START_CONVERSATION)
        }
    }

    companion object {
        private const val ACTION_TALK = "com.seema.assistant.action.WIDGET_TALK"
        private const val ACTION_ROUTINE = "com.seema.assistant.action.WIDGET_ROUTINE"
        private const val EXTRA_ROUTINE = "routine"
        private val ROUTINE_BUTTONS = intArrayOf(R.id.widget_routine_1, R.id.widget_routine_2)
        private const val ROUTINE_LABEL_CHARS = 10
        private const val OFFLINE_COLOR = 0xFF546E7A.toInt()

        /** Redraws every SEEMA widget on the home screen. Cheap no-op when none are placed. */
        fun updateAll(context: Context, state: AgentState, running: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SeemaWidgetProvider::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, buildViews(context, state, running))
        }

        private fun buildViews(context: Context, state: AgentState, running: Boolean): RemoteViews {
            val color = if (running) state.reactorColor().toArgb() else OFFLINE_COLOR
            return RemoteViews(context.packageName, R.layout.widget_seema).apply {
                setInt(R.id.widget_reactor, "setColorFilter", color)
                setTextViewText(R.id.widget_status, if (running) state.label else "OFFLINE · TAP TO START")
                setTextColor(R.id.widget_status, color)
                setOnClickPendingIntent(R.id.widget_talk, talkIntent(context, running))
                setOnClickPendingIntent(R.id.widget_reactor, openAppIntent(context))

                SeemaRoutines.init(context)
                val routines = SeemaRoutines.all()
                ROUTINE_BUTTONS.forEachIndexed { slot, buttonId ->
                    val routine = routines.getOrNull(slot)
                    if (routine == null) {
                        setViewVisibility(buttonId, View.GONE)
                    } else {
                        setViewVisibility(buttonId, View.VISIBLE)
                        setTextViewText(buttonId, routine.name.uppercase().take(ROUTINE_LABEL_CHARS))
                        setOnClickPendingIntent(buttonId, routineIntent(context, slot, routine.name, running))
                    }
                }
            }
        }

        /** One PendingIntent per slot (distinct request codes), carrying the routine name. */
        private fun routineIntent(context: Context, slot: Int, routineName: String, running: Boolean): PendingIntent =
            if (running) {
                PendingIntent.getBroadcast(
                    context,
                    ROUTINE_REQUEST_BASE + slot,
                    Intent(context, SeemaWidgetProvider::class.java)
                        .setAction(ACTION_ROUTINE)
                        .putExtra(EXTRA_ROUTINE, routineName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            } else {
                openAppIntent(context)
            }

        private const val ROUTINE_REQUEST_BASE = 10

        /** TALK: a broadcast when SEEMA runs; otherwise open the app directly, which starts her. */
        private fun talkIntent(context: Context, running: Boolean): PendingIntent =
            if (running) {
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, SeemaWidgetProvider::class.java).setAction(ACTION_TALK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            } else {
                openAppIntent(context)
            }

        private fun openAppIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}
