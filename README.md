# Vox Transcribe

Vox Transcribe is a privacy-first, offline-only Android meeting assistant. The current branch is built around Gemma 4 running through LiteRT-LM for both live transcription and post-processing tasks such as title generation, summaries, and meeting notes.

## Current Direction

- offline transcription with Gemma 4 audio-capable LiteRT-LM models
- offline text processing with the same selected Gemma model
- manual model import only
- no cloud processing, no in-app authentication, no token handling
- existing Android UI, Room persistence, and foreground recording service preserved

## Current Stack

- Kotlin
- Jetpack Compose
- Hilt
- Room
- LiteRT-LM (`com.google.ai.edge.litertlm`)
- DataStore
- WorkManager

## Platform Requirements

- Android Studio Ladybug or newer
- Android device on API 31+
- enough free device storage for imported models

## Supported Models

The app currently accepts only these exact LiteRT-LM artifacts:

- `gemma-4-E2B-it.litertlm`
- `gemma-4-E4B-it.litertlm`

Users download these files outside the app and then import them manually through the model settings screen.

## Current Capabilities

- manual import, selection, and deletion of supported Gemma models
- on-device title generation
- on-device summary generation
- on-device meeting notes generation
- live clip-based transcription for long-form recordings

## Current Transcription Pipeline

The current live transcription implementation is a bounded clip pipeline:

- 15 second clips
- 3 second overlap
- 16 kHz mono microphone capture
- WAV wrapping before LiteRT-LM audio input
- CPU-only audio inference path for device compatibility
- delayed overlap merge between adjacent clip results
- bounded backlog with explicit clip dropping if inference falls behind

This is already usable on device, but transcription quality and merge behavior are still being tuned.

## Building

Clone the repository and open it in Android Studio. There are no longer any required git submodules or native inference build steps for the Android app.

For command-line Gradle use on Windows, the repo includes:

```powershell
.\scripts\gradlew-jbr.ps1 :app:compileDebugKotlin
```

## Model Import Flow

1. Install the app on a device.
2. Download one of the supported `.litertlm` model files externally.
3. Open the app and go to the Gemma model screen from Home.
4. Import the downloaded model file.
5. Select the imported model.
6. Use recording for transcription or open a note detail screen for AI processing.

## Docs

- `docs/GEMMA_GALLERY_MIGRATION_PLAN.md`: migration plan and rationale
- `docs/GALLERY_STACK_RESEARCH.md`: findings from Google AI Edge Gallery investigation
- `docs/GEMMA_INFERENCE_ARCHITECTURE.md`: current Gemma architecture and runtime design
- `docs/MIGRATION_TASKLIST.md`: current implementation status and remaining work

## License

MIT License. See `LICENSE` for details.
