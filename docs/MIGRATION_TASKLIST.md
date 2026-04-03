# Migration Task List

## Current Status

This task list now reflects the current branch state rather than the initial migration breakdown.

Completed at this point:

- Gemma manual import and model selection are working
- transcription language guidance is working
- Gemma text processing is working on device
- Gemma live transcription is working on device
- the old app-module Voxtral/JNI/CMake inference path has been removed

Still in progress:

- transcription quality hardening and UX polish
- longer-session verification and device validation

## Phase 0: Research Closure

- [x] Clone and inspect `google-ai-edge/gallery` locally
- [x] Document Gallery stack findings
- [x] Document target Gemma architecture for `vox-transcribe`
- [x] Confirm the exact Gemma 4 artifacts and accepted file formats
- [x] Decide against in-app authentication and authenticated downloads

Decision:

- the app accepts only `.litertlm` files for now
- the current supported artifacts are `gemma-4-E2B-it.litertlm` and `gemma-4-E4B-it.litertlm`
- model download happens outside the app

## Phase 1: Platform and Dependency Reset

- [x] Raise `minSdk` from `26` to `31`
- [x] Remove `externalNativeBuild` from [app/build.gradle.kts](/C:/Development/vox-transcribe/app/build.gradle.kts)
- [x] Remove native build arguments for OpenCL/Vulkan
- [x] Remove JNI/CMake-related files from the app module
- [x] Remove obsolete root-level submodule wiring and directories
- [x] Add LiteRT-LM dependency support
- [x] Add dependencies for manual model import and persisted settings

## Phase 2: Delete Old Runtime Paths

- [x] Remove `VoxtralJni.kt`
- [x] Remove `VoxtralModelManager.kt`
- [x] Remove `VoxtralTranscriptionRepository.kt`
- [x] Remove `AndroidSpeechRecognizerImpl.kt`
- [x] Remove `DynamicTranscriptionRepository.kt`
- [x] Remove engine switching
- [x] Replace old model management UI with Gemma-specific UI
- [x] Remove all remaining obsolete Voxtral/OpenCL/Vulkan repo artifacts

## Phase 3: Introduce Gemma Model Management

- [x] Create `GemmaModelCatalog`
- [x] Create `GemmaSettingsRepository`
- [x] Persist `selectedModelId`
- [x] Persist transcription language preference
- [x] Create `GemmaImportRepository`
- [x] Store models in app-managed external storage
- [x] Add strict import validation for supported Gemma artifacts
- [x] Expose import and install state to UI
- [x] Build a Gemma model management screen for `E2B` and `E4B`

## Phase 4: Introduce Shared Runtime Ownership

- [x] Create `GemmaRuntimeManager`
- [x] Implement model initialization and cleanup
- [x] Serialize runtime access so one inference job owns the model at a time
- [x] Support text and audio conversation setup
- [ ] Improve explicit busy and runtime-state reporting in UI

## Phase 5: Restore Text Tasks on Gemma

- [x] Replace `MediaPipeAiRepository` with `GemmaAiRepository`
- [ ] Centralize summary prompt templates
- [ ] Centralize notes, title, and action-item prompt templates
- [x] Rewire detail screen actions to the new repository
- [x] Generate note titles automatically after recording stops
- [x] Verify text tasks on device with imported Gemma models

## Phase 6: Build Clip-Based Long-Form Transcription

- [x] Create `GemmaTranscriptionRepository`
- [x] Reuse `AudioRecorder` as the microphone capture source
- [x] Add rolling clip scheduler
- [x] Add clip overlap support
- [x] Convert recorded PCM to WAV input for LiteRT-LM audio ingestion
- [x] Queue clips for sequential inference
- [x] Emit finalized transcript segments through `TranscriptionRepository`
- [x] Add first-pass clip-result deduplication and merge heuristics
- [x] Bound queue growth and define overflow behavior
- [x] Force CPU-only audio path for current device compatibility
- [x] Add silence-aware sliding clip cuts with overlap only on forced cuts
- [x] Add realtime-factor and backlog debug metrics to the recording UI
- [x] Add transcription language guidance with `Auto` / fixed-language modes
- [ ] Improve duplicate suppression at clip boundaries
- [ ] Strip remaining prompt echoes more aggressively
- [ ] Tune silence thresholds and model-specific live defaults

## Phase 7: Rewire Existing App Flow

- [x] Keep `TranscriptionService` as the recording-session owner
- [x] Rebind DI so `TranscriptionRepository` points to `GemmaTranscriptionRepository`
- [x] Rebind AI DI so `AiRepository` points to `GemmaAiRepository`
- [x] Remove obsolete backend-selection UI
- [ ] Improve user-visible recording and catch-up status messaging

## Phase 8: Verification

- [x] Build the app successfully without JNI/CMake inference code
- [x] Verify model import, selection, and delete flows
- [x] Verify selected model persists across app restarts
- [x] Verify summary generation on stored transcripts
- [x] Verify live transcription on device
- [x] Verify fixed-language transcription guidance improves output quality
- [ ] Verify long-form recording over at least 5 minutes
- [ ] Verify backlog behavior when inference falls behind real time
- [ ] Verify behavior when app backgrounds during transcription
- [ ] Compare `E2B` and `E4B` behavior on target devices

## Immediate Next Tasks

1. Tighten transcription boundary cleanup and prompt-echo suppression.
2. Tune silence-aware clip thresholds and model-specific transcription defaults.
3. Improve recording-state and degraded-mode UX.
4. Run longer on-device verification for `E2B` and `E4B`.
