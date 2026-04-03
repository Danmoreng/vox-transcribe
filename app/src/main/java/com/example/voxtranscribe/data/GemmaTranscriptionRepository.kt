package com.example.voxtranscribe.data

import android.util.Log
import com.example.voxtranscribe.data.gemma.GemmaImportRepository
import com.example.voxtranscribe.data.gemma.GemmaRuntimeManager
import com.example.voxtranscribe.data.gemma.GemmaSettingsRepository
import com.example.voxtranscribe.domain.LogEntry
import com.example.voxtranscribe.domain.TranscriptionRepository
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class GemmaTranscriptionRepository @Inject constructor(
    private val audioRecorder: AudioRecorder,
    private val runtimeManager: GemmaRuntimeManager,
    private val settingsRepository: GemmaSettingsRepository,
    private val importRepository: GemmaImportRepository,
) : TranscriptionRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private val isListening = AtomicBoolean(false)

    private val _transcriptionState = MutableSharedFlow<LogEntry>(extraBufferCapacity = 16)
    override val transcriptionState: SharedFlow<LogEntry> = _transcriptionState.asSharedFlow()

    private val _partialText = MutableStateFlow("")
    override val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _isOfflineModel = MutableStateFlow(true)
    override val isOfflineModel: StateFlow<Boolean> = _isOfflineModel.asStateFlow()

    private val _engineState = MutableStateFlow(initialEngineState())
    override val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private var clipChannel: Channel<ByteArray>? = null
    private var captureJob: kotlinx.coroutines.Job? = null
    private var processingJob: kotlinx.coroutines.Job? = null
    private var currentClipBuffer = ByteArrayOutputStream()
    private var currentClipSamples = 0

    init {
        scope.launch {
            combine(
                settingsRepository.selectedModelId,
                importRepository.modelStatuses,
            ) { selectedModelId, statuses ->
                statuses.any { it.spec.id == selectedModelId && it.isImported }
            }.collect { modelReady ->
                if (!isListening.get()) {
                    _engineState.value = if (modelReady) EngineState.Ready else EngineState.Uninitialized
                }
            }
        }
    }

    override fun startListening() {
        if (!isListening.compareAndSet(false, true)) {
            return
        }

        if (!isModelReady()) {
            isListening.set(false)
            _engineState.value = EngineState.Uninitialized
            _partialText.value = "Import and select a Gemma model before recording."
            return
        }

        _engineState.value = EngineState.Ready
        _partialText.value = "Listening for the first Gemma clip..."
        currentClipBuffer = ByteArrayOutputStream()
        currentClipSamples = 0
        clipChannel = Channel(capacity = CLIP_CHANNEL_CAPACITY)

        processingJob = scope.launch {
            processQueuedClips()
        }

        audioRecorder.startRecording()
        captureJob = scope.launch {
            try {
                audioRecorder.audioFlow.collect { floatChunk ->
                    val pcmChunk = floatArrayToPcm16(floatChunk)
                    stateMutex.withLock {
                        currentClipBuffer.write(pcmChunk)
                        currentClipSamples += floatChunk.size
                        if (currentClipSamples >= CLIP_SAMPLES) {
                            enqueueCurrentClipLocked()
                        } else {
                            _partialText.value = clipCaptureStatus(currentClipSamples)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting microphone audio", e)
                _engineState.value = EngineState.Error
                _partialText.value = "Audio capture failed."
            }
        }
    }

    override suspend fun stopListening() {
        if (!isListening.compareAndSet(true, false)) {
            return
        }

        audioRecorder.stopRecording()
        captureJob?.join()
        captureJob = null

        stateMutex.withLock {
            if (currentClipSamples >= MIN_FINAL_CLIP_SAMPLES) {
                enqueueCurrentClipLocked()
            } else {
                currentClipBuffer.reset()
                currentClipSamples = 0
            }
        }

        clipChannel?.close()
        processingJob?.join()
        processingJob = null
        clipChannel = null

        if (_engineState.value != EngineState.Error) {
            _engineState.value = if (isModelReady()) EngineState.Ready else EngineState.Uninitialized
        }
        _partialText.value = ""
    }

    override fun clear() {
        _partialText.value = ""
    }

    override fun cleanup() {
        scope.launch {
            stopListening()
            audioRecorder.release()
        }
    }

    override suspend fun transcribeTestAudio(): String {
        return "Gemma live transcription is enabled through microphone capture."
    }

    private suspend fun processQueuedClips() {
        val channel = clipChannel ?: return
        for (clipBytes in channel) {
            try {
                _partialText.value = "Transcribing ${clipDurationSeconds(clipBytes)}s clip on-device..."
                val transcript = runtimeManager.transcribeAudioClip(
                    audioBytes = pcm16MonoToWav(clipBytes, SAMPLE_RATE),
                    requestedGenerationTokens = TRANSCRIPTION_GENERATION_TOKENS,
                ).cleanTranscript()

                if (transcript.isNotBlank()) {
                    _transcriptionState.emit(
                        LogEntry(
                            text = transcript,
                            timestamp = System.currentTimeMillis(),
                            isFinal = true,
                        )
                    )
                    _partialText.value = transcript
                } else {
                    _partialText.value = "No speech detected in the last clip."
                }

                _engineState.value = EngineState.Ready
            } catch (e: Exception) {
                Log.e(TAG, "Gemma clip transcription failed", e)
                _engineState.value = EngineState.Error
                _partialText.value = e.message ?: "Gemma transcription failed."
            }
        }
    }

    private fun enqueueCurrentClipLocked() {
        val clipBytes = currentClipBuffer.toByteArray()
        if (clipBytes.isNotEmpty()) {
            clipChannel?.trySend(clipBytes)
            _partialText.value = "Queued ${clipDurationSeconds(clipBytes)}s clip for Gemma transcription..."
        }
        currentClipBuffer = ByteArrayOutputStream()
        currentClipSamples = 0
    }

    private fun isModelReady(): Boolean {
        val selectedModelId = settingsRepository.selectedModelId.value ?: return false
        return importRepository.modelStatuses.value.any { it.spec.id == selectedModelId && it.isImported }
    }

    private fun initialEngineState(): EngineState {
        return if (isModelReady()) EngineState.Ready else EngineState.Uninitialized
    }

    private fun floatArrayToPcm16(floatChunk: FloatArray): ByteArray {
        val byteBuffer = ByteBuffer.allocate(floatChunk.size * BYTES_PER_SAMPLE)
            .order(ByteOrder.LITTLE_ENDIAN)
        floatChunk.forEach { sample ->
            val clamped = sample.coerceIn(-1f, 1f)
            byteBuffer.putShort((clamped * Short.MAX_VALUE).toInt().toShort())
        }
        return byteBuffer.array()
    }

    private fun clipCaptureStatus(sampleCount: Int): String {
        val seconds = sampleCount.toFloat() / SAMPLE_RATE
        return "Capturing Gemma clip... ${"%.1f".format(seconds)}s / ${CLIP_DURATION_SECONDS}s"
    }

    private fun clipDurationSeconds(clipBytes: ByteArray): Int {
        return (clipBytes.size / BYTES_PER_SAMPLE) / SAMPLE_RATE
    }

    private fun pcm16MonoToWav(pcmBytes: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val wavFileSize = pcmBytes.size + WAV_HEADER_SIZE
        val header = ByteArray(WAV_HEADER_SIZE)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (wavFileSize and 0xff).toByte()
        header[5] = (wavFileSize shr 8 and 0xff).toByte()
        header[6] = (wavFileSize shr 16 and 0xff).toByte()
        header[7] = (wavFileSize shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte()
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (pcmBytes.size and 0xff).toByte()
        header[41] = (pcmBytes.size shr 8 and 0xff).toByte()
        header[42] = (pcmBytes.size shr 16 and 0xff).toByte()
        header[43] = (pcmBytes.size shr 24 and 0xff).toByte()

        return header + pcmBytes
    }

    private fun String.cleanTranscript(): String {
        return lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("transcript:", ignoreCase = true) }
            .joinToString(separator = "\n")
            .trim()
    }

    private companion object {
        const val TAG = "GemmaTranscription"
        const val SAMPLE_RATE = 16_000
        const val BYTES_PER_SAMPLE = 2
        const val CLIP_DURATION_SECONDS = 10
        const val CLIP_SAMPLES = SAMPLE_RATE * CLIP_DURATION_SECONDS
        const val MIN_FINAL_CLIP_SAMPLES = SAMPLE_RATE
        const val CLIP_CHANNEL_CAPACITY = 4
        const val TRANSCRIPTION_GENERATION_TOKENS = 256
        const val WAV_HEADER_SIZE = 44
    }
}
