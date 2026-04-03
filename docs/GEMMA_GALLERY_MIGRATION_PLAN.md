# Gemma Gallery Migration Plan

## Purpose

This document defines the development plan for pivoting `vox-transcribe` away from the current `voxtral.cpp`-based transcription stack and toward a unified Google AI Edge stack based on the approach used by `google-ai-edge/gallery`.

The new direction is:

- keep the existing Android app product shape, navigation, screens, and local-first UX
- remove all `voxtral` / JNI / CMake / ggml / native inference code
- standardize on Gemma 4 on-device models for both transcription and text tasks
- let the user choose one installed Gemma model variant (`E2B` or `E4B`) and use that same model for:
  - clip-based audio transcription
  - summarization
  - additional text processing tasks
- add in-app model import and management inspired by Google AI Edge Gallery, but without any in-app authentication or downloader

This is a hard-cut migration branch, not an incremental dual-stack rollout.

## Confirmed Product Decisions

- Product goal remains a long-form offline meeting assistant.
- Android support baseline can be raised to align with the new inference stack.
- `voxtral` is removed completely rather than retained as a fallback.
- The app must not require any authentication, whether hardcoded or user-provided.
- Model acquisition should happen outside the app, followed by manual import into the app.
- A single selected Gemma model should serve both audio and text workflows.
- Existing Android UI direction should be preserved.
- Long-form transcription should be achieved by repeatedly processing bounded audio clips, similar in product behavior to the earlier Android speech-recognition flow.

## Why This Pivot Makes Sense

The previous architecture split transcription and summarization across different inference systems:

- `voxtral.cpp` via JNI for speech-to-text
- MediaPipe / ML Kit for text generation and summarization

That split increases maintenance cost, testing surface, app complexity, and model-management burden. The new direction collapses those responsibilities into one model family and one on-device inference ecosystem.

Expected benefits:

- one model family instead of multiple heterogeneous stacks
- one model-selection UI and import flow
- removal of native C++ build and Android NDK maintenance burden
- tighter integration between transcription and post-processing
- easier experimentation with future Google AI Edge model releases

Expected tradeoffs:

- transcription is no longer true continuous streaming inference
- transcription quality and latency depend on clip scheduling, prompt design, and merge logic
- minimum Android version and device capability requirements will increase
- model packaging, import validation, and lifecycle management become central product concerns

## Current Reusable App Assets

These parts of the existing app should be preserved and adapted rather than rewritten:

- Compose UI, app visual style, navigation graph, and existing screen structure
- Room database and note/segment persistence
- foreground `TranscriptionService`
- `TranscriptionRepository` abstraction and UI-facing state flows
- `TranscriptionViewModel` accumulation and session timing logic
- `AudioRecorder` microphone capture path
- note detail screens and existing summarization entry points

These parts should be removed:

- `external/voxtral` and related submodule wiring
- JNI bridge and native CMake build
- `VoxtralJni`, `VoxtralModelManager`, `VoxtralTranscriptionRepository`, and related UI
- engine-switching code that toggles between Voxtral and Android speech recognizer
- Voxtral-specific docs, status markers, and debug surfaces

## External Reference Architecture

The target direction should be informed by Google AI Edge Gallery rather than copied blindly.

Relevant Gallery characteristics to study and reuse conceptually:

- LiteRT-based on-device inference stack
- in-app model browsing, import, and local management
- support for Gemma 4 family models
- unified model execution environment for multiple task types
- Android baseline more aligned with modern on-device GenAI support

We should study:

- dependency set and runtime stack
- model file format and runtime packaging expectations
- import flow and exact validation rules for supported model artifacts
- session lifecycle and memory management for loaded models
- how audio scribe mode is modeled in the app and how task routing is represented

We should not assume Gallery architecture maps 1:1 onto this app. This app has a different product shape:

- persistent note-taking instead of general-purpose sandbox usage
- long-form recording sessions instead of isolated demos
- a narrower, more opinionated UX

## High-Level Target Architecture

### 1. Unified inference layer

Introduce a new inference subsystem centered around Gemma and LiteRT:

- `GemmaModelManager`
- `GemmaInferenceRepository` or split repositories by concern behind one shared model runtime
- `GemmaTranscriptionRepository`
- `GemmaTextProcessingRepository`

The implementation must support:

- lazy model loading
- explicit model selection between `E2B` and `E4B`
- model readiness and failure states
- cancellation and cleanup across service and UI boundaries

### 2. Clip-based transcription pipeline

Replace streaming Voxtral inference with a rolling clip pipeline:

1. capture microphone audio continuously with `AudioRecorder`
2. accumulate PCM into bounded windows suitable for Gemma audio transcription
3. submit clips for inference sequentially
4. convert model responses into finalized transcript segments
5. merge segments into note history without duplicating overlapping content
6. surface in-progress state and latest partial/final text to the UI

Design constraints:

- target window size should start with 30 seconds or lower if model/runtime behavior requires it
- overlap between windows should be evaluated to reduce sentence-boundary truncation
- the system must avoid unbounded queue growth on slower devices
- transcription must continue reliably during long recordings

### 3. Unified text task pipeline

Use the same selected Gemma model for:

- meeting summary generation
- extraction of action items
- general text transformations already represented in the product

This requires:

- prompt templates per task
- configurable generation parameters if needed
- timeout and cancellation support
- consistent error propagation to the UI

### 4. Model management UX

Add an in-app model management flow modeled after Gallery but adapted to this product:

- discover or present supported models
- show installed/imported/currently selected model
- allow manual import, replace, reimport, and delete
- validate model compatibility before selection
- surface storage and device requirement warnings

The app should remain usable without a model installed, but recording/inference actions must be gated clearly.

## Migration Phases

## Phase 0: Research and Technical Spike

Goal:

- understand the exact Android-side inference APIs and model lifecycle used by Gallery

Tasks:

- inspect Gallery Android modules, dependencies, and runtime layers
- identify the minimum dependency subset needed for this app
- verify how Gemma 4 `E2B` and `E4B` are packaged and selected
- verify expected model file sources and accepted import formats
- verify how audio input must be prepared for Gemma transcription
- verify runtime constraints:
  - min SDK
  - RAM profile
  - GPU / accelerator assumptions
  - first-load latency
  - model-switching cost

Deliverables:

- architecture notes in docs
- dependency list
- runtime constraints table
- chosen integration strategy for manual import and inference sessions

Exit criteria:

- no major uncertainty remains around model loading, task invocation, or audio input shape

## Phase 1: Hard Cut and Build-System Cleanup

Goal:

- remove Voxtral and native build dependencies cleanly

Tasks:

- remove `external/voxtral` and related submodule config
- remove `external/vulkan-headers`, OpenCL-related dependencies if no longer needed
- delete JNI and CMake integration from the app module
- remove native build arguments from Gradle
- remove Voxtral-specific Kotlin classes and screens
- remove engine-switching logic from DI and repositories
- rename or refactor classes whose naming is now misleading

Deliverables:

- project builds without NDK/JNI/CMake
- no references to Voxtral remain in runtime code

Exit criteria:

- repository compiles with only the new Android-side inference stack

## Phase 2: Dependency and Platform Realignment

Goal:

- align the app with the new Google AI Edge dependency model

Tasks:

- raise `minSdk` and related Android requirements
- add required LiteRT / Google AI Edge dependencies
- remove obsolete MediaPipe / ML Kit dependencies that are no longer part of the target stack
- update repository and plugin versions as needed
- add only the app configuration required for manual import and persisted selection state

Deliverables:

- stable Gradle sync
- debug build starts on supported devices

Exit criteria:

- dependency graph is coherent and ready for feature work

## Phase 3: New Model Management Layer

Goal:

- support import, installation state, and selection of Gemma models inside the app

Tasks:

- define app-level model metadata for `E2B` and `E4B`
- implement model state persistence
- implement manual import workflow
- implement integrity and compatibility checks
- implement model selection UX
- expose model readiness state to the rest of the app

Deliverables:

- users can import and select a supported model in-app

Exit criteria:

- selected model survives app restart and can be loaded on demand

## Phase 4: Gemma Text Processing Integration

Goal:

- restore summarization and text-processing features using Gemma

Tasks:

- implement inference session wrapper for text tasks
- port existing summary generation flows to the new runtime
- define prompt templates for:
  - concise summary
  - action items
  - optional follow-up text transforms
- update screens and view models to use the new repository

Deliverables:

- note detail screen can generate summaries and action items with the selected Gemma model

Exit criteria:

- text generation works without the previous MediaPipe / ML Kit integration path

## Phase 5: Gemma Audio Transcription Integration

Goal:

- restore live meeting transcription using bounded clip inference

Tasks:

- adapt `AudioRecorder` output into transcription clip buffers
- define clip size, overlap strategy, and processing cadence
- implement sequential clip inference queue
- implement segment deduplication and merge heuristics
- emit partial and final transcript states into the existing UI/service pipeline
- ensure `TranscriptionService` persists finalized segments reliably

Key design questions to resolve during implementation:

- Do we emit one final segment per clip or smaller merged segments inside a clip?
- How much overlap is required to preserve sentence continuity?
- Do we keep a rolling prompt context from the last segment to improve continuity?
- How do we backpressure when inference is slower than real-time?

Deliverables:

- long-form recording works through repeated Gemma clip processing

Exit criteria:

- a recording longer than several minutes produces coherent stored transcript segments without service instability

## Phase 6: UX Cleanup and Product Fit

Goal:

- make the new direction feel intentional rather than like a backend swap

Tasks:

- remove Voxtral naming throughout the app
- rename settings and screens to reflect Gemma model management
- update onboarding and empty states
- surface model requirements, compatibility, and storage usage clearly
- review recording UX so clip-based transcription behavior is communicated appropriately

Deliverables:

- app language and settings reflect the new architecture

Exit criteria:

- no user-facing references to removed technology remain

## Phase 7: Verification, Performance, and Release Readiness

Goal:

- verify the app is reliable enough for the new direction

Tasks:

- validate model import, replacement, and deletion behavior
- test model switching between `E2B` and `E4B`
- test long recording sessions across supported devices
- evaluate transcription latency, backlog growth, and battery impact
- evaluate summary quality on realistic meeting transcripts
- verify process recovery after app backgrounding and service restarts
- update docs, screenshots, and release notes

Deliverables:

- migration branch is ready for feature hardening or merge preparation

Exit criteria:

- agreed test matrix passes and known limitations are documented

## Work Breakdown by Area

### App configuration

- Android version baseline changes
- Gradle dependency updates
- manifest and permission review
- background service compatibility review

### Domain and repositories

- replace Voxtral-specific repositories with Gemma equivalents
- keep `TranscriptionRepository` if it still maps cleanly
- add model-management state and events
- add task-level inference APIs for text workflows

### Data layer

- retain Room schema unless transcript segmentation needs refinement
- add model metadata persistence
- add model import metadata and selection persistence

### UI

- preserve current overall style
- replace model-management screen
- update debug or status surfaces
- keep note list, detail view, and recording flow shape

### Services

- preserve foreground service model
- ensure long-running clip scheduling works off the UI thread
- ensure stop/cancel behavior is deterministic

### Documentation

- replace setup instructions
- add supported-device guidance
- add model acquisition, import, and storage guidance
- remove outdated Voxtral/Vulkan/OpenCL docs from the active product path

## Major Risks and Mitigations

### Risk 1: Gallery stack is not directly portable

Mitigation:

- do an explicit research spike before coding core assumptions
- isolate the inference adapter layer so runtime choices can change later

### Risk 2: Clip-based transcription is slower than real-time

Mitigation:

- benchmark `E2B` and `E4B` separately
- implement queue limits and backlog telemetry
- allow shorter clips if throughput requires it

### Risk 3: Transcript quality degrades at clip boundaries

Mitigation:

- evaluate overlap windows
- apply lightweight deduplication and stitch heuristics
- test with realistic conversational audio instead of synthetic samples only

### Risk 4: Model acquisition and import UX becomes too complex

Mitigation:

- start with two explicitly supported model variants only
- provide exact external download guidance and strict import validation

### Risk 5: Device support becomes too narrow

Mitigation:

- document supported device classes clearly
- fail fast with explicit compatibility messaging

### Risk 6: One model for both transcription and summarization may be a compromise

Mitigation:

- benchmark both supported variants for both task types
- keep repository boundaries clean so task-specific models remain possible later if needed

## Testing Strategy

### Unit-level

- model metadata/state management
- prompt construction
- clip scheduling logic
- overlap merge and dedup logic

### Integration-level

- model import lifecycle
- model selection persistence
- transcription repository to service to DB pipeline
- summary generation from saved transcript text

### Device-level

- first model import
- first load latency
- transcription over 5, 15, and 30 minute sessions
- stop/resume behavior
- app backgrounding during recording
- low storage conditions

## Acceptance Criteria for the Branch

This migration branch is considered successful when all of the following are true:

- the project contains no Voxtral runtime dependency
- the app builds without native JNI/CMake inference code
- the app can import and select Gemma `E2B` or `E4B` in-app
- the selected model is used for both transcription and text tasks
- long-form meeting recording works through repeated bounded audio inference
- transcript segments are persisted to Room through the existing service-based workflow
- summary and action-item generation work from stored meeting text
- the user-facing UI remains recognizably the existing app, not a Gallery clone

## Non-Goals for the First Migration Pass

- supporting multiple unrelated model families
- preserving backward compatibility with old Voxtral models
- building a generic Gallery-style sandbox app
- adding cloud fallback
- solving every performance issue before first end-to-end integration exists

## Recommended Implementation Order

1. Complete Gallery/Gemma research notes and lock technical assumptions.
2. Remove Voxtral/native build system and stabilize the project.
3. Add new dependencies and platform baseline updates.
4. Implement model management and install/select flows.
5. Restore text generation tasks first.
6. Implement clip-based transcription second.
7. Tune quality, performance, and UX once end-to-end paths exist.

## Branch Notes

Initial branch for this effort:

- `migration/gemma-gallery`

This document should evolve as research reduces uncertainty, but the branch intent should remain fixed:

- one Gemma-based stack
- no Voxtral
- keep the app product and UI identity
