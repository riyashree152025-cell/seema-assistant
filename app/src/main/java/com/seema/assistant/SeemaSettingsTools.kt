package com.seema.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Phone settings by voice. Where Android forbids apps from flipping a switch directly
 * (Wi-Fi on 10+, Bluetooth on 13+), the tool opens the system panel and returns
 * status "needs_user" so SEEMA can say which switch to tap.
 * KSP generates `SeemaSettingsTools.generatedTools()`.
 */
class SeemaSettingsTools(context: Context) {

    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)

    @Tool(description = "Returns the current Wi-Fi, Bluetooth, Do Not Disturb, ringer and screen brightness settings.")
    @SuppressLint("MissingPermission")
    fun getPhoneSettings(): Map<String, String> = buildMap {
        put("status", "ok")
        put("wifi", onOff(wifiManager.isWifiEnabled))
        put("bluetooth", bluetoothAdapter()?.let { onOff(it.isEnabled) } ?: "not available")
        put("do_not_disturb", if (dndIsOn()) "on" else "off")
        put("ringer", ringerName(audioManager.ringerMode))
        put("brightness_percent", currentBrightnessPercent().toString())
        put("auto_brightness", onOff(isAutoBrightness()))
    }

    // ----------------------------------------------------------------- Wi-Fi

    @Tool(description = "Turns Wi-Fi on or off.")
    fun setWifi(@Param("true for on, false for off") on: Boolean): Map<String, String> {
        if (wifiManager.isWifiEnabled == on) return ok("wifi", "already ${onOff(on)}")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            if (wifiManager.setWifiEnabled(on)) return ok("wifi", onOff(on))
        }
        // Android 10+ only lets the user flip Wi-Fi, so show the quick panel.
        launchActivity(Intent(Settings.Panel.ACTION_WIFI))?.let { return failure(it) }
        return needsUser("Opened the Wi-Fi panel. Android lets only the user flip Wi-Fi: tap the switch.")
    }

    // ------------------------------------------------------------- Bluetooth

    @Tool(description = "Turns Bluetooth on or off.")
    @SuppressLint("MissingPermission")
    fun setBluetooth(@Param("true for on, false for off") on: Boolean): Map<String, String> {
        val adapter = bluetoothAdapter() ?: return failure("This phone has no Bluetooth.")
        if (adapter.isEnabled == on) return ok("bluetooth", "already ${onOff(on)}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            return failure("The 'Nearby devices' permission is missing. Ask the user to allow it for SEEMA.")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            val changed = if (on) adapter.enable() else adapter.disable()
            if (changed) return ok("bluetooth", onOff(on))
        }
        // Android 13+: turning on needs the system dialog; turning off needs Settings.
        val intent = if (on) Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE) else Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        launchActivity(intent)?.let { return failure(it) }
        return needsUser(
            if (on) "Showed the system dialog. Tell the user to tap Allow to turn Bluetooth on."
            else "Opened Bluetooth settings. Android lets only the user turn it off: tap the switch."
        )
    }

    // ------------------------------------------------------ Do Not Disturb

    @Tool(
        description = "Turns Do Not Disturb on or off. 'On' uses priority mode, so alarms and " +
            "starred contacts still get through."
    )
    fun setDoNotDisturb(@Param("true for on, false for off") on: Boolean): Map<String, String> {
        val filter = if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        when {
            notificationManager.isNotificationPolicyAccessGranted -> notificationManager.setInterruptionFilter(filter)
            // Notification access (already granted for reading notifications) can also set DND.
            SeemaNotificationListener.setInterruptionFilter(filter) -> Unit
            else -> {
                launchActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))?.let { return failure(it) }
                return needsUser("Opened 'Do Not Disturb access'. Ask the user to allow SEEMA there, then try again.")
            }
        }
        return ok("do_not_disturb", onOff(on))
    }

    @Tool(description = "Sets the ringer to normal, vibrate or silent.")
    fun setRingerMode(@Param("One of: normal, vibrate, silent") mode: String): Map<String, String> {
        val ringer = when (mode.trim().lowercase(Locale.ROOT)) {
            "normal", "ring", "sound", "loud" -> AudioManager.RINGER_MODE_NORMAL
            "vibrate", "vibration" -> AudioManager.RINGER_MODE_VIBRATE
            "silent", "mute" -> AudioManager.RINGER_MODE_SILENT
            else -> return failure("Unknown ringer mode '$mode'. Use normal, vibrate or silent.")
        }
        return try {
            audioManager.ringerMode = ringer
            ok("ringer", ringerName(ringer))
        } catch (e: SecurityException) {
            // Entering or leaving silent counts as a DND change on Android 7+.
            failure("Android needs Do Not Disturb access for silent mode. Offer vibrate or Do Not Disturb instead.")
        }
    }

    // ------------------------------------------------------------ brightness

    @Tool(description = "Sets the screen brightness as a percentage, like the brightness slider. Turns auto-brightness off.")
    fun setBrightness(@Param("Brightness percentage from 0 to 100") percent: Int): Map<String, String> {
        writeSettingsProblem()?.let { return it }
        val resolver = appContext.contentResolver
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, percentToRaw(percent.coerceIn(0, 100)))
        return ok("brightness_percent", percent.coerceIn(0, 100).toString())
    }

    @Tool(description = "Turns automatic (adaptive) brightness on or off.")
    fun setAutoBrightness(@Param("true for on, false for off") on: Boolean): Map<String, String> {
        writeSettingsProblem()?.let { return it }
        Settings.System.putInt(
            appContext.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        return ok("auto_brightness", onOff(on))
    }

    // --------------------------------------------------------------- helpers

    /** Opens the "Modify system settings" screen when that permission is missing. */
    private fun writeSettingsProblem(): Map<String, String>? {
        if (Settings.System.canWrite(appContext)) return null
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${appContext.packageName}"))
        launchActivity(intent)?.let { return failure(it) }
        return needsUser("Opened 'Modify system settings'. Ask the user to allow SEEMA, then ask again.")
    }

    /** The slider is perceptual, the setting is linear; a 2.2 gamma keeps "50 percent" looking like half. */
    private fun percentToRaw(percent: Int): Int =
        ((percent / 100.0).pow(BRIGHTNESS_GAMMA) * maxBrightness()).roundToInt().coerceIn(1, maxBrightness())

    private fun currentBrightnessPercent(): Int {
        val raw = Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 0)
        return ((raw.toDouble() / maxBrightness()).coerceIn(0.0, 1.0).pow(1 / BRIGHTNESS_GAMMA) * 100).roundToInt()
    }

    /** 255 on most phones; some (e.g. Xiaomi) use a larger range, read from the framework config. */
    @SuppressLint("DiscouragedApi")
    private fun maxBrightness(): Int {
        val id = Resources.getSystem().getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
        return if (id != 0) Resources.getSystem().getInteger(id).coerceAtLeast(1) else DEFAULT_MAX_BRIGHTNESS
    }

    private fun isAutoBrightness(): Boolean =
        Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

    private fun dndIsOn(): Boolean =
        notificationManager.currentInterruptionFilter.let {
            it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private fun ringerName(mode: Int): String = when (mode) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        else -> "normal"
    }

    /** Starts a system screen. Returns an error message, or null on success. */
    private fun launchActivity(intent: Intent): String? {
        // Android 10+ blocks activity launches from background services unless this is granted.
        if (!Settings.canDrawOverlays(appContext)) {
            return "Permission missing. Tell the user to enable 'Display over other apps' for SEEMA in Settings."
        }
        return try {
            appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (e: Exception) {
            "This phone does not support that settings screen."
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun onOff(on: Boolean) = if (on) "on" else "off"

    private fun ok(key: String, value: String) = mapOf("status" to "ok", key to value)

    private fun needsUser(message: String) = mapOf("status" to "needs_user", "message" to message)

    private fun failure(message: String) = mapOf("status" to "error", "error" to message)

    private companion object {
        const val BRIGHTNESS_GAMMA = 2.2
        const val DEFAULT_MAX_BRIGHTNESS = 255
    }
}
