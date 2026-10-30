package com.seema.assistant.ui.components

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.seema.assistant.AgentState
import com.seema.assistant.SeemaMediaTools
import com.seema.assistant.SeemaNotificationListener
import com.seema.assistant.SeemaWeatherTools
import com.seema.assistant.ui.theme.AlertRed
import com.seema.assistant.ui.theme.JarvisCyan
import com.seema.assistant.ui.theme.SpeakingGreen
import com.seema.assistant.ui.theme.TextSecondary
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live values shown around the reactor. Null means "not known yet". */
data class HudData(
    val now: LocalDateTime = LocalDateTime.now(),
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val temperature: String? = null,
    val condition: String? = null,
    val mediaTitle: String? = null,
    val mediaArtist: String? = null,
    val mediaPlaying: Boolean = false,
    val alarmTime: String? = null,
    val alarmDay: String? = null,
    val messageCount: Int? = null,
    val messageApps: String? = null,
    /** Null while unknown; "OFFLINE" when there is no connection. */
    val network: String? = null,
    /** 0 to 4 bars, null when the strength is unknown. */
    val signalLevel: Int? = null
)

/**
 * The arc reactor with seven floating HUD readouts: time, network, battery on top; next alarm
 * and unread messages beside it; weather and now playing below. Data only refreshes while the
 * screen is visible.
 */
@Composable
fun ReactorHud(state: AgentState, audioLevel: Float, modifier: Modifier = Modifier) {
    val hud = rememberHudData()

    // One slow shared phase; each readout bobs a few dp, offset so they drift independently.
    val phase by rememberInfiniteTransition(label = "hudFloat").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(FLOAT_PERIOD_MS, easing = LinearEasing)),
        label = "hudPhase"
    )
    val floatPx = with(LocalDensity.current) { FLOAT_AMPLITUDE.toPx() }

    // Layout: 3 readouts on top, 2 beside the reactor, 2 below. The reactor shrinks to leave room.
    BoxWithConstraints(modifier = modifier) {
        val reactorSize = min(maxWidth.value, maxHeight.value).dp * REACTOR_FRACTION
        val topWidth = maxWidth * TOP_READOUT_FRACTION
        val bottomWidth = maxWidth * BOTTOM_READOUT_FRACTION
        val sideWidth = (maxWidth - reactorSize) / 2 - SIDE_GAP

        ArcReactor(
            state = state,
            audioLevel = audioLevel,
            modifier = Modifier.size(reactorSize).align(Alignment.Center)
        )

        val batteryLow = hud.batteryPercent != null && hud.batteryPercent < LOW_BATTERY && !hud.charging
        fun Modifier.float(offset: Float) = graphicsLayer { translationY = sin(phase + offset) * floatPx }

        // ---- top row
        HudReadout(
            label = "TIME",
            value = hud.now.format(TIME_FORMAT),
            detail = hud.now.format(DATE_FORMAT).uppercase(Locale.ENGLISH),
            accent = JarvisCyan,
            align = HudAlign.Start,
            modifier = Modifier.align(Alignment.TopStart).widthIn(max = topWidth).float(0f)
        )
        HudReadout(
            label = "NETWORK",
            value = hud.network ?: "--",
            detail = hud.signalLevel?.let { signalBars(it) },
            accent = if (hud.network == OFFLINE) AlertRed else JarvisCyan,
            align = HudAlign.Center,
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = topWidth).float(0.9f)
        )
        HudReadout(
            label = "POWER",
            value = hud.batteryPercent?.let { "$it%" } ?: "--",
            detail = when {
                hud.batteryPercent == null -> null
                hud.charging -> "CHARGING ⚡"
                batteryLow -> "LOW — CHARGE SOON"
                else -> "ON BATTERY"
            },
            accent = when {
                hud.charging -> SpeakingGreen
                batteryLow -> AlertRed
                else -> JarvisCyan
            },
            align = HudAlign.End,
            modifier = Modifier.align(Alignment.TopEnd).widthIn(max = topWidth).float(1.8f)
        )

        // ---- beside the reactor
        HudReadout(
            label = "ALARM",
            value = hud.alarmTime ?: "NONE",
            detail = hud.alarmDay,
            accent = JarvisCyan,
            align = HudAlign.Start,
            compact = true,
            modifier = Modifier.align(Alignment.CenterStart).widthIn(max = sideWidth).float(2.7f)
        )
        HudReadout(
            label = "MESSAGES",
            value = hud.messageCount?.toString() ?: "--",
            detail = hud.messageApps,
            accent = if ((hud.messageCount ?: 0) > 0) SpeakingGreen else JarvisCyan,
            align = HudAlign.End,
            compact = true,
            modifier = Modifier.align(Alignment.CenterEnd).widthIn(max = sideWidth).float(3.6f)
        )

        // ---- bottom row
        HudReadout(
            label = "WEATHER",
            value = hud.temperature ?: "--",
            detail = hud.condition?.uppercase(Locale.ENGLISH),
            accent = JarvisCyan,
            align = HudAlign.Start,
            modifier = Modifier.align(Alignment.BottomStart).widthIn(max = bottomWidth).float(4.5f)
        )
        HudReadout(
            label = if (hud.mediaPlaying) "AUDIO ▶" else "AUDIO",
            value = hud.mediaTitle ?: "NO MEDIA",
            detail = hud.mediaArtist?.uppercase(Locale.ENGLISH),
            accent = if (hud.mediaPlaying) SpeakingGreen else JarvisCyan,
            align = HudAlign.End,
            modifier = Modifier.align(Alignment.BottomEnd).widthIn(max = bottomWidth).float(5.4f)
        )
    }
}

private enum class HudAlign { Start, Center, End }

@Composable
private fun HudReadout(
    label: String,
    value: String,
    detail: String?,
    accent: Color,
    align: HudAlign,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val horizontal = when (align) {
        HudAlign.Start -> Alignment.Start
        HudAlign.Center -> Alignment.CenterHorizontally
        HudAlign.End -> Alignment.End
    }
    val textAlign = when (align) {
        HudAlign.Start -> TextAlign.Start
        HudAlign.Center -> TextAlign.Center
        HudAlign.End -> TextAlign.End
    }
    Column(modifier = modifier, horizontalAlignment = horizontal) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary.copy(alpha = 0.8f))
        Box(
            modifier = Modifier
                .padding(vertical = 2.dp)
                .width(28.dp)
                .height(1.dp)
                .background(accent.copy(alpha = 0.7f))
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = if (compact) 13.sp else 15.sp
            ),
            color = accent,
            textAlign = textAlign,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Collects clock, battery, weather and media; polling stops while the app is in the background. */
@Composable
private fun rememberHudData(): HudData {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current

    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var battery by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    var weather by remember { mutableStateOf<Pair<String, String>?>(null) }
    var media by remember { mutableStateOf<Triple<String, String?, Boolean>?>(null) }
    var alarm by remember { mutableStateOf<Pair<String, String>?>(null) }
    var messages by remember { mutableStateOf<Pair<Int, String?>?>(null) }
    var network by remember { mutableStateOf<Pair<String, Int?>?>(null) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                while (true) {
                    now = LocalDateTime.now()
                    delay(1_000 - System.currentTimeMillis() % 1_000)
                }
            }
            launch {
                val tools = SeemaWeatherTools(context)
                while (true) {
                    readWeather(tools)?.let { weather = it }
                    delay(WEATHER_REFRESH_MS)
                }
            }
            launch {
                val tools = SeemaMediaTools(context)
                while (true) {
                    media = readMedia(tools)
                    delay(MEDIA_REFRESH_MS)
                }
            }
            launch {
                while (true) {
                    alarm = readNextAlarm(context)
                    messages = readMessages()
                    network = readNetwork(context)
                    delay(SYSTEM_REFRESH_MS)
                }
            }
        }
    }

    // Battery changes are pushed by the system, no polling needed.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                battery = parseBattery(intent)
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )?.let { battery = parseBattery(it) }
        onDispose { context.unregisterReceiver(receiver) }
    }

    return HudData(
        now = now,
        batteryPercent = battery?.first,
        charging = battery?.second == true,
        temperature = weather?.first,
        condition = weather?.second,
        mediaTitle = media?.first,
        mediaArtist = media?.second,
        mediaPlaying = media?.third == true,
        alarmTime = alarm?.first,
        alarmDay = alarm?.second,
        messageCount = messages?.first,
        messageApps = messages?.second,
        network = network?.first,
        signalLevel = network?.second
    )
}

private fun parseBattery(intent: Intent): Pair<Int, Boolean>? {
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return level * 100 / scale to charging
}

/** "29°C" to "partly cloudy", or null if the forecast could not be fetched. */
private suspend fun readWeather(tools: SeemaWeatherTools): Pair<String, String>? = withContext(Dispatchers.IO) {
    val weather = tools.getWeather()
    val temperature = weather["now_temperature"]
    if (weather["status"] != "ok" || temperature == null) null else temperature to (weather["now_condition"] ?: "")
}

/** Title, artist and whether it is playing; null when nothing is playing. */
private suspend fun readMedia(tools: SeemaMediaTools): Triple<String, String?, Boolean>? = withContext(Dispatchers.IO) {
    val media = tools.getNowPlaying()
    val title = media["title"]
    if (media["playing"] == "nothing" || title == null) null else Triple(title, media["artist"], media["state"] == "playing")
}

/** "07:30" and "TOMORROW" for the next alarm from any app, or null if none is set. */
private fun readNextAlarm(context: Context): Pair<String, String>? {
    val next = context.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return null
    val time = Instant.ofEpochMilli(next.triggerTime).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val day = when (time.toLocalDate()) {
        today -> "TODAY"
        today.plusDays(1) -> "TOMORROW"
        else -> time.format(ALARM_DAY_FORMAT).uppercase(Locale.ENGLISH)
    }
    return time.format(ALARM_TIME_FORMAT) to day
}

/** Unread chat messages in the notification shade, e.g. 5 to "WHATSAPP 4 · MESSAGES 1". */
private fun readMessages(): Pair<Int, String?>? {
    val chats = SeemaNotificationListener.activeItems()?.filter { it.isChat } ?: return null
    if (chats.isEmpty()) return 0 to null
    val byApp = chats.groupingBy { it.app }.eachCount().entries.sortedByDescending { it.value }
    return chats.size to byApp.joinToString(" · ") { "${it.key.uppercase(Locale.ENGLISH)} ${it.value}" }
}

/** "WI-FI" / "5G" / "4G"… with 0–4 signal bars, or "OFFLINE". */
private fun readNetwork(context: Context): Pair<String, Int?> {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return OFFLINE to null
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI" to wifiLevel(context, caps)
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> cellularLabel(context) to cellularLevel(context)
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET" to null
        else -> "ONLINE" to null
    }
}

private fun wifiLevel(context: Context, caps: NetworkCapabilities): Int? {
    val rssi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        caps.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(WifiManager::class.java).connectionInfo?.rssi
    } ?: return null
    return when {
        rssi >= -55 -> 4
        rssi >= -65 -> 3
        rssi >= -75 -> 2
        rssi >= -85 -> 1
        else -> 0
    }
}

private fun cellularLevel(context: Context): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    return runCatching { context.getSystemService(TelephonyManager::class.java).signalStrength?.level }.getOrNull()
}

/** Needs READ_PHONE_STATE (already granted for caller ID); falls back to "MOBILE". */
@SuppressLint("MissingPermission")
private fun cellularLabel(context: Context): String = try {
    when (context.getSystemService(TelephonyManager::class.java).dataNetworkType) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE, TelephonyManager.NETWORK_TYPE_IWLAN -> "4G"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_TD_SCDMA,
        TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B -> "3G"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "MOBILE"
        else -> "2G"
    }
} catch (e: SecurityException) {
    "MOBILE"
}

/** "▂▄▆_" style bars: filled up to [level] of 4. */
private fun signalBars(level: Int): String =
    BARS.mapIndexed { i, bar -> if (i < level) bar else '_' }.joinToString("")

private const val OFFLINE = "OFFLINE"
private const val BARS = "▂▄▆█"
private const val REACTOR_FRACTION = 0.56f
private const val TOP_READOUT_FRACTION = 0.33f
private const val BOTTOM_READOUT_FRACTION = 0.42f
private val SIDE_GAP = 4.dp
private const val SYSTEM_REFRESH_MS = 5_000L
private val ALARM_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val ALARM_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private const val LOW_BATTERY = 20
private const val FLOAT_PERIOD_MS = 6_000
private val FLOAT_AMPLITUDE = 3.dp
private const val WEATHER_REFRESH_MS = 30 * 60 * 1000L
private const val MEDIA_REFRESH_MS = 2_000L

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH)
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
