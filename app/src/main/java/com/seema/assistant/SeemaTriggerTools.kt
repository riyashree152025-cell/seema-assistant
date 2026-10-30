package com.seema.assistant

import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import android.provider.Settings
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import java.util.Locale
import java.util.UUID

/** Automations: routines started by places or power events. KSP generates `generatedTools()`. */
class SeemaTriggerTools(context: Context) {

    private val appContext = context.applicationContext

    @Tool(
        description = "Runs a routine automatically every time the user arrives at, or leaves, a place, " +
            "e.g. 'run driving mode when I leave home'. The place can be a saved place or an address."
    )
    fun setLocationRoutine(
        @Param("Routine name") routine: String,
        @Param("Saved place name like 'home', or an address") place: String,
        @Param("true to run when leaving the place; omit or false for arriving") whenLeaving: Boolean? = null
    ): Map<String, String> {
        val target = SeemaRoutines.find(routine) ?: return failure("No routine called '$routine'. Offer to create it.")
        permissionProblem()?.let { return it }
        val saved = SeemaPlaces.findPlace(place)
        val (placeName, lat, lon) = when {
            saved != null -> Triple(saved.name, saved.latitude, saved.longitude)
            else -> geocode(place)?.let { Triple(place.trim(), it.first, it.second) }
                ?: return failure("'$place' is not a saved place or a findable address. Offer to save it first.")
        }

        val trigger = LocationTrigger(UUID.randomUUID().toString(), target.name, placeName, lat, lon, whenLeaving != true)
        SeemaGeofences.register(appContext, trigger)?.let { return failure(it) }
        SeemaTriggers.addLocationTrigger(trigger)
        return mapOf("status" to "ok", "automation" to describeAutomation(trigger))
    }

    @Tool(
        description = "Runs a routine automatically on a charging or battery event, optionally only in a " +
            "time window, e.g. 'run good night when I plug in after 11 PM' (event 'plugged in', fromHour 23, toHour 6)."
    )
    fun setPowerRoutine(
        @Param("Routine name") routine: String,
        @Param("One of: plugged in, unplugged, battery low, fully charged") event: String,
        @Param("Optional start of the time window, hour 0 to 23") fromHour: Int? = null,
        @Param("Optional end of the time window, hour 0 to 23; may be earlier than fromHour to wrap past midnight") toHour: Int? = null
    ): Map<String, String> {
        val target = SeemaRoutines.find(routine) ?: return failure("No routine called '$routine'. Offer to create it.")
        val powerEvent = PowerEvent.parse(event)
            ?: return failure("Unknown event '$event'. Use plugged in, unplugged, battery low or fully charged.")
        if (fromHour != null && fromHour !in 0..23) return failure("fromHour must be 0 to 23.")
        if (toHour != null && toHour !in 0..23) return failure("toHour must be 0 to 23.")
        // "After 11 PM" with no end means "until morning"; "after 2 PM" means "until midnight".
        val end = toHour ?: fromHour?.let { if (it >= EVENING_HOUR) MORNING_END_HOUR else MIDNIGHT }

        val trigger = PowerTrigger(UUID.randomUUID().toString(), target.name, powerEvent, fromHour, end)
        SeemaTriggers.addPowerTrigger(trigger)
        return mapOf("status" to "ok", "automation" to describeAutomation(trigger))
    }

    @Tool(description = "Lists automations: routines that start by place or by charging and battery events.")
    fun listAutomations(): Map<String, String> {
        val all = SeemaTriggers.locationTriggers().map { describeAutomation(it) } + SeemaTriggers.powerTriggers().map { describeAutomation(it) }
        if (all.isEmpty()) return mapOf("status" to "ok", "automations" to "none")
        return mapOf("status" to "ok", "automations" to all.joinToString("; "))
    }

    @Tool(description = "Removes an automation matching the description, e.g. 'driving home' or 'good night plugged'.")
    fun removeAutomation(@Param("Words describing the automation") description: String): Map<String, String> {
        val words = SeemaMemory.normalize(description).split(' ').filter { it.length >= 3 }
        if (words.isEmpty()) return failure("Which automation should I remove?")
        val candidates = SeemaTriggers.locationTriggers().map { it.id to describeAutomation(it) } +
            SeemaTriggers.powerTriggers().map { it.id to describeAutomation(it) }
        val matches = candidates.filter { (_, text) ->
            val normalized = SeemaMemory.normalize(text)
            words.all { normalized.contains(it) }
        }
        return when {
            matches.isEmpty() -> failure("No automation matches '$description'.")
            matches.size > 1 -> failure("Several match: ${matches.joinToString("; ") { it.second }}. Ask which one.")
            else -> {
                val (id, text) = matches.single()
                SeemaGeofences.unregister(appContext, id)
                SeemaTriggers.remove(id)
                mapOf("status" to "ok", "removed" to text)
            }
        }
    }

    // --------------------------------------------------------------- helpers

    /** Same requirement as location reminders: precise location "all the time". */
    private fun permissionProblem(): Map<String, String>? {
        if (SeemaGeofences.hasPermissions(appContext)) return null
        if (!Settings.canDrawOverlays(appContext)) {
            return failure(
                "Location automations need Location set to 'Allow all the time' with precise location. " +
                    "Tell the user to set it in Settings, Apps, SEEMA, Permissions, Location."
            )
        }
        appContext.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${appContext.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return mapOf(
            "status" to "needs_user",
            "message" to "Opened SEEMA's app settings. Tell the user: Permissions, Location, choose " +
                "'Allow all the time' and turn on 'Use precise location', then ask again."
        )
    }

    @Suppress("DEPRECATION")
    private fun geocode(address: String): Pair<Double, Double>? = runCatching {
        if (!Geocoder.isPresent()) return null
        Geocoder(appContext, Locale.getDefault()).getFromLocationName(address, 1)?.firstOrNull()
            ?.let { it.latitude to it.longitude }
    }.getOrNull()

    private fun failure(message: String): Map<String, String> = mapOf("status" to "error", "error" to message)

    private companion object {
        const val EVENING_HOUR = 18
        const val MORNING_END_HOUR = 6
        const val MIDNIGHT = 0
    }
}

fun describeAutomation(trigger: LocationTrigger): String =
    "${trigger.routineName} routine ${if (trigger.onArrive) "when arriving at" else "when leaving"} ${trigger.placeName}"

fun describeAutomation(trigger: PowerTrigger): String {
    val event = when (trigger.event) {
        PowerEvent.CHARGER_CONNECTED -> "when plugged in"
        PowerEvent.CHARGER_DISCONNECTED -> "when unplugged"
        PowerEvent.BATTERY_LOW -> "when the battery gets low"
        PowerEvent.BATTERY_FULL -> "when fully charged"
    }
    return "${trigger.routineName} routine $event" + (trigger.describeWindow()?.let { " $it" } ?: "")
}
