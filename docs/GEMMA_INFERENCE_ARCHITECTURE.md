# Gemma Inference Architecture

## Goal

This document describes the current Gemma-based runtime architecture for `vox-transcribe`.

The project direction is now:

- one Gemma runtime stack based on LiteRT-LM
- one selected imported model (`E2B` or `E4B`)
- one Android-side architecture for both live transcription and text processing

## Current Design Constraints

- preserve the existing Compose UI and product identity
- preserve Room note and transcript persistence
- preserve foreground-service ownership for recording sessions
- support manual in-app model import only
- support long-form meetings even though audio inference is clip-based
- avoid reintroducing native/JNI inference complexity

## Current Implementation Snapshot

Implemented core pieces:

- `GemmaModelCatalog`
- `GemmaSettingsRepository`
- `GemmaImportRepository`
- `GemmaRuntimeManager`
- `GemmaAiRepository`
- `GemmaTranscriptionRepository`
- `GemmaModelViewModel`
- `GemmaModelScreen`

The old app-module JNI/CMake runtime path has already been removed.

## Architecture Overview

The current design has five layers:

1. model catalog and persistence
2. model import and install state
3. shared Gemma runtime ownership
4. task-specific repositories
5. preserved service/UI/database pipeline

## Layer 1: Model Catalog

The app owns a small static model catalog in code.

Supported ids:

- `GEMMA_4_E2B`
- `GEMMA_4_E4B`

Current accepted import artifacts:

- `gemma-4-E2B-it.litertlm`
- `gemma-4-E4B-it.litertlm`

The first pass accepts `.litertlm` files only.

## Layer 2: Model Persistence and Import

### Persisted state

The app persists:

- `selectedModelId`

The selected model is durable across app restarts.

### Import subsystem

Current import path:

- user selects a local file or URI
- the app validates the file name against the supported catalog
- the file is copied into app-managed external storage
- install state is exposed back to the UI

The app intentionally does not implement:

- in-app download
- OAuth
- token handling

## Layer 3: Shared Runtime Ownership

`GemmaRuntimeManager` owns LiteRT-LM runtime access.

Responsibilities:

- resolve the selected imported model
- initialize the LiteRT-LM engine
- serialize model access
- create task-specific conversations
- recreate the engine when model or runtime requirements change

Current policy:

- one active model loaded at a time
- one inference job at a time

This is intentionally strict because the app combines foreground recording with post-processing tasks.

## Layer 4: Task-Specific Repositories

### `GemmaAiRepository`

Responsibilities:

- title generation
- summary generation
- meeting notes generation

Current notes:

- text tasks are already working on device
- text inference uses the shared runtime manager
- the current implementation still needs prompt-template centralization

### `GemmaTranscriptionRepository`

Responsibilities:

- use `AudioRecorder`
- collect microphone audio into rolling clips
- keep overlap between adjacent clips
- wrap PCM into WAV for LiteRT-LM audio input
- submit clips sequentially through `GemmaRuntimeManager`
- merge adjacent clip transcripts into finalized segments

Current operating point:

- clip duration: `15s`
- overlap: `3s`
- queue capacity: `4`
- audio input: `16 kHz` mono

Current behavior:

- audio-enabled LiteRT-LM sessions run on CPU for compatibility
- if inference falls behind, clips are dropped and the UI shows a catch-up message
- finalized transcript emission is one-clip delayed to improve overlap merging

## Layer 5: Preserved App Pipeline

These existing pieces remain structurally intact:

- `TranscriptionService`
- `TranscriptionViewModel`
- `NotesRepository`
- Room entities and DAOs
- note list and detail screens

The migration changed implementations behind these seams rather than rewriting the product.

## Long-Form Transcription Design

### Clip scheduler

The scheduler:

- captures raw audio continuously
- emits bounded rolling clips
- keeps overlap from the prior clip
- queues clips for sequential inference
- limits backlog growth

### Output handling

The current merge strategy is:

- keep one pending clip transcript
- use the next clip to trim overlap from the pending clip
- emit the stable portion of the older clip
- emit the final pending clip when recording stops

Current cleanup includes:

- whitespace normalization
- prompt-echo stripping
- normalized word-overlap matching
- character-overlap fallback matching

### Current limitations

The current transcription path is already usable, but still has known weaknesses:

- some duplicate text still appears at clip boundaries
- prompt echoes are reduced but not fully eliminated
- clip cutting is fixed-window based rather than voice-activity-aware

## Partial UI State

The existing UI contract is preserved:

- `partialText` is used for the current in-flight or pending clip text
- finalized transcript entries continue to flow through `transcriptionState`

This allows the current recording UI to keep working without a redesign.

## Text Task Design

Text tasks are implemented as single-turn requests against the selected model.

Current prompt-owned operations:

- executive summary
- meeting notes
- title generation

Prompt centralization is still a remaining cleanup task.

## Data and Settings Changes

Current durable settings are centered on:

- selected Gemma model id

Obsolete settings and controls tied to the old architecture have been removed from the active app path.

## UI Mapping

The old model-management surface has been replaced with a Gemma-focused screen.

Current screen responsibilities:

- show supported models
- show installed/not installed state
- import model files
- select active model
- delete imported model files

The following old controls are intentionally gone:

- backend selection
- native engine loading controls
- `.gguf` import workflow

## Dependency and Platform State

Current platform assumptions:

- `minSdk 31`
- no app-module JNI/CMake/native inference code
- LiteRT-LM runtime dependency in Kotlin code
- DataStore for persisted settings
- WorkManager still available in the dependency set

## Non-Goals

- multiple simultaneously loaded models
- arbitrary custom model compatibility
- token-level streaming ASR semantics
- in-app authenticated downloads

## Remaining Work

The biggest remaining tasks are:

1. centralize prompt templates for text tasks
2. improve transcription boundary handling and prompt-echo suppression
3. improve degraded-mode and busy-state UX
4. complete longer-session device verification
5. compare `E2B` and `E4B` behavior on target devices
