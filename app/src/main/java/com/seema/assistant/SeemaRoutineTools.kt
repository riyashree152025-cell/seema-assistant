package com.seema.assistant

import android.content.Context
import androidx.work.ExistingWorkPolicy
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Routines (macros) by voice. KSP generates `SeemaRoutineTools.generatedTools()`. */
class SeemaRoutineTools(context: Context) {

    private val appContext = context.applicationContext

    @Tool(
        description = "Starts a routine by name or trigger phrase and returns its steps. After calling it, " +
            "perform every step in order with your other tools, then give one short combined reply."
    )
    fun runRoutine(
        @Param("Routine name or the phrase the user said, e.g. 'good morning'") name: String
    ): Map<String, String> {
        val routine = SeemaRoutines.find(name) ?: return failure("No routine called '$name'.")
        return mapOf(
            "status" to "ok",
            "routine" to routine.name,
            "steps" to routine.steps.mapIndexed { i, step -> "${i + 1}) $step" }.joinToString("; "),
            "instructions" to "Do each step now, in order, using your tools. If a step fails or needs the " +
                "user, mention it in a few words and continue. Do music last. Then reply once, briefly."
        )
    }

    @Tool(
        description = "Creates or replaces a routine: a name, trigger phrases and plain-language steps that " +
            "you will later perform with your tools, e.g. steps 'Turn off Do Not Disturb; Set media volume " +
            "to 40 percent; Play Lo-fi Beats on Spotify'."
    )
    fun createRoutine(
        @Param("Short routine name, e.g. 'good morning', 'driving', 'workout'") name: String,
        @Param("Comma-separated phrases that start it, e.g. 'good morning, suprabhat'") triggerPhrases: String,
        @Param("Steps separated by semicolons, each one a single action in plain words") steps: String
    ): Map<String, String> {
        val routineName = name.trim().lowercase(Locale.ROOT)
        if (routineName.isEmpty()) return failure("The routine needs a name.")
        val stepList = steps.split(';', '\n', '|').map { it.trim() }.filter { it.isNotEmpty() }
        if (stepList.isEmpty()) return failure("The routine needs at least one step.")
        if (stepList.size > MAX_STEPS) return failure("A routine can have at most $MAX_STEPS steps.")
        val triggers = (triggerPhrases.split(',').map { it.trim().lowercase(Locale.ROOT) } + routineName)
            .filter { it.isNotEmpty() }
            .distinct()

        // Editing a routine keeps its schedule.
        val existingSchedule = SeemaRoutines.all().firstOrNull { it.name == routineName }?.schedule
        SeemaRoutines.put(Routine(routineName, triggers, stepList, existingSchedule))
        return mapOf(
            "status" to "ok",
            "saved_routine" to routineName,
            "triggers" to triggers.joinToString(", "),
            "steps" to stepList.mapIndexed { i, step -> "${i + 1}) $step" }.joinToString("; ")
        )
    }

    @Tool(description = "Lists the user's routines with their trigger phrases and steps.")
    fun listRoutines(): Map<String, String> {
        val routines = SeemaRoutines.all()
        if (routines.isEmpty()) return mapOf("status" to "ok", "routines" to "none")
        return mapOf("status" to "ok", "routines" to routines.joinToString(" | ") { describe(it) })
    }

    @Tool(description = "Deletes a routine. Only call after the user confirmed.")
    fun deleteRoutine(@Param("Routine name") name: String): Map<String, String> {
        val routine = SeemaRoutines.find(name) ?: return failure("No routine called '$name'.")
        SeemaRoutines.delete(routine)
        RoutineScheduler.cancel(appContext, routine.name)
        // Automations that would run the deleted routine go too.
        SeemaTriggers.locationTriggers().filter { it.routineName == routine.name }.forEach {
            SeemaGeofences.unregister(appContext, it.id)
            SeemaTriggers.remove(it.id)
        }
        SeemaTriggers.powerTriggers().filter { it.routineName == routine.name }.forEach { SeemaTriggers.remove(it.id) }
        return mapOf("status" to "ok", "deleted_routine" to routine.name)
    }

    @Tool(
        description = "Runs a routine automatically at a time of day, e.g. 'run my good night routine " +
            "every day at 11 PM'. Replaces any earlier schedule for that routine."
    )
    fun scheduleRoutine(
        @Param("Routine name") name: String,
        @Param("Hour in 24-hour format, 0 to 23 (11 PM = 23)") hour: Int,
        @Param("Minute, 0 to 59") minute: Int,
        @Param("Optional days: 'daily', 'weekdays', 'weekends' or 'mon,wed,fri'. Omit for every day.") days: String? = null
    ): Map<String, String> {
        val routine = SeemaRoutines.find(name) ?: return failure("No routine called '$name'. Offer to create it.")
        if (hour !in 0..23 || minute !in 0..59) return failure("Invalid time $hour:$minute.")
        val daySet = if (days.isNullOrBlank()) parseRepeatDays("daily")!! else {
            parseRepeatDays(days) ?: return failure("Could not understand the days '$days'.")
        }
        val scheduled = routine.copy(schedule = RoutineSchedule(hour, minute, daySet))
        SeemaRoutines.put(scheduled)
        RoutineScheduler.schedule(appContext, scheduled, ExistingWorkPolicy.REPLACE)
        return mapOf("status" to "ok", "routine" to routine.name, "schedule" to describeSchedule(scheduled.schedule!!))
    }

    @Tool(description = "Stops a routine from running automatically. The routine itself is kept.")
    fun unscheduleRoutine(@Param("Routine name") name: String): Map<String, String> {
        val routine = SeemaRoutines.find(name) ?: return failure("No routine called '$name'.")
        if (routine.schedule == null) return failure("The ${routine.name} routine is not scheduled.")
        SeemaRoutines.put(routine.copy(schedule = null))
        RoutineScheduler.cancel(appContext, routine.name)
        return mapOf("status" to "ok", "unscheduled" to routine.name)
    }

    private fun describe(routine: Routine): String =
        "${routine.name} (say: ${routine.triggers.joinToString(", ")}" +
            (routine.schedule?.let { "; runs automatically ${describeSchedule(it)}" } ?: "") + "): " +
            routine.steps.mapIndexed { i, step -> "${i + 1}) $step" }.joinToString("; ")

    private fun failure(message: String): Map<String, String> = mapOf("status" to "error", "error" to message)

    private companion object {
        const val MAX_STEPS = 12
    }
}

/** "every day at 11:00 PM". */
fun describeSchedule(schedule: RoutineSchedule): String =
    "${describeDays(schedule.days)} at " +
        LocalTime.of(schedule.hour, schedule.minute).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
