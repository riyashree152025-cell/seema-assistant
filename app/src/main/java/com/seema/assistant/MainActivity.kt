package com.seema.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seema.assistant.ui.components.ReactorHud
import com.seema.assistant.ui.components.VoiceSettingsDialog
import com.seema.assistant.ui.components.reactorColor
import com.seema.assistant.ui.theme.AlertRed
import com.seema.assistant.ui.theme.DeepSpace
import com.seema.assistant.ui.theme.GridLine
import com.seema.assistant.ui.theme.JarvisCyan
import com.seema.assistant.ui.theme.PanelSurface
import com.seema.assistant.ui.theme.SeemaTheme
import com.seema.assistant.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {

    private var micGranted by mutableStateOf(false)
    private var askedOnce = false
    private var batteryPromptShown = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        micGranted = hasMicPermission()
        if (micGranted) startVoiceService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )

        micGranted = hasMicPermission()
        SeemaRoutines.init(this)

        setContent {
            SeemaTheme {
                val state by SeemaState.state.collectAsStateWithLifecycle()
                val lastResponse by SeemaState.lastResponse.collectAsStateWithLifecycle()
                val audioLevel by SeemaState.audioLevel.collectAsStateWithLifecycle()
                val routines by SeemaRoutines.routinesFlow.collectAsStateWithLifecycle()
                SeemaScreen(
                    state = state,
                    lastResponse = lastResponse,
                    audioLevel = audioLevel,
                    micGranted = micGranted,
                    onGrantClick = ::onGrantClick,
                    onTalk = { SeemaState.send(SeemaCommand.START_CONVERSATION) },
                    onBriefing = { SeemaState.send(SeemaCommand.DAILY_BRIEFING) },
                    routines = routines,
                    onRunRoutine = { name -> SeemaState.requestRoutine(name) },
                    onAddTile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ::requestAddTile else null,
                    onAddWidget = if (AppWidgetManager.getInstance(this).isRequestPinAppWidgetSupported) ::requestAddWidget else null
                )
            }
        }

        if (micGranted) startVoiceService() else requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Covers the case where the user granted the mic from system Settings.
        if (!micGranted && hasMicPermission()) {
            micGranted = true
            startVoiceService()
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        askedOnce = true
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun onGrantClick() {
        val permanentlyDenied = askedOnce &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        if (permanentlyDenied) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        } else {
            requestPermissions()
        }
    }

    /** Android 13+: system dialog that adds the SEEMA tile to Quick Settings in one tap. */
    private fun requestAddTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, SeemaTileService::class.java),
            "SEEMA",
            Icon.createWithResource(this, R.drawable.ic_seema_tile),
            mainExecutor
        ) { result -> Log.i("SeemaTile", "Add tile result: $result") }
    }

    /** Launcher dialog that places the SEEMA widget on the home screen. */
    private fun requestAddWidget() {
        AppWidgetManager.getInstance(this)
            .requestPinAppWidget(ComponentName(this, SeemaWidgetProvider::class.java), null, null)
    }

    private fun startVoiceService() {
        ContextCompat.startForegroundService(this, Intent(this, SeemaVoiceService::class.java))
        requestBatteryExemption()
    }

    /** Asks once per launch to exclude SEEMA from Doze so the service is not throttled. */
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        if (batteryPromptShown) return
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        batteryPromptShown = true
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.fromParts("package", packageName, null))
        )
    }
}

@Composable
fun SeemaScreen(
    state: AgentState,
    lastResponse: String,
    audioLevel: Float,
    micGranted: Boolean,
    onGrantClick: () -> Unit,
    onTalk: () -> Unit,
    onBriefing: () -> Unit,
    routines: List<Routine>,
    onRunRoutine: (String) -> Unit,
    onAddTile: (() -> Unit)?,
    onAddWidget: (() -> Unit)?
) {
    val displayState = if (micGranted) state else AgentState.IDLE
    var showSettings by rememberSaveable { mutableStateOf(false) }
    if (showSettings) VoiceSettingsDialog(onDismiss = { showSettings = false })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepSpace)
            .drawBehind { drawHud() }
            .systemBarsPadding()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "SEEMA",
                        style = MaterialTheme.typography.displayLarge,
                        color = JarvisCyan
                    )
                    Text(
                        text = "PERSONAL AI SYSTEM // v1.0",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                // Voice settings
                Text(
                    text = "⚙",
                    fontSize = 22.sp,
                    color = JarvisCyan,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clickable { showSettings = true }
                        .padding(8.dp)
                )
            }

            ReactorHud(
                state = displayState,
                audioLevel = if (displayState == AgentState.HEARING) audioLevel else 0f,
                // Takes all the height the other elements leave, so it fits small screens too.
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            StatusIndicator(
                state = displayState,
                micGranted = micGranted,
                onGrantClick = onGrantClick
            )

            if (micGranted) {
                ControlRow(
                    busy = displayState != AgentState.LISTENING,
                    onTalk = onTalk,
                    onBriefing = onBriefing,
                    routines = routines,
                    onRunRoutine = onRunRoutine,
                    onAddTile = onAddTile,
                    onAddWidget = onAddWidget
                )
            }

            ResponsePanel(lastResponse = lastResponse)
        }
    }
}

@Composable
private fun StatusIndicator(
    state: AgentState,
    micGranted: Boolean,
    onGrantClick: () -> Unit
) {
    val blink by rememberInfiniteTransition(label = "blink").animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "blinkAlpha"
    )

    if (!micGranted) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "MICROPHONE ACCESS REQUIRED",
                style = MaterialTheme.typography.labelLarge,
                color = AlertRed,
                modifier = Modifier.alpha(blink)
            )
            Spacer(Modifier.size(12.dp))
            OutlinedButton(
                onClick = onGrantClick,
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisCyan)
            ) {
                Text("GRANT ACCESS", style = MaterialTheme.typography.labelLarge)
            }
        }
        return
    }

    val color = state.reactorColor()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .alpha(blink)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = state.label,
            style = MaterialTheme.typography.titleMedium,
            color = color
        )
    }
}

/** TALK and BRIEFING work like the wake word; they are disabled while SEEMA is busy. */
@Composable
private fun ControlRow(
    busy: Boolean,
    onTalk: () -> Unit,
    onBriefing: () -> Unit,
    routines: List<Routine>,
    onRunRoutine: (String) -> Unit,
    onAddTile: (() -> Unit)?,
    onAddWidget: (() -> Unit)?
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton("TALK", enabled = !busy, onClick = onTalk)
            HudButton("BRIEFING", enabled = !busy, onClick = onBriefing)
        }
        if (routines.isNotEmpty()) {
            // One chip per routine; scrolls sideways when there are many.
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                routines.forEach { routine ->
                    HudButton(
                        label = "▸ ${routine.name.uppercase()}",
                        enabled = !busy,
                        onClick = { onRunRoutine(routine.name) }
                    )
                }
            }
        }
        if (onAddTile != null || onAddWidget != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onAddTile != null) HudButton("+ TILE", enabled = true, onClick = onAddTile)
                if (onAddWidget != null) HudButton("+ WIDGET", enabled = true, onClick = onAddWidget)
            }
        }
    }
}

@Composable
private fun HudButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, JarvisCyan.copy(alpha = if (enabled) 0.7f else 0.2f)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = JarvisCyan,
            disabledContentColor = TextSecondary.copy(alpha = 0.4f)
        )
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ResponsePanel(lastResponse: String) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp, max = 160.dp)
            .background(PanelSurface.copy(alpha = 0.7f), shape)
            .border(1.dp, JarvisCyan.copy(alpha = 0.4f), shape)
            .padding(16.dp)
    ) {
        Text(
            text = if (lastResponse.isBlank()) "> AWAITING WAKE WORD \"SEEMA\"_" else "> $lastResponse",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Start,
            modifier = Modifier.verticalScroll(rememberScrollState())
        )
    }
}

/** Background HUD: faint grid plus corner brackets. */
private fun DrawScope.drawHud() {
    val step = 32.dp.toPx()
    val stroke = 1.dp.toPx()

    var x = 0f
    while (x < size.width) {
        drawLine(GridLine, Offset(x, 0f), Offset(x, size.height), stroke)
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(GridLine, Offset(0f, y), Offset(size.width, y), stroke)
        y += step
    }

    val inset = 16.dp.toPx()
    val len = 28.dp.toPx()
    val bracket = JarvisCyan.copy(alpha = 0.6f)
    val w = 2.dp.toPx()
    val corners = listOf(
        Offset(inset, inset) to Offset(1f, 1f),
        Offset(size.width - inset, inset) to Offset(-1f, 1f),
        Offset(inset, size.height - inset) to Offset(1f, -1f),
        Offset(size.width - inset, size.height - inset) to Offset(-1f, -1f)
    )
    corners.forEach { (p, dir) ->
        drawLine(bracket, p, Offset(p.x + len * dir.x, p.y), w)
        drawLine(bracket, p, Offset(p.x, p.y + len * dir.y), w)
    }
}
