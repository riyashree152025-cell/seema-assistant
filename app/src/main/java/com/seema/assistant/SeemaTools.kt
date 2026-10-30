package com.seema.assistant

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Actions SEEMA can execute. KSP generates `SeemaTools.generatedTools()` from @Tool functions. */
class SeemaTools(context: Context) {

    private val appContext = context.applicationContext

    // ------------------------------------------------------------------ time

    @Tool(description = "Returns the current local time and date in the given city or country.")
    fun getCurrentTime(
        @Param("City or country name, e.g. 'Mumbai', 'New York', 'London', 'Japan'") city: String
    ): Map<String, String> {
        val zone = resolveZone(city)
            ?: return failure("Unknown location '$city'. Ask the user for a nearby major city.")
        val now = ZonedDateTime.now(zone)
        return mapOf(
            "status" to "ok",
            "city" to city,
            "timezone" to zone.id,
            "time" to now.format(TIME_FORMAT),
            "date" to now.format(DATE_FORMAT)
        )
    }

    // ------------------------------------------------------------ flashlight

    @Tool(description = "Turns the phone's flashlight (torch) on or off.")
    fun setFlashlight(
        @Param("true to turn the flashlight on, false to turn it off") on: Boolean
    ): Map<String, String> {
        val cameraManager = appContext.getSystemService(CameraManager::class.java)
        val cameraId = findTorchCamera(cameraManager)
            ?: return failure("This phone has no flashlight.")
        return try {
            cameraManager.setTorchMode(cameraId, on)
            mapOf("status" to "ok", "flashlight" to if (on) "on" else "off")
        } catch (e: CameraAccessException) {
            failure("The flashlight is busy because the camera is in use.")
        }
    }

    private fun findTorchCamera(cameraManager: CameraManager): String? {
        val withFlash = cameraManager.cameraIdList.filter { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        return withFlash.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: withFlash.firstOrNull()
    }

    // --------------------------------------------------------------- battery

    @Tool(description = "Returns the phone's battery level, charging state, power source and temperature.")
    fun getBatteryStatus(): Map<String, String> {
        val batteryManager = appContext.getSystemService(BatteryManager::class.java)
        val sticky = ContextCompat.registerReceiver(
            appContext,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val tenthsCelsius = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE

        return buildMap {
            put("status", "ok")
            put("level_percent", batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toString())
            put("charging", batteryManager.isCharging.toString())
            put(
                "power_source",
                when (plugged) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "wall charger"
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless charger"
                    else -> "battery"
                }
            )
            if (tenthsCelsius != Int.MIN_VALUE) {
                put("temperature_celsius", "%.1f".format(Locale.ROOT, tenthsCelsius / 10f))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && batteryManager.isCharging) {
                val remainingMs = batteryManager.computeChargeTimeRemaining()
                if (remainingMs > 0) put("minutes_until_full", (remainingMs / 60_000).toString())
            }
        }
    }

    // ------------------------------------------------------------------ apps

    @Tool(description = "Opens an installed app by name, e.g. 'YouTube', 'WhatsApp', 'Camera', 'Settings'.")
    fun openApp(
        @Param("The app's name as shown on the home screen") appName: String
    ): Map<String, String> {
        // Android 10+ blocks activity launches from background services unless this is granted.
        if (!Settings.canDrawOverlays(appContext)) {
            return failure(
                "Permission missing. Tell the user to enable 'Display over other apps' for SEEMA in Settings."
            )
        }
        val app = findInstalledApp(appName)
            ?: return failure("No installed app named '$appName'.")
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ?: return failure("${app.label} cannot be opened directly.")

        appContext.startActivity(launchIntent)
        return mapOf("status" to "ok", "opened" to app.label)
    }

    private data class InstalledApp(val label: String, val packageName: String)

    private fun findInstalledApp(query: String): InstalledApp? {
        val wanted = normalizeName(query)
        if (wanted.isEmpty()) return null

        val apps = launchableActivities()
            .map { InstalledApp(it.loadLabel(appContext.packageManager).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != appContext.packageName }
            .distinctBy { it.packageName }

        return apps.firstOrNull { normalizeName(it.label) == wanted }
            ?: apps.firstOrNull { normalizeName(it.label).startsWith(wanted) }
            ?: apps.firstOrNull { normalizeName(it.label).contains(wanted) }
            ?: apps.firstOrNull { app ->
                val label = normalizeName(app.label)
                label.length >= MIN_REVERSE_MATCH_LENGTH && wanted.contains(label)
            }
    }

    private fun launchableActivities(): List<ResolveInfo> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packageManager = appContext.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcher, 0)
        }
    }

    private fun normalizeName(name: String): String =
        name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    // ---------------------------------------------------------------- volume

    @Tool(description = "Sets the media (music and video) volume as a percentage from 0 to 100.")
    fun setMediaVolume(
        @Param("Target volume percentage, from 0 (mute) to 100 (max)") percent: Int
    ): Map<String, String> {
        val audioManager = appContext.getSystemService(AudioManager::class.java)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (percent.coerceIn(0, 100) * max + 50) / 100
        return try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            mapOf("status" to "ok", "volume_percent" to (target * 100 / max).toString())
        } catch (e: SecurityException) {
            failure("Do Not Disturb is blocking volume changes.")
        }
    }

    // --------------------------------------------------------------- helpers

    private fun failure(message: String): Map<String, String> =
        mapOf("status" to "error", "error" to message)

    private fun resolveZone(city: String): ZoneId? {
        val key = city.trim().lowercase(Locale.ROOT)
        LOCATION_ALIASES[key]?.let { return ZoneId.of(it) }

        // Matches tz database cities such as Asia/Kolkata, America/New_York, Europe/London.
        val normalized = key.replace(' ', '_')
        return ZoneId.getAvailableZoneIds()
            .firstOrNull { it.substringAfterLast('/').lowercase(Locale.ROOT) == normalized }
            ?.let(ZoneId::of)
    }

    private companion object {
        const val MIN_REVERSE_MATCH_LENGTH = 3

        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
        val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)

        /** Places that are not tz database identifiers. */
        val LOCATION_ALIASES: Map<String, String> = buildMap {
            listOf(
                "india", "delhi", "new delhi", "mumbai", "bombay", "bangalore", "bengaluru",
                "chennai", "madras", "hyderabad", "pune", "ahmedabad", "jaipur", "lucknow",
                "patna", "bhopal", "indore", "nagpur", "surat", "chandigarh", "calcutta"
            ).forEach { put(it, "Asia/Kolkata") }
            listOf("san francisco", "seattle", "las vegas", "california")
                .forEach { put(it, "America/Los_Angeles") }
            listOf("usa", "america", "washington", "boston", "miami", "atlanta")
                .forEach { put(it, "America/New_York") }
            listOf("uk", "england", "britain", "manchester").forEach { put(it, "Europe/London") }
            listOf("china", "beijing").forEach { put(it, "Asia/Shanghai") }
            listOf("uae", "abu dhabi").forEach { put(it, "Asia/Dubai") }
            listOf("saudi arabia", "mecca", "jeddah").forEach { put(it, "Asia/Riyadh") }
            put("japan", "Asia/Tokyo")
            put("pakistan", "Asia/Karachi")
            put("nepal", "Asia/Kathmandu")
            put("bangladesh", "Asia/Dhaka")
            put("australia", "Australia/Sydney")
            put("germany", "Europe/Berlin")
            put("france", "Europe/Paris")
            put("russia", "Europe/Moscow")
        }
    }
}
