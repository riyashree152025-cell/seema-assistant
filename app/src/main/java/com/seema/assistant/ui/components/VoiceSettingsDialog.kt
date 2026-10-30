package com.seema.assistant.ui.components

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.seema.assistant.VoiceSettings
import com.seema.assistant.ui.theme.DeepSpace
import com.seema.assistant.ui.theme.JarvisCyan
import com.seema.assistant.ui.theme.PanelSurface
import com.seema.assistant.ui.theme.TextSecondary
import java.util.Locale

/**
 * SEEMA's voice settings: TTS voice, pitch, speed, a JARVIS preset, a test button, and the
 * "prefer offline speech recognition" switch. Saved values apply to the next reply.
 */
@Composable
fun VoiceSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { VoiceSettings(context) }

    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    val preview = remember { VoicePreview(context) { voices = it } }
    DisposableEffect(Unit) { onDispose { preview.shutdown() } }

    var selected by remember { mutableStateOf(settings.voiceName) }
    var pitch by remember { mutableFloatStateOf(settings.pitch) }
    var rate by remember { mutableFloatStateOf(settings.speechRate) }
    var preferOffline by remember { mutableStateOf(settings.preferOfflineRecognition) }

    val shape = RoundedCornerShape(8.dp)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .background(PanelSurface, shape)
                .border(1.dp, JarvisCyan.copy(alpha = 0.5f), shape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("VOICE SETTINGS", style = MaterialTheme.typography.headlineSmall, color = JarvisCyan)

            SectionLabel("VOICE")
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .background(DeepSpace.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            ) {
                item { VoiceRow("Automatic (by language)", selected == null) { selected = null } }
                items(voices, key = { it.name }) { voice ->
                    VoiceRow(voiceLabel(voice), selected == voice.name) { selected = voice.name }
                }
            }
            if (voices.isEmpty()) {
                Text(
                    "Loading voices… Install more in Settings → System → Languages → Text-to-speech.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            SectionLabel("PITCH  ${"%.2f".format(Locale.ROOT, pitch)}   (lower = deeper)")
            HudSlider(pitch) { pitch = it }
            SectionLabel("SPEED  ${"%.2f".format(Locale.ROOT, rate)}")
            HudSlider(rate) { rate = it }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = preferOffline,
                    onCheckedChange = { preferOffline = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = JarvisCyan, checkedTrackColor = JarvisCyan.copy(alpha = 0.4f))
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Always use offline speech recognition",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton("JARVIS PRESET") {
                    preview.jarvisVoice(voices)?.let { selected = it.name }
                    pitch = VoiceSettings.JARVIS_PITCH
                    rate = VoiceSettings.JARVIS_RATE
                }
                DialogButton("▶ TEST") {
                    preview.speak("Good evening. All systems are online and at your service.", selected, pitch, rate)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton("CANCEL", onClick = onDismiss)
                DialogButton("SAVE") {
                    settings.voiceName = selected
                    settings.pitch = pitch
                    settings.speechRate = rate
                    settings.preferOfflineRecognition = preferOffline
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
}

@Composable
private fun VoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = JarvisCyan)
        )
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (selected) JarvisCyan else TextSecondary)
    }
}

@Composable
private fun HudSlider(value: Float, onChange: (Float) -> Unit) {
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = VoiceSettings.MIN..VoiceSettings.MAX,
        colors = SliderDefaults.colors(thumbColor = JarvisCyan, activeTrackColor = JarvisCyan)
    )
}

@Composable
private fun DialogButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.7f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisCyan)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** "English (United Kingdom) · en-gb-x-rjs-local", marked when it needs the internet. */
private fun voiceLabel(voice: Voice): String =
    "${voice.locale.getDisplayName(Locale.ENGLISH)} · ${voice.name}" +
        if (voice.isNetworkConnectionRequired) "  (online)" else ""

/** A private TextToSpeech used only to list voices and play the test line. */
private class VoicePreview(context: Context, onReady: (List<Voice>) -> Unit) {

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) onReady(installedVoices())
    }

    /** English and Hindi voices that are actually installed, grouped by language. */
    fun installedVoices(): List<Voice> =
        tts.voices.orEmpty()
            .filter { it.locale.language in LANGUAGES }
            .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features }
            .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.isNetworkConnectionRequired }, { it.name }))

    /** Best match for the JARVIS preset: a male British voice, preferring offline ones. */
    fun jarvisVoice(voices: List<Voice>): Voice? =
        VoiceSettings.JARVIS_VOICE_PREFIXES.firstNotNullOfOrNull { prefix ->
            voices.filter { it.name.startsWith(prefix) }.minByOrNull { it.isNetworkConnectionRequired }
        } ?: voices.firstOrNull { it.locale.country == "GB" && !it.isNetworkConnectionRequired }

    fun speak(text: String, voiceName: String?, pitch: Float, rate: Float) {
        val voice = voiceName?.let { name -> tts.voices?.firstOrNull { it.name == name } }
        if (voice != null) tts.setVoice(voice) else tts.setLanguage(Locale.forLanguageTag("en-IN"))
        tts.setPitch(pitch)
        tts.setSpeechRate(rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "seema_preview")
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private companion object {
        val LANGUAGES = setOf("en", "hi")
    }
}
