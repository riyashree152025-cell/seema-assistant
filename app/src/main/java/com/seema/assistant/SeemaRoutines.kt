package com.seema.assistant

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.time.DayOfWeek
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A routine (macro): trigger phrases plus plain-language steps. The agent performs the steps
 * with its normal tools, so any tool SEEMA has can be part of a routine.
 */
data class Routine(
    val name: String,
    val triggers: List<String>,
    val steps: List<String>,
    val schedule: RoutineSchedule? = null
)

/** Runs the routine automatically at [hour]:[minute] on [days] (see [RoutineScheduler]). */
data class RoutineSchedule(val hour: Int, val minute: Int, val days: Set<DayOfWeek>)

/** Routines persisted as JSON like [SeemaMemory]; seeded with two examples on first run. */
object SeemaRoutines {

    private const val TAG = "SeemaRoutines"
    private const val FILE_NAME = "seema_routines.json"

    private lateinit var file: AtomicFile
    private val routines = mutableListOf<Routine>()

    private val _routines = MutableStateFlow<List<Routine>>(emptyList())

    /** Live list for the UI, the widget and the routine tile. */
    val routinesFlow: StateFlow<List<Routine>> = _routines.asStateFlow()

    private val DEFAULT_ROUTINES = listOf(
        Routine(
            name = "good morning",
            triggers = listOf("good morning", "suprabhat", "morning routine"),
            steps = listOf(
                "Turn off Do Not Disturb",
                "Set media volume to 40 percent",
                "Give the daily briefing",
                "Play some upbeat morning music in the usual music app"
            )
        ),
        Routine(
            name = "good night",
            triggers = listOf("good night", "shubh ratri", "night routine"),
            steps = listOf(
                "Pause any music",
                "Turn on Do Not Disturb",
                "Set screen brightness to 10 percent",
                "Tell me my next alarm",
                "Wish me good night"
            )
        )
    )

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))
        if (!load()) {
            routines += DEFAULT_ROUTINES
            save()
        }
        _routines.value = routines.toList()
    }

    @Synchronized
    fun all(): List<Routine> = routines.toList()

    /** Matches a routine by its name or any trigger phrase (exact first, then contained). */
    @Synchronized
    fun find(phrase: String): Routine? {
        val wanted = SeemaMemory.normalize(phrase)
        if (wanted.isEmpty()) return null
        fun Routine.keys() = (triggers + name).map { SeemaMemory.normalize(it) }.filter { it.isNotEmpty() }
        return routines.firstOrNull { wanted in it.keys() }
            ?: routines.firstOrNull { routine -> routine.keys().any { wanted.contains(it) || it.contains(wanted) } }
    }

    /** Saves [routine], replacing an existing one with the same name. */
    @Synchronized
    fun put(routine: Routine) {
        routines.removeAll { SeemaMemory.normalize(it.name) == SeemaMemory.normalize(routine.name) }
        routines += routine
        save()
    }

    @Synchronized
    fun delete(routine: Routine) {
        routines.remove(routine)
        save()
    }

    /** Returns false when no file exists yet (first run). */
    private fun load(): Boolean = try {
        val array = JSONArray(String(file.readFully(), Charsets.UTF_8))
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            routines += Routine(
                name = item.getString("name"),
                triggers = item.getJSONArray("triggers").let { t -> (0 until t.length()).map { t.getString(it) } },
                steps = item.getJSONArray("steps").let { s -> (0 until s.length()).map { s.getString(it) } },
                schedule = item.optJSONObject("schedule")?.let { sch ->
                    val days = sch.getJSONArray("days")
                    RoutineSchedule(
                        sch.getInt("hour"),
                        sch.getInt("minute"),
                        (0 until days.length()).map { DayOfWeek.of(days.getInt(it)) }.toSet()
                    )
                }
            )
        }
        true
    } catch (e: FileNotFoundException) {
        false
    } catch (e: JSONException) {
        Log.e(TAG, "Routines file is corrupt; keeping it out of use", e)
        true
    }

    private fun save() {
        val array = JSONArray()
        routines.forEach {
            val item = JSONObject()
                .put("name", it.name)
                .put("triggers", JSONArray(it.triggers))
                .put("steps", JSONArray(it.steps))
            it.schedule?.let { sch ->
                item.put(
                    "schedule",
                    JSONObject().put("hour", sch.hour).put("minute", sch.minute)
                        .put("days", JSONArray(sch.days.map { day -> day.value }.sorted()))
                )
            }
            array.put(item)
        }
        _routines.value = routines.toList()
        val stream = file.startWrite()
        try {
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            Log.e(TAG, "Could not save routines", e)
        }
    }
}
