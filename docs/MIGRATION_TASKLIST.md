# Migration Task List

## Status

This task list reflects the first actionable breakdown after researching Google AI Edge Gallery.

## Phase 0: Research Closure

- [x] Clone and inspect `google-ai-edge/gallery` locally
- [x] Document Gallery stack findings
- [x] Document target Gemma architecture for `vox-transcribe`
- [x] Confirm the exact Gemma 4 artifacts and accepted file formats we will support for manual import
  Decision: first pass accepts `.litertlm` files only, specifically `gemma-4-E2B-it.litertlm` and `gemma-4-E4B-it.litertlm`.
- [x] Decide whether Hugging Face OAuth is required for those artifacts
  Decision: the app will not support in-app authentication or authenticated downloads; model acquisition happens outside the app.

## Phase 1: Platform and Dependency Reset

- [ ] Raise `minSdk` from `26` to `31`
- [ ] Remove `externalNativeBuild` from [app/build.gradle.kts](/C:/Development/vox-transcribe/app/build.gradle.kts)
- [ ] Remove native build arguments for OpenCL/Vulkan
- [ ] Remove JNI/CMake-related files from the app module
- [ ] Remove `external/voxtral` submodule wiring
- [ ] Add the LiteRT-LM dependency set required for Gemma runtime integration
- [ ] Add the dependencies needed for manual model import and persisted settings

## Phase 2: Delete Old Runtime Paths

- [ ] Remove `VoxtralJni.kt`
- [ ] Remove `VoxtralModelManager.kt`
- [ ] Remove `VoxtralTranscriptionRepository.kt`
- [ ] Remove `AndroidSpeechRecognizerImpl.kt`
- [ ] Remove `DynamicTranscriptionRepository.kt`
- [ ] Remove `EngineType` switching
- [ ] Remove `VoxtralModelViewModel.kt`
- [ ] Replace `VoxtralModelScreen.kt` with a Gemma-specific model screen
- [ ] Remove obsolete Voxtral/OpenCL/Vulkan docs from the active product path

## Phase 3: Introduce Gemma Model Management

- [ ] Create `GemmaModelCatalog`
- [ ] Create `GemmaSettingsRepository`
- [ ] Persist `selectedModelId`
- [ ] Add persisted TOU/token state if needed
- [ ] Create `GemmaImportRepository`
- [ ] Store models in `externalFilesDir`
- [ ] Add strict import validation for supported Gemma artifacts
- [ ] Expose install/import state to UI
- [ ] Build a new model management screen for `E2B` / `E4B`

## Phase 4: Introduce Shared Runtime Ownership

- [ ] Create `GemmaRuntimeManager`
- [ ] Implement model initialization and cleanup
- [ ] Implement busy/ready/error state reporting
- [ ] Serialize runtime access so one inference job owns the model at a time
- [ ] Add conversation/session reset behavior where needed

## Phase 5: Restore Text Tasks on Gemma

- [ ] Replace `MediaPipeAiRepository` with `GemmaAiRepository`
- [ ] Centralize summary prompt templates
- [ ] Centralize notes/action-items/title prompt templates
- [ ] Rewire detail screen actions to the new repository
- [ ] Verify text tasks work with both `E2B` and `E4B`

## Phase 6: Build Clip-Based Long-Form Transcription

- [ ] Create `GemmaTranscriptionRepository`
- [ ] Reuse `AudioRecorder` as the microphone capture source
- [ ] Add rolling clip scheduler
- [ ] Add clip overlap support
- [ ] Convert recorded PCM to the model input format expected by LiteRT-LM
- [ ] Queue clips for sequential inference
- [ ] Emit finalized transcript segments through `TranscriptionRepository`
- [ ] Add clip-result deduplication/merge heuristics
- [ ] Bound queue growth and define overflow behavior

## Phase 7: Rewire Existing App Flow

- [ ] Keep `TranscriptionService` as the recording-session owner
- [ ] Rebind DI so `TranscriptionRepository` points to `GemmaTranscriptionRepository`
- [ ] Rebind AI DI so `AiRepository` points to `GemmaAiRepository`
- [ ] Update status surfaces and error messaging
- [ ] Remove obsolete backend-selection UI

## Phase 8: Verification

- [ ] Build the app successfully without JNI/CMake inference code
- [ ] Verify model import/install/delete flows
- [ ] Verify selected model persists across app restarts
- [ ] Verify summary generation on stored transcripts
- [ ] Verify long-form recording over at least 5 minutes
- [ ] Verify backlog behavior when inference falls behind real time
- [ ] Verify behavior when app backgrounds during transcription

## Immediate Next Tasks

These are the next concrete implementation tasks from here:

1. Confirm the exact supported Gemma 4 artifacts and accepted import formats.
2. Update Gradle and Android platform assumptions for `minSdk 31` and LiteRT-LM.
3. Implement the new model catalog/settings/import layer before deleting Voxtral classes.
