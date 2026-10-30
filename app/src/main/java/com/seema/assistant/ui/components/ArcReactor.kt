package com.seema.assistant.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.seema.assistant.AgentState
import com.seema.assistant.ui.theme.DeepSpace
import com.seema.assistant.ui.theme.HearingWhite
import com.seema.assistant.ui.theme.JarvisCyan
import com.seema.assistant.ui.theme.SpeakingGreen
import com.seema.assistant.ui.theme.TextSecondary
import com.seema.assistant.ui.theme.ThinkingAmber
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val TWO_PI = (2 * PI).toFloat()
private const val DEG_TO_RAD = (PI / 180).toFloat()

fun AgentState.reactorColor(): Color = when (this) {
    AgentState.IDLE -> TextSecondary
    AgentState.LISTENING -> JarvisCyan
    AgentState.HEARING -> HearingWhite
    AgentState.THINKING -> ThinkingAmber
    AgentState.SPEAKING -> SpeakingGreen
}

/** Ring rotation in degrees per second. */
private fun AgentState.rotationSpeed(): Float = when (this) {
    AgentState.IDLE -> 15f
    AgentState.LISTENING -> 45f
    AgentState.HEARING -> 30f
    AgentState.THINKING -> 220f
    AgentState.SPEAKING -> 90f
}

/** Core pulse in radians per second. */
private fun AgentState.pulseSpeed(): Float = when (this) {
    AgentState.IDLE -> 1.2f
    AgentState.LISTENING -> 2.5f
    AgentState.HEARING -> 5f
    AgentState.THINKING -> 7f
    AgentState.SPEAKING -> 10f
}

/**
 * @param audioLevel live microphone level from 0 to 1; makes the core and rings react to your voice.
 */
@Composable
fun ArcReactor(state: AgentState, modifier: Modifier = Modifier, audioLevel: Float = 0f) {
    val color by animateColorAsState(state.reactorColor(), tween(600), label = "reactorColor")
    // Short tween smooths the 10–20 Hz level updates without lagging behind your voice.
    val level by animateFloatAsState(audioLevel.coerceIn(0f, 1f), tween(90), label = "voiceLevel")
    val rotationSpeed by animateFloatAsState(state.rotationSpeed(), tween(800), label = "rotationSpeed")
    val pulseSpeed by animateFloatAsState(state.pulseSpeed(), tween(800), label = "pulseSpeed")

    var rotation by remember { mutableFloatStateOf(0f) }
    var phase by remember { mutableFloatStateOf(0f) }

    // Integrate angle/phase per frame so speed changes blend smoothly instead of jumping.
    LaunchedEffect(Unit) {
        var lastFrame = 0L
        while (true) {
            withFrameNanos { now ->
                if (lastFrame != 0L) {
                    val dt = (now - lastFrame) / 1_000_000_000f
                    rotation = (rotation + rotationSpeed * dt) % 360f
                    phase = (phase + pulseSpeed * dt) % TWO_PI
                }
                lastFrame = now
            }
        }
    }

    Canvas(modifier = modifier.aspectRatio(1f)) {
        val pulse = (sin(phase) + 1f) / 2f
        drawReactor(color, rotation, pulse, level)
    }
}

private fun DrawScope.drawReactor(color: Color, rotation: Float, pulse: Float, level: Float) {
    val r = size.minDimension / 2f
    val c = center

    // Ambient glow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = (0.22f + 0.23f * pulse + 0.35f * level).coerceAtMost(1f)),
                Color.Transparent
            ),
            center = c,
            radius = r
        ),
        radius = r,
        center = c
    )

    // Outer hairline
    drawCircle(
        color = color.copy(alpha = 0.35f),
        radius = r * 0.95f,
        center = c,
        style = Stroke(width = r * 0.008f)
    )

    // Voice ring: expands and brightens with the microphone level
    if (level > 0.01f) {
        drawCircle(
            color = color.copy(alpha = level),
            radius = r * (0.88f + 0.07f * level),
            center = c,
            style = Stroke(width = r * 0.015f)
        )
    }

    // Segmented outer ring
    rotate(rotation, c) {
        val ringR = r * 0.82f
        val segments = 12
        val sweep = 360f / segments
        repeat(segments) { i ->
            drawArc(
                color = color.copy(alpha = if (i % 3 == 0) 1f else 0.6f),
                startAngle = i * sweep,
                sweepAngle = sweep * 0.72f,
                useCenter = false,
                topLeft = Offset(c.x - ringR, c.y - ringR),
                size = Size(ringR * 2, ringR * 2),
                style = Stroke(width = r * (0.07f + 0.03f * level))
            )
        }
    }

    // Counter-rotating tick ring
    rotate(-rotation * 0.6f, c) {
        val outer = r * 0.70f
        repeat(60) { i ->
            val angle = i * 6f * DEG_TO_RAD
            val major = i % 5 == 0
            val inner = if (major) r * 0.62f else r * 0.66f
            drawLine(
                color = color.copy(alpha = if (major) 0.9f else 0.4f),
                start = Offset(c.x + cos(angle) * inner, c.y + sin(angle) * inner),
                end = Offset(c.x + cos(angle) * outer, c.y + sin(angle) * outer),
                strokeWidth = r * 0.01f,
                cap = StrokeCap.Round
            )
        }
    }

    // Fast inner arcs
    rotate(rotation * 2.2f, c) {
        val arcR = r * 0.48f
        repeat(3) { i ->
            drawArc(
                color = color,
                startAngle = i * 120f,
                sweepAngle = 70f,
                useCenter = false,
                topLeft = Offset(c.x - arcR, c.y - arcR),
                size = Size(arcR * 2, arcR * 2),
                style = Stroke(width = r * 0.035f, cap = StrokeCap.Round)
            )
        }
    }

    // Pulsing core
    val coreR = r * (0.26f + 0.05f * pulse + 0.10f * level)
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color.White,
            0.35f to color,
            1f to Color.Transparent,
            center = c,
            radius = coreR
        ),
        radius = coreR,
        center = c
    )
    drawCircle(
        color = color,
        radius = r * 0.32f,
        center = c,
        alpha = 0.4f + 0.6f * pulse,
        style = Stroke(width = r * 0.012f)
    )
}

@Preview
@Composable
private fun ArcReactorPreview() {
    ArcReactor(
        state = AgentState.LISTENING,
        modifier = Modifier
            .size(280.dp)
            .background(DeepSpace)
    )
}
