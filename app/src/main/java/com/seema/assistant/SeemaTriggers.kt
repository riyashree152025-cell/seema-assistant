package com.seema.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.AtomicFile
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Runs [routineName] every time the user arrives at (or leaves) a place. */
data class LocationTrigger(
    override val id: String,
    val routineName: String,
    val placeName: String,
    override val latitude: Double,
    override val longitude: Double,
    override val onArrive: Boolean
) : GeofenceSpec

/** Phone power events that can start a routine. */
enum class PowerEvent(val spoken: String) {
    CHARGER_CONNECTED("the phone was plugged in"),
    CHARGER_DISCONNECTED("the phone was unplugged"),
    BATTERY_LOW("the battery got low"),
    BATTERY_FULL("the battery is fully charged");

    companion object {
        /** "plugged in", "unplug", "battery low", "fully charged"… → event; null if unclear. */
        fun parse(text: String): PowerEvent? {
            val t = text.lowercase(Locale.ROOT)
            return when {
                "unplug" in t || "disconnect" in t || "remove" in t || "off charg" in t -> CHARGER_DISCONNECTED
                "low" in t -> BATTERY_LOW
                "full" in t -> BATTERY_FULL
                "plug" in t || "charg" in t || "connect" in t -> CHARGER_CONNECTED
                else -> null
            }
        }
    }
}

/**
 * Runs [routineName] on [event], optionally only between [fromHour] and [toHour]
 * (a window may wrap past midnight, e.g. 23 to 6).
 */
data class PowerTrigger(
    val id: String,
    val routineName: String,
    val event: PowerEvent,
    val fromHour: Int? = null,
    val toHour: Int? = null
) {
    fun isActiveAt(hour: Int): Boolean {
        val from = fromHour ?: return true
        val to = toHour ?: return true
        return if (from <= to) hour in from until to else hour >= from || hour < to
    }

    fun describeWindow(): String? {
        val from = fromHour ?: return null
        val to = toHour ?: return null
        val format = DateTimeFormatter.ofPattern("h a", Locale.ENGLISH)
        return "between ${LocalTime.of(from, 0).format(format)} and ${LocalTime.of(to % 24, 0).format(format)}"
    }
}

/**
 * Automations: location-triggered and power-triggered routines, persisted as JSON.
 * Firing goes through [RoutineScheduler.runNow], the same background path as scheduled routines.
 */
object SeemaTriggers {

    private const val TAG = "SeemaTriggers"
    private const val FILE_NAME = "seema_triggers.json"

    private lateinit var file: AtomicFile
    private val locationTriggers = mutableListOf<LocationTrigger>()
    private val powerTriggers = mutableListOf<PowerTrigger>()

    /** Last firing per trigger, to ignore geofence flapping and charger wiggles. */
    private val lastFired = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))
        load()
    }

    @Synchronized
    fun locationTriggers(): List<LocationTrigger> = locationTriggers.toList()

    @Synchronized
    fun powerTriggers(): List<PowerTrigger> = powerTriggers.toList()

    @Synchronized
    fun locationTrigger(id: String): LocationTrigger? = locationTriggers.firstOrNull { it.id == id }

    @Synchronized
    fun addLocationTrigger(trigger: LocationTrigger) {
        locationTriggers += trigger
        save()
    }

    @Synchronized
    fun addPowerTrigger(trigger: PowerTrigger) {
        powerTriggers += trigger
        save()
    }

    @Synchronized
    fun remove(id: String) {
        locationTriggers.removeAll { it.id == id }
        powerTriggers.removeAll { it.id == id }
        save()
    }

    /** True (and records the time) if [id] has not fired within [cooldownMs]. */
    fun tryFire(id: String, cooldownMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val previous = lastFired[id]
        if (previous != null && now - previous < cooldownMs) return false
        lastFired[id] = now
        return true
    }

    private fun load() {
        try {
            val root = JSONObject(String(file.readFully(), Charsets.UTF_8))
            root.optJSONArray("location")?.let { array ->
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    locationTriggers += LocationTrigger(
                        item.getString("id"), item.getString("routine"), item.getString("place"),
                        item.getDouble("lat"), item.getDouble("lon"), item.getBoolean("onArrive")
                    )
                }
            }
            root.optJSONArray("power")?.let { array ->
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    powerTriggers += PowerTrigger(
                        item.getString("id"),
                        item.getString("routine"),
                        PowerEvent.valueOf(item.getString("event")),
                        if (item.has("from")) item.getInt("from") else null,
                        if (item.has("to")) item.getInt("to") else null
                    )
                }
            }
        } catch (e: FileNotFoundException) {
            // First run: no automations yet.
        } catch (e: Exception) {
            Log.e(TAG, "Automations file is corrupt; starting empty", e)
        }
    }

    private fun save() {
        val root = JSONObject()
            .put("location", JSONArray().apply {
                locationTriggers.forEach {
                    put(
                        JSONObject().put("id", it.id).put("routine", it.routineName).put("place", it.placeName)
                            .put("lat", it.latitude).put("lon", it.longitude).put("onArrive", it.onArrive)
                    )
                }
            })
            .put("power", JSONArray().apply {
                powerTriggers.forEach { trigger ->
                    val item = JSONObject().put("id", trigger.id).put("routine", trigger.routineName)
                        .put("event", trigger.event.name)
                    trigger.fromHour?.let { item.put("from", it) }
                    trigger.toHour?.let { item.put("to", it) }
                    put(item)
                }
            })
        val stream = file.startWrite()
        try {
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            Log.e(TAG, "Could not save automations", e)
        }
    }
}

/**
 * Watches charger and battery events while SeemaVoiceService runs. These broadcasts cannot be
 * received by a manifest receiver on Android 8+, so the always-on service registers for them.
 */
class PowerTriggerMonitor(context: Context) {

    private val appContext = context.applicationContext
    private var wasFull: Boolean? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> fire(PowerEvent.CHARGER_CONNECTED)
                Intent.ACTION_POWER_DISCONNECTED -> fire(PowerEvent.CHARGER_DISCONNECTED)
                Intent.ACTION_BATTERY_LOW -> fire(PowerEvent.BATTERY_LOW)
                Intent.ACTION_BATTERY_CHANGED -> {
                    // Sticky and frequent: fire only on the not-full → full transition.
                    val full = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_FULL
                    if (wasFull == false && full) fire(PowerEvent.BATTERY_FULL)
                    wasFull = full
                }
            }
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    fun unregister() {
        if (registered) appContext.unregisterReceiver(receiver)
        registered = false
    }

    private fun fire(event: PowerEvent) {
        val hour = LocalTime.now().hour
        SeemaTriggers.powerTriggers()
            .filter { it.event == event && it.isActiveAt(hour) }
            .filter { SeemaTriggers.tryFire(it.id, POWER_COOLDOWN_MS) }
            .forEach { trigger ->
                Log.i("SeemaTriggers", "⚡ ${event.spoken} → routine '${trigger.routineName}'")
                RoutineScheduler.runNow(appContext, trigger.routineName, event.spoken)
            }
    }

    private companion object {
        /** A wiggly cable must not run the routine twice. */
        const val POWER_COOLDOWN_MS = 60 * 60 * 1000L
    }
}
