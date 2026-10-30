package com.seema.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A named place such as "home" or "office". */
data class SavedPlace(val name: String, val latitude: Double, val longitude: Double, val address: String)

/** Anything watched by a geofence: a location reminder or a location-triggered routine. */
interface GeofenceSpec {
    val id: String
    val latitude: Double
    val longitude: Double
    val onArrive: Boolean
}

/** Fires once when the user arrives at (or leaves) a place. */
data class LocationReminder(
    override val id: String,
    val message: String,
    val placeName: String,
    override val latitude: Double,
    override val longitude: Double,
    override val onArrive: Boolean
) : GeofenceSpec

/** Saved places and location reminders, persisted as JSON like [SeemaMemory]. */
object SeemaPlaces {

    private const val TAG = "SeemaPlaces"
    private const val FILE_NAME = "seema_places.json"

    private lateinit var file: AtomicFile
    private val places = mutableListOf<SavedPlace>()
    private val reminders = mutableListOf<LocationReminder>()

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))
        load()
    }

    @Synchronized
    fun places(): List<SavedPlace> = places.toList()

    /** Exact name first, then partial ("my office" finds "office"). */
    @Synchronized
    fun findPlace(name: String): SavedPlace? {
        val wanted = SeemaMemory.normalize(name).removePrefix("my ").trim()
        return places.firstOrNull { SeemaMemory.normalize(it.name) == wanted }
            ?: places.firstOrNull {
                val saved = SeemaMemory.normalize(it.name)
                saved.isNotEmpty() && (wanted.contains(saved) || saved.contains(wanted))
            }
    }

    /** Saves [place], replacing any place with the same name. */
    @Synchronized
    fun savePlace(place: SavedPlace) {
        places.removeAll { SeemaMemory.normalize(it.name) == SeemaMemory.normalize(place.name) }
        places += place
        save()
    }

    @Synchronized
    fun removePlace(place: SavedPlace) {
        places.remove(place)
        save()
    }

    @Synchronized
    fun reminders(): List<LocationReminder> = reminders.toList()

    @Synchronized
    fun getReminder(id: String): LocationReminder? = reminders.firstOrNull { it.id == id }

    @Synchronized
    fun addReminder(reminder: LocationReminder) {
        reminders += reminder
        save()
    }

    @Synchronized
    fun removeReminder(id: String) {
        reminders.removeAll { it.id == id }
        save()
    }

    private fun load() {
        try {
            val root = JSONObject(String(file.readFully(), Charsets.UTF_8))
            root.optJSONArray("places")?.let { array ->
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    places += SavedPlace(
                        item.getString("name"), item.getDouble("lat"), item.getDouble("lon"), item.optString("address")
                    )
                }
            }
            root.optJSONArray("reminders")?.let { array ->
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    reminders += LocationReminder(
                        item.getString("id"), item.getString("message"), item.getString("place"),
                        item.getDouble("lat"), item.getDouble("lon"), item.getBoolean("onArrive")
                    )
                }
            }
        } catch (e: FileNotFoundException) {
            // First run: nothing saved yet.
        } catch (e: JSONException) {
            Log.e(TAG, "Places file is corrupt; starting empty", e)
        }
    }

    private fun save() {
        val root = JSONObject()
            .put("places", JSONArray().apply {
                places.forEach {
                    put(JSONObject().put("name", it.name).put("lat", it.latitude).put("lon", it.longitude).put("address", it.address))
                }
            })
            .put("reminders", JSONArray().apply {
                reminders.forEach {
                    put(
                        JSONObject().put("id", it.id).put("message", it.message).put("place", it.placeName)
                            .put("lat", it.latitude).put("lon", it.longitude).put("onArrive", it.onArrive)
                    )
                }
            })
        val stream = file.startWrite()
        try {
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            Log.e(TAG, "Could not save places", e)
        }
    }
}

/**
 * Registers location reminders with Google Play services geofencing, which watches for
 * arrivals and departures using little battery. Needs precise + "all the time" location.
 */
object SeemaGeofences {

    private const val TAG = "SeemaGeofences"
    private const val RADIUS_METERS = 150f
    private const val REGISTER_TIMEOUT_SECONDS = 10L

    fun hasPermissions(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine && background
    }

    /** Blocking registration for tools (they run on Dispatchers.IO). Returns an error message or null. */
    @SuppressLint("MissingPermission")
    fun register(context: Context, spec: GeofenceSpec): String? {
        if (!hasPermissions(context)) return "Location permission missing."
        return try {
            Tasks.await(
                LocationServices.getGeofencingClient(context).addGeofences(request(spec), pendingIntent(context)),
                REGISTER_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
            null
        } catch (e: Exception) {
            Log.e(TAG, "Geofence registration failed", e)
            "Could not start watching that place. Make sure Location is turned on."
        }
    }

    fun unregister(context: Context, reminderId: String) {
        LocationServices.getGeofencingClient(context).removeGeofences(listOf(reminderId))
    }

    /** Geofences vanish on reboot and when Location is toggled; re-adding is harmless. */
    @SuppressLint("MissingPermission")
    fun registerAll(context: Context) {
        SeemaPlaces.init(context)
        SeemaTriggers.init(context)
        val specs: List<GeofenceSpec> = SeemaPlaces.reminders() + SeemaTriggers.locationTriggers()
        if (specs.isEmpty() || !hasPermissions(context)) return
        val client = LocationServices.getGeofencingClient(context)
        specs.forEach { spec ->
            client.addGeofences(request(spec), pendingIntent(context))
                .addOnFailureListener { Log.e(TAG, "Could not re-register geofence ${spec.id}", it) }
        }
        Log.i(TAG, "Re-registered ${specs.size} geofences")
    }

    private fun request(spec: GeofenceSpec): GeofencingRequest {
        val geofence = Geofence.Builder()
            .setRequestId(spec.id)
            .setCircularRegion(spec.latitude, spec.longitude, RADIUS_METERS)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(if (spec.onArrive) Geofence.GEOFENCE_TRANSITION_ENTER else Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        // Initial trigger 0: being inside the area already does not fire "arrive" immediately.
        return GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(geofence).build()
    }

    /** Play services fills in the event extras, so this PendingIntent must be mutable. */
    private fun pendingIntent(context: Context): PendingIntent {
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, GeofenceReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or mutable
        )
    }
}

/**
 * Fires location reminders (one-shot: notification + spoken) and location-triggered routines
 * (every time, via [RoutineScheduler.runNow]).
 */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.e("SeemaGeofences", "Geofence error: ${GeofenceStatusCodes.getStatusCodeString(event.errorCode)}")
            return
        }
        SeemaPlaces.init(context)
        SeemaTriggers.init(context)
        event.triggeringGeofences.orEmpty().forEach { geofence ->
            SeemaTriggers.locationTrigger(geofence.requestId)?.let { trigger ->
                // Walking along the edge of the area can flap in and out; run at most every 30 minutes.
                if (SeemaTriggers.tryFire(trigger.id, LOCATION_COOLDOWN_MS)) {
                    val reason = "the user ${if (trigger.onArrive) "arrived at" else "left"} ${trigger.placeName}"
                    Log.i("SeemaGeofences", "📍 $reason → routine '${trigger.routineName}'")
                    RoutineScheduler.runNow(context, trigger.routineName, reason)
                }
                return@forEach
            }
            val reminder = SeemaPlaces.getReminder(geofence.requestId) ?: return@forEach
            SeemaPlaces.removeReminder(reminder.id)
            SeemaGeofences.unregister(context, reminder.id)

            showReminderNotification(context, reminder.id.hashCode(), "📍 Reminder", reminder.message)
            SeemaState.announce("Reminder: ${reminder.message}")
            Log.i("SeemaGeofences", "📍 ${reminder.message} (${if (reminder.onArrive) "arrived at" else "left"} ${reminder.placeName})")
        }
    }
}

private const val LOCATION_COOLDOWN_MS = 30 * 60 * 1000L
