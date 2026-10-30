# SEEMA

A personal, always-on JARVIS-style voice assistant for Android: on-device wake word
(openWakeWord), Gemini brain via Google ADK for Kotlin, ~60 phone-control tools, memory,
routines, automations, proactive briefings, a sci-fi Compose HUD, Quick Settings tiles,
a home screen widget and an offline fallback mode.

Personal use only — not intended for the Play Store.

## Setup

1. Open the project in Android Studio (it will offer to add the Gradle wrapper on first sync).
2. Create `local.properties` in the project root (it is git-ignored):
   ```properties
   sdk.dir=/path/to/Android/sdk
   GEMINI_API_KEY=your-key-from-aistudio.google.com
   # optional fallback city for weather
   SEEMA_HOME_CITY=Patna
   ```
3. Put your wake-word model in `app/src/main/assets/`: `seema.onnx` (trained with the
   openWakeWord Colab) or `hey_jarvis_v0.1.onnx` for testing. `melspectrogram.onnx` and
   `embedding_model.onnx` are already included (Apache 2.0, see `LICENSE-MODELS`).
4. Build, install, and grant the permissions the app asks for.

## Requirements

Android 8.0+ (minSdk 26), compileSdk 35, Kotlin 2.0.21, AGP 8.7.3.
