# Gemma Inference Architecture

## Goal

Define the target runtime architecture for migrating `vox-transcribe` from:

- `voxtral.cpp` for transcription
- MediaPipe `LlmInference` for text tasks

to:

- one Gemma runtime stack based on LiteRT LM
- one selected model (`E2B` or `E4B`)
- one shared Android-side architecture for both transcription and text processing

## Design Constraints

- preserve the existing Compose UI and product identity
- preserve Room note and transcript persistence
- preserve foreground-service ownership for recording sessions
- support manual in-app model import
- support long-form meetings even though model audio input is bounded
- avoid reintroducing native/JNI inference complexity

## Architecture Overview

The target design has five layers:

1. Model catalog and persistence
2. Model import and install state
3. Shared Gemma runtime ownership
4. Task-specific repositories
5. Existing service/UI/database pipeline

## Layer 1: Model Catalog

Introduce a small model catalog owned by the app.

Suggested type:

- `GemmaModelId`
  - `GEMMA_4_E2B`
  - `GEMMA_4_E4B`

Suggested metadata:

- stable id
- display name
- remote URL
- file name
- version / commit
- size bytes
- min RAM requirement
- supports audio
- supports text

This should live in app code, not in a remote allowlist for the first pass.

## Layer 2: Model Persistence and Download

### Persisted state

Create persistent settings for:

- `selectedModelId`
- per-model import/install state if needed

Unlike Gallery, selected model must be durable.

### Import subsystem

Create:

- `GemmaImportRepository`
- `GemmaModelStore`

Responsibilities:

- import model from a user-selected local file or URI into `externalFilesDir/{normalizedName}/{version}/{fileName}`
- validate file type, metadata, and compatibility
- support replace / reimport / delete
- expose install state as observable app state

The current `ModelDownloadManager` should be removed rather than evolved in place.

## Layer 3: Shared Runtime Ownership

This is the most important architectural decision.

Create one runtime owner:

- `GemmaRuntimeManager`

Responsibilities:

- load the selected model
- own the LiteRT `Engine`
- create and reset LiteRT `Conversation` objects
- serialize access to the runtime
- expose initialization state
- tear down runtime cleanly when model changes or app requests cleanup

Why this layer is needed:

- Gallery is conversation-centric and informal about ownership
- `vox-transcribe` has a foreground service plus UI plus background text tasks
- we need stricter control over runtime access and lifecycle

### Required policy

The first implementation should assume:

- one active model loaded at a time
- one active inference job at a time

That is the simplest correct policy.

If a recording is active:

- transcription owns the runtime
- summary generation should either queue or fail with a clear busy state

This is stricter than Gallery, but more appropriate for a focused meeting app.

## Layer 4: Task-Specific Repositories

### A. `GemmaTranscriptionRepository`

This replaces:

- `VoxtralTranscriptionRepository`
- `AndroidSpeechRecognizerImpl`
- `DynamicTranscriptionRepository`

Responsibilities:

- use `AudioRecorder`
- accumulate microphone audio into bounded clips
- enforce 16 kHz mono PCM clip contract
- optionally add clip overlap
- wrap clip PCM into WAV bytes if required by runtime
- submit clips sequentially to `GemmaRuntimeManager`
- convert model responses into transcript segments
- emit partial and final text through `TranscriptionRepository`

### B. `GemmaAiRepository`

This replaces:

- `MediaPipeAiRepository`

Responsibilities:

- run summary generation
- run meeting notes generation
- run title generation
- share the selected model from `GemmaRuntimeManager`
- own prompt templates and task-specific formatting

## Layer 5: Preserved App Pipeline

These existing pieces should stay structurally intact:

- `TranscriptionService`
- `TranscriptionViewModel`
- `NotesRepository`
- Room entities and DAOs
- note list and detail screens

The migration should change implementations behind these seams, not rewrite the product from scratch.

## Long-Form Transcription Design

## Clip Scheduler

Use `AudioRecorder` as the source of raw audio flow.

Build a scheduler that:

- collects audio continuously
- emits clips at a fixed target duration
- optionally includes overlap from the previous clip
- queues clips for sequential inference
- backpressures when inference falls behind

Suggested initial operating point:

- clip duration: `20s`
- overlap: `3s`
- mono 16 kHz PCM

These are starting values only and must be tuned empirically.

## Output Handling

For each clip:

- send clip to Gemma
- receive model response
- normalize whitespace and formatting
- deduplicate against the trailing text from the previous finalized segment
- store a finalized segment when safe

Two output models are plausible:

- one DB segment per processed clip
- smaller logical segments extracted from the clip response

The first pass should use:

- one finalized segment per processed clip

That is simpler and easier to debug.

## Partial UI State

The UI currently expects:

- `partialText`
- finalized log entries via `transcriptionState`

The transcription repository should provide:

- `partialText` as the latest in-flight clip result or queue-progress message
- `transcriptionState` only for finalized segments

This keeps the existing screens usable without redesign.

## Failure and Backpressure Policy

If inference is slower than recording:

- clip queue must be bounded
- queue overflow must surface an explicit degraded-mode error
- the service must not grow memory without bound

Suggested first-pass rule:

- max queued clips: `2`
- if queue is full:
  - drop oldest pending non-processing clip
  - emit a diagnostic state entry

This is better than unbounded memory growth.

## Text Task Design

Text tasks should be implemented as single-turn requests against the selected model.

Suggested prompt-owned operations:

- executive summary
- meeting notes
- title generation
- action items

Prompt templates should live centrally, not inline inside UI code.

Suggested type:

- `GemmaPromptTemplates`

This makes future tuning easy.

## Data and Settings Changes

Add new durable settings for:

- selected model id
- model install/import progress snapshots if useful
- optional access token
- TOU acceptance

Remove obsolete persisted behavior tied to:

- Voxtral model import
- backend selection
- engine switching

## UI Mapping

Replace the existing model-management screen with a Gemma-focused screen.

The current `VoxtralModelScreen` should evolve into:

- selected model
- installed/not installed state
- import / replace / delete actions
- model requirements
- load / ready / busy state

Do not carry forward:

- CPU / Auto / Vulkan / OpenCL controls
- test-recording controls specific to Voxtral
- `.gguf` import workflow

## Dependency and Platform Changes

Expected changes:

- raise `minSdk` to `31`
- remove CMake/JNI/native build wiring
- remove `libs.mediapipe.genai` and `libs.mlkit.genai.prompt` if fully superseded
- add LiteRT LM centered dependency set
- add WorkManager / AppAuth / DataStore pieces as needed

## Proposed New Types

Suggested first-pass type layout:

- `data/gemma/GemmaModelCatalog.kt`
- `data/gemma/GemmaSettingsRepository.kt`
- `data/gemma/GemmaImportRepository.kt`
- `data/gemma/GemmaRuntimeManager.kt`
- `data/gemma/GemmaTranscriptionRepository.kt`
- `data/gemma/GemmaAiRepository.kt`
- `ui/GemmaModelViewModel.kt`
- `ui/screens/GemmaModelScreen.kt`

## First-Pass Non-Goals

- multiple simultaneously loaded models
- imported arbitrary custom models
- benchmark features
- generic task marketplace behavior
- streaming token-level ASR semantics

## Implementation Order

1. Add settings/model catalog/import architecture.
2. Add `GemmaRuntimeManager`.
3. Migrate text tasks first.
4. Migrate clip-based transcription second.
5. Remove Voxtral/native code once replacement seams compile cleanly.
