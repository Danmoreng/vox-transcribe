package com.example.voxtranscribe.data

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoxtralJni @Inject constructor() {

    companion object {
        init {
            System.loadLibrary("voxtral_jni")
        }
    }

    /**
     * Initializes the Voxtral engine.
     * @param modelPath Absolute path to the .gguf model file.
     * @param threads Number of threads to use (e.g., 4).
     * @param gpuBackend GPU backend to use: 0=none, 1=auto, 2=cuda, 3=metal, 4=vulkan, 5=opencl.
     * @param kvWindow KV cache window size (0 for default).
     * @return A pointer (handle) to the native context, or 0 if initialization failed.
     */
    external fun init(modelPath: String, threads: Int, gpuBackend: Int, kvWindow: Int): Long

    /**
     * Frees the Voxtral engine resources.
     * @param handlePtr The handle returned by init().
     */
    external fun free(handlePtr: Long)

    /**
     * Transcribes audio data.
     * @param handlePtr The handle returned by init().
     * @param audioData Float array of audio samples (PCM 16kHz, mono, normalized -1.0 to 1.0).
     * @param maxTokens Maximum tokens to generate.
     * @return The transcribed text, or empty string on failure.
     */
    external fun transcribe(handlePtr: Long, audioData: FloatArray, maxTokens: Int): String

    /**
     * Creates a new streaming context.
     * @param ctxPtr The context handle returned by init().
     * @param maxBufferSamples Max PCM samples to keep in rolling buffer.
     * @param enableIncrementalEncoder WIP: Enable experimental incremental encoder.
     * @param minDecodeSamples Samples required before triggering a decode (cadence).
     * @param maxTokens Max tokens to generate per decode step.
     * @return A pointer to the streaming context, or 0 if failed.
     */
    external fun streamInit(
        ctxPtr: Long, 
        maxBufferSamples: Int, 
        enableIncrementalEncoder: Boolean,
        minDecodeSamples: Int,
        maxTokens: Int
    ): Long

    /**
     * Frees the streaming context.
     * @param streamPtr The handle returned by streamInit().
     */
    external fun streamFree(streamPtr: Long)

    /**
     * Pushes PCM audio data to the stream buffer.
     * @param streamPtr The handle returned by streamInit().
     * @param audioData Float array of audio samples.
     * @return True if successful.
     */
    external fun streamPush(streamPtr: Long, audioData: FloatArray): Boolean

    /**
     * Attempts to decode if enough audio is buffered.
     * @param streamPtr The handle returned by streamInit().
     * @return The partial transcription string, or empty if no decode happened yet.
     */
    external fun streamDecode(streamPtr: Long): String

    /**
     * Forces a decode of remaining buffered audio.
     * @param streamPtr The handle returned by streamInit().
     * @return The final transcription string.
     */
    external fun streamFlush(streamPtr: Long): String

    /**
     * Retrieves statistics for the given stream.
     * @param streamPtr The handle returned by streamInit().
     * @return A VoxtralStreamStats object or null if failed.
     */
    external fun streamGetStats(streamPtr: Long): VoxtralStreamStats?
}

data class VoxtralStreamStats(
    val decodeCalls: Long,
    val decodeSuccess: Long,
    val skippedCadence: Long,
    val skippedSilence: Long,
    val failures: Long,
    val lastAudioSamples: Int,
    val lastGeneratedTokens: Int,
    val lastTotalMs: Double,
    val lastEncoderMs: Double,
    val lastAdapterMs: Double,
    val lastPrefillMs: Double,
    val lastDecodeMs: Double,
    val lastDecodeMsPerStep: Double,
    val lastRtf: Double
)
