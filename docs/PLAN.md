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
2.  **The "Forever Loop" Logic:** ✅

---

## **Phase 4: Domain & UI Refinement** ✅
*Objective: Transform the UI into a "Time Machine" log.*

1.  **Rich Models:** ✅
2.  **Home Screen:** ✅
3.  **Recording Screen:** ✅
4.  **Detail Screen:** ✅
    *   Implemented segment-based view with timestamps and export functionality.

---

## **Phase 5: Intelligence (Local AI)** ✅
*Objective: On-device summarization and meeting notes generation.*

1.  **AI Integration:** ✅
    *   Integrated ML Kit GenAI Summarization API (Gemini Nano) for on-device processing.
    *   Implemented `AiRepository` with `GeminiAiRepository` and `MockAiRepository` for testing.
2.  **Smart Generation Features:** ✅
    *   **Executive Summary:** A concise overview of the conversation using `ONE_BULLET` output.
    *   **Structured Meeting Notes:** Extraction of key highlights using `THREE_BULLETS` output.
3.  **Persistence & UI:** ✅
    *   Updated `Note` entity to store `summary` and `structuredNotes`.
    *   Added "AI Insights" tab to the Detail Screen.
    *   Improved layout to accommodate action buttons below the title.

---

## **Phase 6: Core Feature Completion** ✅
*Objective: Polish core functionality for daily use.*

1.  **Note Deletion:** ✅
    *   Implemented deletion in `NotesDao`, `NotesRepository`, and `ViewModels`.
    *   Added confirmation dialogs to prevent accidental data loss.
    *   Fixed Room/Flow lifecycle crashes during deletion from the detail view.
2.  **UI Polish:** ✅
    *   Refactored Detail Screen to separate title from action buttons for better clarity.
    *   Added `HorizontalDivider` and spaced-out action row.

---

## **Phase 7: Polish & Advanced Features** 🔄
*Objective: Enhance the app for power users.*

1.  **Export Options:** 📅
    *   Export as .txt or .pdf files.
2.  **Language Support:** 📅
    *   Allow users to select from the supported GenAI languages (English, Japanese, Korean).
3.  **Search & Filters:** 📅
    *   Search through transcripts and AI-generated summaries.
4.  **Advanced AI Control:** 📅
    *   Allow users to customize the number of bullets for summaries and notes.

---

## **Technical Standards**
- **State:** Use `StateFlow` in ViewModels, collected with `collectAsStateWithLifecycle`.
- **Threading:** Database ops on `Dispatchers.IO`, Speech UI updates on `Dispatchers.Main`.
- **UI:** Pure Material 3 components.
- **AI Safety:** Ensure all processing remains strictly on-device for privacy.
