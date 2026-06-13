package com.example.voxtranscribe.data.parakeet

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParakeetBackendInstrumentedTest {
    @Test
    fun nativeBackendInitializes() {
        val abiVersion = ParakeetNative.abiVersion()
        val backend = ParakeetNative.activeBackendName()

        Log.i(TAG, "Parakeet ABI=$abiVersion backend=$backend")
        assertTrue("Expected Parakeet C ABI v5 or newer", abiVersion >= 5)
        assertTrue("Expected a named Parakeet backend", backend.isNotBlank())
    }

    @Test
    fun importedModelRunsStreamingSilence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stagedModel = File(context.filesDir, "parakeet-test/model.gguf")
        val model = stagedModel.takeIf(File::isFile) ?: File(
            context.getExternalFilesDir(null),
            "parakeet/nemotron-3.5-asr-streaming-0.6b.gguf",
        )
        assertTrue("Expected imported Parakeet model at ${model.absolutePath}", model.isFile)

        Log.i(TAG, "Loading ${model.length()} byte model")
        ParakeetNative.setThreadCount(6)
        val modelHandle = ParakeetNative.loadModel(model.absolutePath)
        assertNotEquals("Model load failed", 0L, modelHandle)

        try {
            Log.i(TAG, "Model loaded; backend=${ParakeetNative.activeBackendName()}")
            val streamHandle = ParakeetNative.beginStream(modelHandle, "")
            assertNotEquals("Stream creation failed", 0L, streamHandle)

            try {
                repeat(20) { second ->
                    Log.i(TAG, "Feeding silence second=${second + 1}")
                    val text = ParakeetNative.feedStream(streamHandle, FloatArray(16_000))
                    assertTrue("Feed failed: ${ParakeetNative.lastError(modelHandle)}", text != null)
                }
                Log.i(TAG, "Streaming silence completed")
            } finally {
                ParakeetNative.freeStream(streamHandle)
            }
        } finally {
            ParakeetNative.freeModel(modelHandle)
        }
    }

    @Test
    fun importedModelTranscribesReferenceSpeech() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val backend = InstrumentationRegistry.getArguments().getString("backend").orEmpty()
        ParakeetNative.configureBackend(backend)

        val model = File(
            context.filesDir,
            "parakeet/nemotron-3.5-asr-streaming-0.6b.gguf",
        )
        val wav = File(context.filesDir, "parakeet-test/speech.wav")
        assertTrue("Expected imported model at ${model.absolutePath}", model.isFile)
        assertTrue("Expected reference WAV at ${wav.absolutePath}", wav.isFile)

        val samples = readPcm16Wav(wav)
        ParakeetNative.setThreadCount(6)
        val modelHandle = ParakeetNative.loadModel(model.absolutePath)
        assertNotEquals("Model load failed", 0L, modelHandle)

        try {
            val streamHandle = ParakeetNative.beginStream(modelHandle, "en")
            assertNotEquals("Stream creation failed", 0L, streamHandle)
            val transcript = StringBuilder()
            try {
                samples.asList().chunked(16_000).forEach { chunk ->
                    val text = ParakeetNative.feedStream(streamHandle, chunk.toFloatArray())
                    assertTrue("Feed failed: ${ParakeetNative.lastError(modelHandle)}", text != null)
                    transcript.append(text)
                }
                transcript.append(ParakeetNative.finalizeStream(streamHandle).orEmpty())
            } finally {
                ParakeetNative.freeStream(streamHandle)
            }
            Log.i(TAG, "Reference backend=${ParakeetNative.activeBackendName()} text=$transcript")
            assertTrue(
                "Expected the reference transcript, got: $transcript",
                transcript.contains("observed Phoebe", ignoreCase = true),
            )
        } finally {
            ParakeetNative.freeModel(modelHandle)
        }
    }

    private fun readPcm16Wav(file: File): FloatArray {
        val bytes = FileInputStream(file).use { it.readBytes() }
        val dataOffset = bytes.indexOfSequence("data".encodeToByteArray()) + 8
        assertTrue("Expected PCM WAV data chunk", dataOffset >= 8)
        val pcm = ByteBuffer.wrap(bytes, dataOffset, bytes.size - dataOffset)
            .order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(pcm.remaining() / 2) { pcm.short / 32768.0f }
    }

    private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
        for (start in 0..size - needle.size) {
            if (needle.indices.all { this[start + it] == needle[it] }) return start
        }
        return -1
    }

    private companion object {
        const val TAG = "ParakeetBackendTest"
    }
}
