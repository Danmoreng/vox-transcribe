package com.example.voxtranscribe.data.nemotron

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NemotronInstrumentedTest {
    @Test
    fun transcribesReferenceSpeechFasterThanRealtime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.filesDir, "nemotron-onnx")
        val wav = File(context.filesDir, "parakeet-test/speech.wav")
        assertTrue("Expected ONNX model at ${model.absolutePath}", model.isDirectory)
        assertTrue("Expected reference WAV at ${wav.absolutePath}", wav.isFile)

        val samples = readPcm16Wav(wav)
        val started = SystemClock.elapsedRealtimeNanos()
        val transcript = NemotronNative.transcribe(model.absolutePath, samples, 0)
        val wallSeconds = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000_000.0
        val audioSeconds = samples.size / 16_000.0
        val realtimeSpeed = audioSeconds / wallSeconds

        Log.i(
            TAG,
            "audio=${"%.3f".format(audioSeconds)}s wall=${"%.3f".format(wallSeconds)}s " +
                "speed=${"%.2f".format(realtimeSpeed)}x text=$transcript",
        )
        assertTrue("Expected reference transcript, got: $transcript", transcript.contains("observed Phoebe", true))
        assertTrue("Expected faster than realtime, got ${"%.2f".format(realtimeSpeed)}x", realtimeSpeed > 1.0)
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
        const val TAG = "NemotronTest"
    }
}
