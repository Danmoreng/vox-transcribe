package com.example.voxtranscribe.data

import android.util.Log
import com.example.voxtranscribe.data.parakeet.ParakeetImportRepository
import com.example.voxtranscribe.data.parakeet.ParakeetRuntimeManager
import com.example.voxtranscribe.data.parakeet.ParakeetSettingsRepository
import com.example.voxtranscribe.domain.LogEntry
import com.example.voxtranscribe.domain.TranscriptionRepository
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@Singleton
class ParakeetTranscriptionRepository @Inject constructor(
    private val audioRecorder: AudioRecorder,
    private val runtimeManager: ParakeetRuntimeManager,
    private val settingsRepository: ParakeetSettingsRepository,
    private val importRepository: ParakeetImportRepository,
) : TranscriptionRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isListening = AtomicBoolean(false)

    private val _transcriptionState = MutableSharedFlow<LogEntry>(extraBufferCapacity = 16)
    override val transcriptionState: SharedFlow<LogEntry> = _transcriptionState.asSharedFlow()

    private val _partialText = MutableStateFlow("")
    override val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _isOfflineModel = MutableStateFlow(true)
    override val isOfflineModel: StateFlow<Boolean> = _isOfflineModel.asStateFlow()

    private val _engineState = MutableStateFlow(initialEngineState())
    override val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _debugState = MutableStateFlow(TranscriptionDebugState())
    override val debugState: StateFlow<TranscriptionDebugState> = _debugState.asStateFlow()

    private var captureJob: Job? = null
    private var pendingUtterance = ""
    private var totalProcessedAudioSeconds = 0.0
    private var totalProcessingMillis = 0L

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
            _partialText.value = "Import and select the Nemotron streaming GGUF before recording."
            return
        }

        _engineState.value = EngineState.Loading
        _partialText.value = ""
        pendingUtterance = ""
        totalProcessedAudioSeconds = 0.0
        totalProcessingMillis = 0L
        updateDebugState(status = "Loading model", lastClipSeconds = 0.0, lastProcessingMillis = 0L)

        captureJob = scope.launch {
            try {
                runtimeManager.beginStream()
                _engineState.value = EngineState.Ready
                updateDebugState(status = "Starting microphone", queuedClips = 0, droppedClips = 0)

                check(audioRecorder.startRecording()) {
                    "Microphone capture could not start. Check the microphone permission and privacy toggle."
                }
                updateDebugState(status = "Waiting for speech")

                audioRecorder.audioFlow.collect { floatChunk ->
                    if (!containsCapturedAudio(floatChunk)) {
                        updateDebugState(status = "Microphone muted")
                        return@collect
                    }

                    val startedAtNanos = System.nanoTime()
                    val result = runtimeManager.feed(floatChunk)
                    val processingMillis = nanosToMillis(System.nanoTime() - startedAtNanos)
                    recordProcessingMetrics(
                        chunkDurationSeconds = floatChunk.size.toDouble() / SAMPLE_RATE.toDouble(),
                        processingMillis = processingMillis,
                    )

                    val visibleText = sanitizeTranscript(result.text)
                    if (visibleText.isNotBlank()) {
                        pendingUtterance = appendWithSpacing(pendingUtterance, visibleText)
                        _partialText.value = pendingUtterance
                    }

                    if (result.isEndOfUtterance) {
                        emitPendingUtterance()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Parakeet streaming transcription failed", e)
                _engineState.value = EngineState.Error
                _partialText.value = e.message ?: "Parakeet transcription failed."
                updateDebugState(status = "Error")
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

        try {
            val tail = runtimeManager.finalizeStream()
            val visibleTail = sanitizeTranscript(tail)
            if (visibleTail.isNotBlank()) {
                pendingUtterance = appendWithSpacing(pendingUtterance, visibleTail)
            }
            emitPendingUtterance()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize Parakeet stream", e)
            _engineState.value = EngineState.Error
            _partialText.value = e.message ?: "Parakeet finalization failed."
            updateDebugState(status = "Error")
            return
        }

        if (_engineState.value != EngineState.Error) {
            _engineState.value = if (isModelReady()) EngineState.Ready else EngineState.Uninitialized
        }
        _partialText.value = ""
        updateDebugState(status = "Idle")
    }

    override fun clear() {
        _partialText.value = ""
    }

    override fun cleanup() {
        scope.launch {
            stopListening()
            audioRecorder.release()
            runtimeManager.cleanup()
        }
    }

    override suspend fun transcribeTestAudio(): String {
        return "Parakeet realtime streaming is enabled through microphone capture."
    }

    private fun isModelReady(): Boolean {
        val selectedModelId = settingsRepository.selectedModelId.value ?: return false
        return importRepository.modelStatuses.value.any { it.spec.id == selectedModelId && it.isImported }
    }

    private fun initialEngineState(): EngineState {
        return if (isModelReady()) EngineState.Ready else EngineState.Uninitialized
    }

    private suspend fun emitPendingUtterance() {
        val text = pendingUtterance.trim()
        if (text.isBlank()) {
            pendingUtterance = ""
            _partialText.value = ""
            return
        }

        _transcriptionState.emit(
            LogEntry(
                text = text,
                timestamp = System.currentTimeMillis(),
                isFinal = true,
            )
        )
        pendingUtterance = ""
        _partialText.value = ""
    }

    private fun appendWithSpacing(existing: String, next: String): String {
        val trimmedNext = next.trim()
        if (trimmedNext.isBlank()) {
            return existing
        }
        val trimmedExisting = existing.trim()
        return if (trimmedExisting.isBlank()) {
            trimmedNext
        } else {
            "$trimmedExisting $trimmedNext"
        }
    }

    private fun containsCapturedAudio(samples: FloatArray): Boolean {
        if (samples.isEmpty()) return false
        var energy = 0.0
        for (sample in samples) {
            energy += sample * sample
        }
        val rms = kotlin.math.sqrt(energy / samples.size)
        return rms >= MIN_CAPTURE_RMS
    }

    private fun sanitizeTranscript(text: String): String {
        val withoutSpecialTokens = text
            .replace(LOCALE_TOKEN_REGEX, " ")
            .replace(SPECIAL_TOKEN_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()

        if (withoutSpecialTokens.isBlank()) return ""
        if (looksLikeDecoderLoop(withoutSpecialTokens)) {
            Log.w(TAG, "Discarding repetitive decoder output")
            return ""
        }
        return withoutSpecialTokens
    }

    private fun looksLikeDecoderLoop(text: String): Boolean {
        val compact = text.filterNot(Char::isWhitespace)
        if (compact.length < 24) return false
        val distinctRatio = compact.toSet().size.toDouble() / compact.length
        if (distinctRatio < 0.12) return true

        return (1..8).any { unitLength ->
            if (compact.length < unitLength * 6) return@any false
            val unit = compact.take(unitLength)
            compact.chunked(unitLength).take(6).all { it == unit }
        }
    }

    private fun recordProcessingMetrics(
        chunkDurationSeconds: Double,
        processingMillis: Long,
    ) {
        totalProcessedAudioSeconds += chunkDurationSeconds
        totalProcessingMillis += processingMillis
        val averageRealtimeFactor = if (totalProcessedAudioSeconds > 0.0) {
            totalProcessingMillis / 1000.0 / totalProcessedAudioSeconds
        } else {
            null
        }
        val averageSpeedMultiplier = averageRealtimeFactor
            ?.takeIf { it > 0.0 }
            ?.let { 1.0 / it }

        updateDebugState(
            status = "Streaming",
            lastClipSeconds = chunkDurationSeconds,
            lastProcessingMillis = processingMillis,
            averageRealtimeFactor = averageRealtimeFactor,
            averageSpeedMultiplier = averageSpeedMultiplier,
        )
    }

    private fun updateDebugState(
        status: String? = null,
        queuedClips: Int? = null,
        droppedClips: Int? = null,
        lastClipSeconds: Double? = null,
        lastProcessingMillis: Long? = null,
        averageRealtimeFactor: Double? = null,
        averageSpeedMultiplier: Double? = null,
    ) {
        _debugState.value = _debugState.value.copy(
            status = status ?: _debugState.value.status,
            queuedClips = queuedClips ?: _debugState.value.queuedClips,
            droppedClips = droppedClips ?: _debugState.value.droppedClips,
            lastClipSeconds = lastClipSeconds ?: _debugState.value.lastClipSeconds,
            lastProcessingMillis = lastProcessingMillis ?: _debugState.value.lastProcessingMillis,
            averageRealtimeFactor = averageRealtimeFactor ?: _debugState.value.averageRealtimeFactor,
            averageSpeedMultiplier = averageSpeedMultiplier ?: _debugState.value.averageSpeedMultiplier,
        )
    }

    private fun nanosToMillis(nanos: Long): Long {
        return nanos / 1_000_000L
    }

    private companion object {
        const val TAG = "ParakeetTranscription"
        const val SAMPLE_RATE = 16_000
        // Android's AppOps silencing produces digital zeroes. Keep natural
        // pauses so the streaming model can still detect end-of-utterance.
        const val MIN_CAPTURE_RMS = 0.000_01
        val LOCALE_TOKEN_REGEX = Regex("<[a-z]{2}(?:-[A-Z]{2})?>")
        val SPECIAL_TOKEN_REGEX = Regex("<(?:EOU|EOB|blank|pad|unk)>", RegexOption.IGNORE_CASE)
        val WHITESPACE_REGEX = Regex("\\s+")
    }
}
