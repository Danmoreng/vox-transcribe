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
     * @return A pointer (handle) to the native context, or 0 if initialization failed.
     */
    external fun init(modelPath: String, threads: Int): Long

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
}
