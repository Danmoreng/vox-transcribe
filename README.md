# Vox Transcribe

Vox Transcribe is an offline Android meeting assistant for recording, transcribing, and summarizing conversations directly on-device.

The app is built around Gemma 4 running through LiteRT-LM and is designed for a local-first workflow: record speech, keep transcripts in a local Room database, and generate titles, summaries, or meeting notes without sending audio or text to a server.

## What The App Does

- records long-form audio sessions with a foreground recording service
- transcribes speech on-device using imported Gemma 4 LiteRT-LM models
- stores transcripts and notes locally with Room
- generates on-device note titles automatically after recording and can generate summaries and meeting notes from saved transcripts
- renders AI-generated summaries and meeting notes as markdown in the detail view
- supports manual model import and selection inside the app

## Key Characteristics

- offline-only inference
- no in-app authentication
- no cloud fallback
- one selected Gemma model used for both transcription and text tasks
- live transcription optimized for long-form recordings through clip scheduling and transcript stitching
- note titles are generated automatically after recording stops; summaries and meeting notes remain manual

## Supported Models

The app currently supports manual import of these exact LiteRT-LM model files:

- `gemma-4-E2B-it.litertlm`
- `gemma-4-E4B-it.litertlm`

These files are not bundled with the app. Download them externally, then import them through the Gemma model screen.

## Tech Stack

- Kotlin
- Jetpack Compose
- Hilt
- Room
- LiteRT-LM (`com.google.ai.edge.litertlm`)
- Mike Penz Multiplatform Markdown Renderer (`com.mikepenz:multiplatform-markdown-renderer-m3`)
- Android Foreground Service

## How Transcription Works

Live transcription uses bounded audio clips rather than a single continuous streaming decoder. The app:

- captures `16 kHz` mono microphone audio
- cuts clips using a silence-aware sliding window
- falls back to short overlap only when a clip must be forced to cut at the maximum window length
- runs Gemma audio transcription locally
- stitches adjacent clip results into a long-form transcript

The app also includes optional transcription language guidance:

- `Auto`
- `German`
- `English`

If the spoken language is known in advance, selecting it can noticeably reduce language drift and prompt leakage during transcription.

## Status

The current branch is already usable for:

- importing and selecting Gemma models
- on-device title, summary, and meeting-notes generation
- live on-device transcription with debug readouts for realtime factor, queue depth, and dropped clips

Transcription quality is still being tuned, especially around boundary cleanup and model-specific live settings.

## Requirements

- Android Studio Ladybug or newer
- Android device on API 31+
- enough free storage for imported `.litertlm` models

## Building

Clone the repository and open it in Android Studio. The Android app no longer depends on JNI/CMake inference code or git submodules.

For command-line Gradle use on Windows:

```powershell
.\scripts\gradlew-jbr.ps1 :app:compileDebugKotlin
```

## Basic Usage

1. Build and install the app on a supported device.
2. Download one of the supported `.litertlm` files externally.
3. Open the app and go to the Gemma model screen.
4. Import the model file and select it.
5. Choose a transcription language if you know the spoken language, or leave it on `Auto`.
6. Start a recording and wait for the app to finalize the transcript and generate a note title automatically.
7. Open a saved note to run summary and meeting-note generation manually.
8. View generated summaries and meeting notes with markdown formatting preserved in the note detail screen.

## Documentation

- `docs/GEMMA_GALLERY_MIGRATION_PLAN.md`
- `docs/GALLERY_STACK_RESEARCH.md`
- `docs/GEMMA_INFERENCE_ARCHITECTURE.md`
- `docs/MIGRATION_TASKLIST.md`

## License

MIT License. See `LICENSE` for details.
