# Vox Transcribe - Detailed Development Plan

## **Architectural Vision**
Vox Transcribe is evolving from a single-screen prototype to a robust, offline-first meeting logger. The architecture follows Clean Architecture principles with Hilt for DI, Room for persistence, and a Foreground Service for mission-critical reliability.

---

## **Phase 1: Dependency Injection & Navigation** ✅
*Objective: Prepare the app for multi-screen navigation and structured dependency management.*

1.  **Hilt Integration:** ✅
2.  **Navigation Setup:** ✅

---

## **Phase 2: Data Persistence (Room)** ✅
*Objective: Ensure no transcription is ever lost.*

1.  **Entities & DAOs:** ✅
2.  **Repository Refactor:** ✅

---

## **Phase 3: Background Reliability (Foreground Service)** ✅
*Objective: Support long-running sessions (e.g., 60min meetings) without interruption.*

1.  **TranscriptionService:** ✅
2.  **The "Forever Loop" Logic:** ✅ (To be refactored into a State Machine in Phase 9)

---

## **Phase 4: Domain & UI Refinement** ✅
*Objective: Transform the UI into a "Time Machine" log.*

1.  **Rich Models:** ✅
2.  **Home Screen:** ✅
3.  **Recording Screen:** ✅
4.  **Detail Screen:** ✅

---

## **Phase 5: Intelligence (Local AI)** ✅
*Objective: On-device summarization and meeting notes generation.*

1.  **AI Integration:** ✅ (ML Kit Gemini Nano)
2.  **Smart Generation Features:** ✅
3.  **Persistence & UI:** ✅

---

## **Phase 6: Core Feature Completion** ✅
*Objective: Polish core functionality for daily use.*

1.  **Note Deletion:** ✅
2.  **UI Polish:** ✅

---

## **Phase 7: Voxtral Transcription Engine (C++ Port)** 🔄
*Objective: Integrate `voxtral.cpp` for high-fidelity, offline, private transcription.*

1.  **NDK & JNI Scaffolding:** ✅
    *   Compile `voxtral.cpp` (libvoxtral) as a shared library (`.so`) for `arm64-v8a`.
    *   Create a JNI wrapper to expose core functions (init, process_chunk, finalize) to Kotlin.
    *   Verify numerical parity with reference implementation.
2.  **Model Management:** ✅
    *   Implement logic to download the GGUF model (~2.5GB, Q4_0 quantization) to app-private storage.
    *   Ensure the model file is *not* bundled in the APK.
3.  **Streaming Pipeline:** 🔄
    *   Implement `AudioRecord` setup for 16-bit PCM, 16kHz audio.
    *   Create a buffering mechanism to feed audio in 80ms chunks to the C++ engine.
    *   Handle partial results and finalized segments for real-time UI updates.
    *   Target parameters: `temperature=0.0`, `transcription_delay_ms=480`.
4.  **Hardware Acceleration (Iterative):** 📅
    *   **Stage 1 (CPU):** Focus on correctness and stability on CPU first.
    *   **Stage 2 (Vulkan):** Attempt to enable `GGML_VULKAN` for GPU acceleration on supported devices.
    *   **Stage 3 (NPU):** Treat as a research track (NNAPI/QNN).

---

## **Phase 8: MediaPipe & AI Intelligence** 🔄
*Objective: High-performance SLM integration and smart capability management.*

1.  **MediaPipe LLM Integration:** 📅
    *   Add `com.google.mediapipe:tasks-genai`.
    *   Implement `MediaPipeAiRepository`.
2.  **AI Capability Gating:** 📅
    *   Implement detection for `checkFeatureStatus()` (ML Kit) and device hardware limits (MediaPipe).
    *   UI states: Unavailable / Downloadable / Downloading / Ready.
    *   Fallback logic: MediaPipe → ML Kit → Mock/Disabled.
3.  **AI Quality Guardrails:** 📅
    *   Implement chunking strategy (map-reduce) for long transcripts (>30 mins).
    *   Cache AI outputs and allow "Regenerate" with different parameters.

---

## **Phase 9: Platform & Compliance** 📅
*Objective: Ensure production-grade reliability on modern Android versions (14+).*

1.  **Foreground Service Hardening:**
    *   Declare `FOREGROUND_SERVICE_MICROPHONE` type and handle Android 14+ requirements.
    *   Implement "while-in-use" permission checks and background start restrictions.
2.  **Service State Machine:**
    *   Replace simple loops with an explicit lifecycle: `Idle → Recording → Transcribing → Paused → Error → Finalizing`.
    *   Implement bounded retries and exponential backoff for recognizer failures.
3.  **Resilience Mechanics:**
    *   Crash-safe checkpoints (flush segments to Room every 30s).
    *   Session recovery after process death.

---

## **Phase 10: Advanced Features & Search** 🔄
*Objective: Enhance the app for power users with efficient search and flexible exports.*

1.  **Room FTS Integration:** 📅
    *   Implement `@Fts4` index for transcripts and summaries.
    *   Support fast full-text search with snippets and ranking.
2.  **Rich Export Options:** 📅
    *   Support **Markdown**, TXT, and PDF.
    *   Include metadata (date, duration, AI provider).
    *   Use Storage Access Framework for user-controlled file saving.
3.  **Data Safety & Security:** 📅
    *   Biometric/PIN app lock option.
    *   Optional at-rest encryption for sensitive meeting DBs.
    *   Data retention policies (auto-delete after N days).

---

## **Phase 11: Quality & Release Readiness** 📅
*Objective: Validation and deployment preparation.*

1.  **Comprehensive Testing:**
    *   Unit tests for domain logic and AI repositories.
    *   Instrumentation tests for Room migrations and FGS behavior.
    *   Compose UI tests for critical flows.
2.  **CI/CD Pipeline:**
    *   Setup Lint, Detekt, and automated unit tests.
    *   Strategy for testing on-device AI (mocking requirements).
3.  **Play Console Compliance:**
    *   Prepare FGS type declarations and data safety labels.

---

## **Technical Standards**
- **State:** Use `StateFlow` in ViewModels, collected with `collectAsStateWithLifecycle`.
- **Threading:** Database ops on `Dispatchers.IO`, Speech UI updates on `Dispatchers.Main`.
- **UI:** Pure Material 3 components.
- **Privacy:** Strict on-device processing; no transcript data in logs/analytics.
