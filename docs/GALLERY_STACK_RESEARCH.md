# Google AI Edge Gallery Research

## Purpose

This document captures the relevant findings from investigating `google-ai-edge/gallery` as the reference implementation for the new Gemma-based direction of `vox-transcribe`.

Investigated local reference copy:

- `C:\Development\google-ai-edge-gallery`

This is not a copy plan. It is a source-of-truth summary of what Gallery actually does and which parts are worth reusing.

## Executive Summary

The Gallery Android app confirms that the new direction is technically coherent, but not as a direct backend swap.

High-signal findings:

- Gallery uses `com.google.ai.edge.litertlm` as its actual LLM runtime, not the older MediaPipe `LlmInference` API.
- Its Android baseline is materially higher than the current app: `minSdk 31`.
- Audio Scribe is clip-based, not streaming ASR.
- Audio is captured or imported as bounded audio clips, normalized to mono 16 kHz PCM, wrapped as WAV bytes, and passed into the model as `Content.AudioBytes(...)`.
- Model download and storage are substantially more mature than the current `vox-transcribe` implementation and are worth studying selectively.
- Gallery does not persist the selected model; `vox-transcribe` should.
- Gallery’s authenticated Hugging Face download flow is not acceptable for this app because `vox-transcribe` must not require any authentication.

## Build and Runtime Stack

Gallery is a single Android app module.

Relevant characteristics:

- one `:app` module
- Kotlin + Compose app
- Hilt + DataStore + WorkManager + Protobuf
- AppAuth for Hugging Face OAuth in Gallery, which this app should not adopt
- `litertlm-android` as the primary inference dependency
- Play Services TFLite Java/GPU/support are present, but the main LLM inference path in app code goes through LiteRT LM

Practical conclusion for `vox-transcribe`:

- this migration should replace the current native/JNI + MediaPipe split with a single LiteRT-LM-centered runtime
- `minSdk` should move to `31`
- the app no longer needs NDK/CMake/JNI for inference

## Inference Runtime Shape

Gallery’s effective runtime abstraction is:

- one `Model`
- one LiteRT `Engine`
- one mutable LiteRT `Conversation`

The wrapper surface is conversation-centric:

- initialize model runtime
- create or reset conversation
- run inference asynchronously
- stop response generation
- clean up engine and conversation

Important consequences:

- this is not a stateless `generate(prompt)` API
- it is not a streaming ASR engine
- the runtime is built around a conversation session that can accept text, image, and audio contents

Practical conclusion for `vox-transcribe`:

- summarization and other text tasks can map cleanly onto this runtime
- long-form transcription needs its own repository/service orchestration on top of the model runtime
- we should not let multiple parts of the app compete for the same model instance without explicit arbitration

## Audio Handling in Gallery

Gallery’s audio path is bounded and message-based.

Observed behavior:

- records audio with `AudioRecord`
- mono, `16 kHz`, PCM `16-bit`
- clip duration is hard-capped at `30 seconds`
- imported audio is normalized to the same format
- raw PCM is wrapped into WAV bytes before inference
- the model receives the clip as audio content in a single inference request

What it is not:

- not continuous decoding
- not an always-on speech recognizer
- not microphone-frame-by-frame streaming inference

Practical conclusion for `vox-transcribe`:

- reuse the app’s existing `AudioRecorder` and its 16 kHz mono pipeline
- build a rolling clip queue on top of it
- submit sequential bounded clips to Gemma
- merge clip outputs into transcript segments

## Audio Scribe vs Long-Form Transcription

Gallery’s Audio Scribe is a product demo for bounded audio input.

This differs from `vox-transcribe` in an important way:

- Gallery assumes one clip per inference interaction
- `vox-transcribe` needs continuous meeting capture over long sessions

So the correct migration interpretation is:

- reuse Gallery’s clip contract
- do not reuse Gallery’s one-shot task model as the final architecture

`vox-transcribe` still needs:

- background service ownership
- rolling clip creation
- inference queueing
- overlap and dedup logic
- persistence of finalized segments to Room

## Model Management and Downloads

Gallery’s model management is one of the most reusable parts.

Observed behavior:

- model metadata originates from an allowlist
- models are downloaded into app-private `externalFilesDir`
- downloads are driven by WorkManager
- partially downloaded files use `.tmp`
- resume is attempted with `Range` requests
- optional unzip is supported
- Hugging Face gated models are handled via OAuth/AppAuth and bearer tokens
- installed state is inferred from files on disk plus pending partials

Important gap in Gallery:

- selected model is not persisted as durable app state

Practical conclusion for `vox-transcribe`:

- reuse Gallery’s model metadata ideas and `externalFilesDir` storage layout conceptually
- keep the model list intentionally tiny at first: Gemma 4 `E2B` and `E4B`
- persist `selectedModelId` explicitly
- do not adopt Gallery’s authenticated download flow
- prefer manual import and local validation instead

## Allowlist and Task Mapping

Gallery uses a generic allowlist and task-routing system.

That is more general than this app needs.

For `vox-transcribe`, the minimal useful subset is:

- fixed supported model catalog with exactly two entries
- display name
- remote URL / file name / size / version
- RAM requirement
- capability flags:
  - text
  - audio

We do not need in the first pass:

- broad task-to-model routing
- benchmark metadata
- imported custom models
- remote allowlist versioning
- promo and marketplace infrastructure

## What We Should Reuse

- LiteRT-LM `Engine` / `Conversation` runtime shape
- model file storage in `externalFilesDir`
- bounded audio clip contract
- bounded audio clip contract
- audio normalization assumptions: mono 16 kHz PCM

## What We Should Not Reuse Verbatim

- one-shot Audio Scribe UX
- broad allowlist and custom-model framework
- in-memory-only selected-model handling
- Hugging Face OAuth or any authenticated in-app downloader
- Gallery’s overall task sandbox product structure

## Concrete Decisions for `vox-transcribe`

Based on this investigation, the migration should assume:

- `minSdk 31`
- LiteRT-LM as the new inference runtime
- one selected Gemma model at a time
- selected model persisted locally
- manual model acquisition outside the app
- manual model import inside the app
- clip-based transcription loop for long-form sessions
- Room/service/UI pipeline preserved from the existing app

## Open Questions

These still need resolution during implementation spikes:

- exact model file extension and packaging expected for the Gemma 4 `E2B` and `E4B` artifacts we will support
- exact import validation rules for accepted files
- acceptable clip size and overlap on realistic devices for long-form meetings
- concurrency policy:
  - whether transcription blocks text summarization while a recording session is active
  - or whether model sessions can be torn down and recreated safely on demand

## Recommended Immediate Follow-Up

1. Finalize the target app architecture around one shared Gemma runtime owner.
2. Define a minimal 2-model catalog for `E2B` and `E4B`.
3. Replace the current model-management flow with a manual import pipeline and strict validation.
4. Remove Voxtral/native build code only after the replacement architecture is documented.
