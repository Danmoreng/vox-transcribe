# VoxNotes - Detailed Development Plan

## **Architectural Vision**
VoxNotes is evolving from a single-screen prototype to a robust, offline-first meeting logger. The architecture follows Clean Architecture principles with Hilt for DI, Room for persistence, and a Foreground Service for mission-critical reliability.

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
    *   Created `LifecycleService` with `startForeground`.
2.  **The "Forever Loop" Logic:** ✅
    *   Implemented automatic restart on timeout/silence.

---

## **Phase 4: Domain & UI Refinement** ✅
*Objective: Transform the UI into a "Time Machine" log.*

1.  **Rich Models:** ✅
2.  **Home Screen:** ✅
3.  **Recording Screen:** ✅
4.  **Detail Screen:** ✅
    *   Implemented segment-based view with timestamps and export functionality.

---

## **Phase 5: Intelligence (Local AI)** 🔄
*Objective: On-device summarization and meeting notes generation.*

1.  **AI Integration:**
    *   Integrate Google AI Edge SDK (Gemini Nano) or equivalent local LLM.
    *   Implement `AiRepository` for model management and inference.
2.  **Smart Generation Features:**
    *   **Executive Summary:** A concise overview of the conversation.
    *   **Structured Meeting Notes:** Automatic extraction of action items, key decisions, and bulleted highlights.
3.  **Persistence & UI:**
    *   Update `NoteEntity` schema to store `summary` and `structuredNotes`.
    *   Add "AI Generate" triggers to the Detail Screen.
    *   Implement a "Review" UI showing the processed insights alongside the transcript.

---

## **Technical Standards**
- **State:** Use `StateFlow` in ViewModels, collected with `collectAsStateWithLifecycle`.
- **Threading:** Database ops on `Dispatchers.IO`, Speech UI updates on `Dispatchers.Main`.
- **UI:** Pure Material 3 components.
- **AI Safety:** Ensure all processing remains strictly on-device for privacy.
