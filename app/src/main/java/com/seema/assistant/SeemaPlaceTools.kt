package com.seema.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Saved places and location reminders (geofencing). KSP generates `generatedTools()`. */
class SeemaPlaceTools(context: Context) {

    private val appContext = context.applicationContext

    @Tool(
        description = "Saves a named place such as 'home' or 'office'. Omit the address to save the " +
            "phone's current location ('remember this place as home')."
    )
    fun savePlace(
        @Param("Short name, e.g. 'home', 'office', 'gym'") name: String,
        @Param("Optional street address; omit to use the current location") address: String? = null
    ): Map<String, String> {
        val placeName = name.trim().lowercase(Locale.ROOT).removePrefix("my ").trim()
        if (placeName.isEmpty()) return failure("What should I call this place?")

        val place = if (address.isNullOrBlank()) {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                return failure("Precise location is off. Ask the user to allow precise Location for SEEMA.")
            }
            val location = currentPreciseLocation()
                ?: return failure("Could not get a location fix. Make sure Location is on and try again outdoors.")
            SavedPlace(placeName, location.latitude, location.longitude, describe(location) ?: "current location")
        } else {
            val found = geocode(address) ?: return failure("Could not find the address '$address'.")
            SavedPlace(placeName, found.latitude, found.longitude, found.getAddressLine(0) ?: address)
        }
        SeemaPlaces.savePlace(place)
        return mapOf("status" to "ok", "saved_place" to place.name, "address" to place.address)
    }

    @Tool(description = "Lists the user's saved places.")
    fun listPlaces(): Map<String, String> {
        val places = SeemaPlaces.places()
        if (places.isEmpty()) return mapOf("status" to "ok", "places" to "none")
        return mapOf("status" to "ok", "places" to places.joinToString("; ") { "${it.name}: ${it.address}" })
    }

    @Tool(description = "Deletes a saved place.")
    fun forgetPlace(@Param("Name of the saved place") name: String): Map<String, String> {
        val place = SeemaPlaces.findPlace(name) ?: return failure("No saved place called '$name'.")
        SeemaPlaces.removePlace(place)
        return mapOf("status" to "ok", "forgotten_place" to place.name)
    }

    @Tool(
        description = "Sets a reminder that fires when the user arrives at, or leaves, a place. The place " +
            "can be a saved place name ('home') or an address."
    )
    fun setLocationReminder(
        @Param("What to remind about, e.g. 'Buy milk'") message: String,
        @Param("Saved place name like 'home', or an address") place: String,
        @Param("true to remind when leaving the place; omit or false for arriving") whenLeaving: Boolean? = null
    ): Map<String, String> {
        if (message.isBlank()) return failure("What should I remind you about?")
        permissionProblem()?.let { return it }

        val target = SeemaPlaces.findPlace(place)
            ?: geocode(place)?.let { SavedPlace(place.trim(), it.latitude, it.longitude, it.getAddressLine(0) ?: place) }
            ?: return failure("'$place' is not a saved place and could not be found as an address. Offer to save it first.")

        val reminder = LocationReminder(
            id = UUID.randomUUID().toString(),
            message = message.trim(),
            placeName = target.name,
            latitude = target.latitude,
            longitude = target.longitude,
            onArrive = whenLeaving != true
        )
        SeemaGeofences.register(appContext, reminder)?.let { return failure(it) }
        SeemaPlaces.addReminder(reminder)
        return mapOf(
            "status" to "ok",
            "reminder" to reminder.message,
            "trigger" to "${if (reminder.onArrive) "when you arrive at" else "when you leave"} ${target.name}"
        )
    }

    @Tool(description = "Lists pending location reminders.")
    fun listLocationReminders(): Map<String, String> {
        val reminders = SeemaPlaces.reminders()
        if (reminders.isEmpty()) return mapOf("status" to "ok", "location_reminders" to "none")
        return mapOf("status" to "ok", "location_reminders" to reminders.joinToString("; ") { describe(it) })
    }

    @Tool(description = "Cancels a location reminder matching the description.")
    fun cancelLocationReminder(@Param("Words from the reminder, e.g. 'milk'") description: String): Map<String, String> {
        val words = SeemaMemory.normalize(description).split(' ').filter { it.length >= 3 }
        if (words.isEmpty()) return failure("Which location reminder should I cancel?")
        val matches = SeemaPlaces.reminders().filter { reminder ->
            val text = SeemaMemory.normalize("${reminder.message} ${reminder.placeName}")
            words.all { text.contains(it) }
        }
        return when {
            matches.isEmpty() -> failure("No location reminder matches '$description'.")
            matches.size > 1 -> failure("Several match: ${matches.joinToString("; ") { describe(it) }}. Ask which one.")
            else -> {
                val reminder = matches.single()
                SeemaGeofences.unregister(appContext, reminder.id)
                SeemaPlaces.removeReminder(reminder.id)
                mapOf("status" to "ok", "cancelled" to describe(reminder))
            }
        }
    }

    // --------------------------------------------------------------- helpers

    /** Geofencing needs precise location "all the time"; Android 11+ only grants that in Settings. */
    private fun permissionProblem(): Map<String, String>? {
        if (SeemaGeofences.hasPermissions(appContext)) return null
        if (!Settings.canDrawOverlays(appContext)) {
            return failure(
                "Location reminders need Location set to 'Allow all the time' with precise location for SEEMA. " +
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

    @SuppressLint("MissingPermission")
    private fun currentPreciseLocation(): Location? = runCatching {
        Tasks.await(
            LocationServices.getFusedLocationProviderClient(appContext)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null),
            LOCATION_TIMEOUT_SECONDS,
            TimeUnit.SECONDS
        )
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun geocode(address: String): Address? = runCatching {
        if (!Geocoder.isPresent()) return null
        Geocoder(appContext, Locale.getDefault()).getFromLocationName(address, 1)?.firstOrNull()
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun describe(location: Location): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        Geocoder(appContext, Locale.getDefault())
            .getFromLocation(location.latitude, location.longitude, 1)
            ?.firstOrNull()
            ?.getAddressLine(0)
    }.getOrNull()

    private fun describe(reminder: LocationReminder): String =
        "${reminder.message} (${if (reminder.onArrive) "arriving at" else "leaving"} ${reminder.placeName})"

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun failure(message: String): Map<String, String> = mapOf("status" to "error", "error" to message)

    private companion object {
        const val LOCATION_TIMEOUT_SECONDS = 15L
    }
}
