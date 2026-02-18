# Vox Transcribe - Private Offline Meeting Assistant

Vox Transcribe is a privacy-first, offline-only Android application designed to transcribe and summarize meetings entirely on your device. By leveraging local AI models (Voxtral for transcription and Gemma/Gemini Nano for summarization), Vox Transcribe ensures your conversations never leave your phone.

## Features

*   **Offline Transcription**: High-fidelity speech-to-text using the **Voxtral** engine (C++ port of Mistral's model) running locally via NDK/JNI.
*   **AI Summarization**: Generate executive summaries and action items using on-device LLMs (MediaPipe GenAI / ML Kit).
*   **Privacy First**: No cloud processing, no data leaks. All data is stored in a local encrypted database (Room).
*   **Background Recording**: Reliable long-form recording using a Foreground Service.
*   **Modern UI**: Built with Jetpack Compose and Material 3.

## Architecture

*   **Language**: Kotlin
*   **UI Toolkit**: Jetpack Compose
*   **DI**: Hilt
*   **Persistence**: Room Database
*   **Transcription Engine**: Custom C++ integration of [voxtral.cpp](https://github.com/andrijdavid/voxtral.cpp) (GGML/GGUF based).
*   **AI Engine**: MediaPipe GenAI / Google ML Kit.

## Setup & Installation

### Prerequisites
*   Android Studio Ladybug or newer.
*   Android Device with API 26+ (API 31+ recommended for AI features).
*   ~4GB free storage on device (for models).

### Building the App
1.  Clone the repository:
    ```bash
    git clone https://github.com/Danmoreng/vox-transcribe.git
    cd vox-transcribe
    ```
2.  Initialize submodules (for Voxtral engine):
    ```bash
    git submodule update --init --recursive
    ```
3.  Open in Android Studio and build.

### Setting up the Voxtral Model
The app requires a GGUF model file to function. This file is not bundled due to its size (~2.5GB).

1.  **Download Model**: Download `voxtral-mini-4b-realtime-q4_0.gguf` from Hugging Face.
2.  **Install App**: Run the app on your device.
3.  **Import Model**:
    *   Open the app.
    *   Tap the **Settings (Gear)** icon on the Home screen.
    *   Tap **"Import .gguf Model File"** and select the downloaded file.
    *   Wait for the status to change to **"Found"**.
4.  **Load Engine**:
    *   Tap **"Load Engine"**.
    *   Wait ~30 seconds for the model to load into RAM (Status: **"Ready"**).
5.  **Record**: Go back to Home and tap "New Recording".

## Acknowledgements

*   **Voxtral**: [andrijdavid/voxtral.cpp](https://github.com/andrijdavid/voxtral.cpp)
*   **GGML**: [ggml-org/ggml](https://github.com/ggml-org/ggml)
*   **Mistral AI**: Creators of the original Voxtral model.

## License

MIT License. See `LICENSE` for details.
