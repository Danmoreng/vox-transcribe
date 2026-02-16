# VoxNotes - Detailed Development Plan

## **Architectural Vision**
VoxNotes is evolving from a single-screen prototype to a robust, offline-first meeting logger. The architecture follows Clean Architecture principles with Hilt for DI, Room for persistence, and a Foreground Service for mission-critical reliability.

---

## **Phase 1: Dependency Injection & Navigation**
*Objective: Prepare the app for multi-screen navigation and structured dependency management.*

1.  **Hilt Integration:**
    *   Add Hilt dependencies to `libs.versions.toml`.
    *   Annotate `VoxApplication` class with `@HiltAndroidApp`.
    *   Create `DatabaseModule` and `RepositoryModule`.
2.  **Navigation Setup:**
    *   Implement `androidx.navigation:navigation-compose`.
    *   Define `Screen` sealed class: `Home`, `Record`, `Detail(noteId: Long)`.

---

## **Phase 2: Data Persistence (Room)**
*Objective: Ensure no transcription is ever lost.*

1.  **Entities:**
    *   `NoteEntity`: `id`, `title`, `createdAt`, `duration`, `summary`.
    *   `SegmentEntity`: `id`, `noteId` (FK), `text`, `timestamp`, `isFinal`.
2.  **DAOs:**
    *   `NotesDao`: `getAllNotes(): Flow<List<NoteEntity>>`, `getNoteWithSegments(id): Flow<NoteWithSegments>`.
3.  **Repository Refactor:**
    *   Repository should now coordinate between `AndroidSpeechRecognizer` and `NotesDao`.
    *   Every "Final" result from the recognizer must be immediately persisted to Room.

---

## **Phase 3: Background Reliability (Foreground Service)**
*Objective: Support long-running sessions (e.g., 60min meetings) without interruption.*

1.  **TranscriptionService:**
    *   Extend `LifecycleService`.
    *   Manage the `SpeechRecognizer` lifecycle here.
    *   Use `startForeground` with a notification showing live word count or "Listening...".
2.  **The "Forever Loop" Logic:**
    *   Implement `RecognitionListener`.
    *   `onResults`: Save to DB, then call `startListening()` immediately.
    *   `onError`: If code is `7` (Timeout) or `8` (Busy), wait 500ms and restart.
    *   **Silence Management:** Use `AudioManager` to mute the system recognition "beeps" if possible.

---

## **Phase 4: Domain & UI Refinement**
*Objective: Transform the UI into a "Time Machine" log.*

1.  **Rich Models:**
    *   Replace `Flow<String>` with `Flow<List<TranscriptSegment>>`.
2.  **Recording Screen:**
    *   `LazyColumn` with `reverseLayout = true`.
    *   Items: `TimestampedBubble(text, time, isFinal)`.
3.  **Home Screen:**
    *   Dashboard showing recent recordings.
    *   Swipe-to-delete functionality.

---

## **Phase 5: Intelligence (Gemini Nano)**
*Objective: On-device summarization.*

1.  **AI Integration:**
    *   Use Google AI Edge SDK.
    *   Create a `Summarizer` implementation.
    *   Add "Summarize" button to the Note Detail screen.

---

## **Technical Standards**
- **State:** Use `StateFlow` in ViewModels, collected with `collectAsStateWithLifecycle`.
- **Threading:** Database ops on `Dispatchers.IO`, Speech UI updates on `Dispatchers.Main`.
- **UI:** Pure Material 3 components.
- **Testing:** Add `TranscriptionRepositoryTest` using a fake `SpeechRecognizer`.
