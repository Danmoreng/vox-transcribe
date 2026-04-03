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
import kotlin.math.abs
import java.util.Locale
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

    private val _debugState = MutableStateFlow(TranscriptionDebugState())
    override val debugState: StateFlow<TranscriptionDebugState> = _debugState.asStateFlow()

    private var clipChannel: Channel<ClipRequest>? = null
    private var captureJob: kotlinx.coroutines.Job? = null
    private var processingJob: kotlinx.coroutines.Job? = null
    private var currentClipBuffer = ByteArrayOutputStream()
    private var currentClipSamples = 0
    private var currentCarrySamples = 0
    private var currentSilentSamples = 0
    private var recentTranscriptTail = ""
    private var pendingTranscript = ""
    private var droppedClipCount = 0
    private var queuedClipCount = 0
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
            _partialText.value = "Import and select a Gemma model before recording."
            return
        }

        _engineState.value = EngineState.Ready
        _partialText.value = ""
        currentClipBuffer = ByteArrayOutputStream()
        currentClipSamples = 0
        currentCarrySamples = 0
        currentSilentSamples = 0
        recentTranscriptTail = ""
        pendingTranscript = ""
        droppedClipCount = 0
        queuedClipCount = 0
        totalProcessedAudioSeconds = 0.0
        totalProcessingMillis = 0L
        _debugState.value = TranscriptionDebugState(status = "Recording")
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
                        appendChunkLocked(floatChunk, pcmChunk)
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
            val newAudioSamples = currentClipSamples - currentCarrySamples
            if (newAudioSamples >= MIN_FINAL_NEW_AUDIO_SAMPLES) {
                enqueueCurrentClipLocked(ClipCutReason.FinalFlush)
            } else {
                currentClipBuffer.reset()
                currentClipSamples = 0
                currentCarrySamples = 0
                currentSilentSamples = 0
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
        updateDebugState(status = "Idle", queuedClips = 0)
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
        for (clip in channel) {
            try {
                val clipDurationSeconds = clip.newAudioSeconds
                queuedClipCount = (queuedClipCount - 1).coerceAtLeast(0)
                updateDebugState(
                    status = if (queuedClipCount > 0) "Catching up" else "Processing",
                    queuedClips = queuedClipCount,
                    lastClipSeconds = clipDurationSeconds,
                )
                val startedAtNanos = System.nanoTime()
                val transcript = runtimeManager.transcribeAudioClip(
                    audioBytes = pcm16MonoToWav(clip.pcmBytes, SAMPLE_RATE),
                    previousTranscriptTail = null,
                    requestedGenerationTokens = TRANSCRIPTION_GENERATION_TOKENS,
                ).cleanTranscript("")
                val processingMillis = nanosToMillis(System.nanoTime() - startedAtNanos)
                recordProcessingMetrics(clipDurationSeconds, processingMillis)

                val currentTranscript = trimTrailingEchoAgainstRecentTail(transcript)

                if (pendingTranscript.isBlank()) {
                    if (currentTranscript.isNotBlank()) {
                        if (clip.hasForwardOverlap) {
                            pendingTranscript = currentTranscript
                            _partialText.value = pendingTranscript
                        } else {
                            emitFinalTranscript(currentTranscript)
                            _partialText.value = ""
                        }
                    }
                    updateDebugState(
                        status = if (queuedClipCount > 0) "Catching up" else "Recording",
                        queuedClips = queuedClipCount,
                    )
                    continue
                }

                val finalizedPending = if (clip.hasOverlapFromPrevious && currentTranscript.isNotBlank()) {
                    trimOverlapWithNextTranscript(
                        pending = pendingTranscript,
                        next = currentTranscript,
                    )
                } else {
                    pendingTranscript
                }

                if (finalizedPending.isNotBlank()) {
                    emitFinalTranscript(finalizedPending)
                }

                pendingTranscript = ""
                if (currentTranscript.isNotBlank()) {
                    if (clip.hasForwardOverlap) {
                        pendingTranscript = currentTranscript
                        _partialText.value = pendingTranscript
                    } else {
                        emitFinalTranscript(currentTranscript)
                        _partialText.value = ""
                    }
                } else {
                    _partialText.value = ""
                }
                _engineState.value = EngineState.Ready
                updateDebugState(
                    status = if (queuedClipCount > 0) "Catching up" else "Recording",
                    queuedClips = queuedClipCount,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Gemma clip transcription failed", e)
                _engineState.value = EngineState.Error
                _partialText.value = e.message ?: "Gemma transcription failed."
                updateDebugState(status = "Error", queuedClips = queuedClipCount)
            }
        }

        if (pendingTranscript.isNotBlank()) {
            emitFinalTranscript(pendingTranscript)
            pendingTranscript = ""
        }
        _partialText.value = ""
    }

    private fun appendChunkLocked(floatChunk: FloatArray, pcmChunk: ByteArray) {
        currentClipBuffer.write(pcmChunk)
        currentClipSamples += floatChunk.size
        currentSilentSamples = updateSilentRunSamples(floatChunk, currentSilentSamples)

        val newAudioSamples = currentClipSamples - currentCarrySamples
        if (newAudioSamples >= MIN_CLIP_SAMPLES && currentSilentSamples >= SILENCE_HOLD_SAMPLES) {
            enqueueCurrentClipLocked(ClipCutReason.Silence)
            return
        }

        if (currentClipSamples >= MAX_CLIP_SAMPLES) {
            enqueueCurrentClipLocked(ClipCutReason.MaxDuration)
        }
    }

    private fun enqueueCurrentClipLocked(cutReason: ClipCutReason) {
        val clipBytes = currentClipBuffer.toByteArray()
        if (clipBytes.isNotEmpty()) {
            val hasForwardOverlap = cutReason == ClipCutReason.MaxDuration
            val clipRequest = ClipRequest(
                pcmBytes = clipBytes,
                newAudioSamples = (currentClipSamples - currentCarrySamples).coerceAtLeast(0),
                hasOverlapFromPrevious = currentCarrySamples > 0,
                hasForwardOverlap = hasForwardOverlap,
                cutReason = cutReason,
            )
            val result = clipChannel?.trySend(clipRequest)
            if (result?.isFailure == true) {
                droppedClipCount += 1
                Log.w(TAG, "Dropping clip due to backlog. droppedClipCount=$droppedClipCount")
                _partialText.value = "Transcription is behind. Skipping audio to catch up."
                updateDebugState(status = "Catching up", droppedClips = droppedClipCount)
            } else {
                droppedClipCount = 0
                queuedClipCount += 1
                updateDebugState(
                    status = if (queuedClipCount > 1) "Catching up" else "Recording",
                    queuedClips = queuedClipCount,
                    droppedClips = droppedClipCount,
                )
            }
        }

        currentClipBuffer = ByteArrayOutputStream()
        if (cutReason == ClipCutReason.MaxDuration) {
            val overlapBytes = clipBytes.takeLast(FORCED_OVERLAP_SAMPLES * BYTES_PER_SAMPLE).toByteArray()
            if (overlapBytes.isNotEmpty()) {
                currentClipBuffer.write(overlapBytes)
            }
            currentCarrySamples = overlapBytes.size / BYTES_PER_SAMPLE
        } else {
            currentCarrySamples = 0
        }
        currentClipSamples = currentCarrySamples
        currentSilentSamples = 0
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

    private fun clipDurationSeconds(clipBytes: ByteArray): Double {
        return clipBytes.size.toDouble() / BYTES_PER_SAMPLE.toDouble() / SAMPLE_RATE.toDouble()
    }

    private fun updateSilentRunSamples(floatChunk: FloatArray, startingSilentRunSamples: Int): Int {
        var silentRunSamples = startingSilentRunSamples
        for (sample in floatChunk) {
            silentRunSamples = if (abs(sample) <= SILENCE_AMPLITUDE_THRESHOLD) {
                silentRunSamples + 1
            } else {
                0
            }
        }
        return silentRunSamples
    }

    private fun recordProcessingMetrics(
        clipDurationSeconds: Double,
        processingMillis: Long,
    ) {
        totalProcessedAudioSeconds += clipDurationSeconds
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
            queuedClips = queuedClipCount,
            lastClipSeconds = clipDurationSeconds,
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

    private fun String.cleanTranscript(previousTranscriptTail: String): String {
        val cleaned = lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("transcript:", ignoreCase = true) }
            .filterNot { it.equals(AUDIO_PROMPT_NO_CONTEXT, ignoreCase = true) }
            .filterNot { it.startsWith("Continue after this confirmed context", ignoreCase = true) }
            .filterNot { it.startsWith("Return only the new transcript", ignoreCase = true) }
            .filterNot { it.startsWith("Return only the transcript", ignoreCase = true) }
            .filterNot { it.startsWith("Transcribe only the spoken audio", ignoreCase = true) }
            .joinToString(separator = "\n")
            .replace(AUDIO_PROMPT_NO_CONTEXT, "", ignoreCase = true)
            .replace("Return only the transcript for this audio clip.", "", ignoreCase = true)
            .trim()

        return if (
            previousTranscriptTail.isNotBlank() &&
            cleaned.startsWith(previousTranscriptTail, ignoreCase = true)
        ) {
            cleaned.removePrefix(previousTranscriptTail).trim()
        } else {
            cleaned
        }
    }

    private fun trimTrailingEchoAgainstRecentTail(transcript: String): String {
        if (transcript.isBlank() || recentTranscriptTail.isBlank()) {
            return transcript
        }

        val trimmedLeading = trimOverlapPrefix(transcript, recentTranscriptTail)
        return trimOverlapSuffix(trimmedLeading, recentTranscriptTail)
    }

    private fun trimOverlapWithNextTranscript(pending: String, next: String): String {
        if (pending.isBlank() || next.isBlank()) {
            return pending
        }

        val trimmedByWords = trimOverlapSuffix(pending, next)
        return if (trimmedByWords == pending) {
            trimCharacterSuffixOverlap(pending, next)
        } else {
            trimmedByWords
        }
    }

    private fun trimOverlapPrefix(transcript: String, reference: String): String {
        val referenceTokens = tokenizeWords(reference)
        val currentTokens = tokenizeWords(transcript)
        if (referenceTokens.isEmpty() || currentTokens.isEmpty()) {
            return transcript
        }

        val matchedTokens = findWordOverlapLength(
            suffixTokens = referenceTokens,
            prefixTokens = currentTokens,
        )

        if (matchedTokens == 0) {
            return trimCharacterPrefixOverlap(transcript, reference)
        }

        return currentTokens.drop(matchedTokens).joinToString(" ").trim()
    }

    private fun trimOverlapSuffix(transcript: String, reference: String): String {
        val currentTokens = tokenizeWords(transcript)
        val referenceTokens = tokenizeWords(reference)
        if (currentTokens.isEmpty() || referenceTokens.isEmpty()) {
            return transcript
        }

        val matchedTokens = findWordOverlapLength(
            suffixTokens = currentTokens,
            prefixTokens = referenceTokens,
        )

        if (matchedTokens == 0) {
            return transcript
        }

        return currentTokens.dropLast(matchedTokens).joinToString(" ").trim()
    }

    private fun findWordOverlapLength(
        suffixTokens: List<String>,
        prefixTokens: List<String>,
    ): Int {
        val maxOverlap = minOf(MAX_OVERLAP_WORDS, suffixTokens.size, prefixTokens.size)
        for (candidate in maxOverlap downTo 1) {
            val suffix = suffixTokens.takeLast(candidate).map { normalizeToken(it) }
            val prefix = prefixTokens.take(candidate).map { normalizeToken(it) }
            val similarities = suffix.zip(prefix).map { (left, right) ->
                tokenSimilarity(left, right)
            }
            val strongMatches = similarities.count { it >= STRONG_TOKEN_MATCH_SCORE }
            val averageScore = similarities.average()
            if (
                averageScore >= MIN_OVERLAP_AVERAGE_SCORE &&
                strongMatches >= maxOf(1, candidate / 2)
            ) {
                return candidate
            }
        }
        return 0
    }

    private fun tokenSimilarity(left: String, right: String): Double {
        if (left.isBlank() || right.isBlank()) {
            return 0.0
        }
        if (left == right) {
            return 1.0
        }
        if (left.length >= 4 && right.length >= 4 && (left.contains(right) || right.contains(left))) {
            return 0.85
        }
        val commonPrefix = left.commonPrefixWith(right)
        val minimumLength = minOf(left.length, right.length)
        if (minimumLength >= 4 && commonPrefix.length.toDouble() / minimumLength.toDouble() >= 0.8) {
            return 0.75
        }
        return 0.0
    }

    private fun trimCharacterPrefixOverlap(transcript: String, reference: String): String {
        val previousMap = buildNormalizedTextMap(reference)
        val currentMap = buildNormalizedTextMap(transcript)
        val previousNormalized = previousMap.normalized
        val currentNormalized = currentMap.normalized

        if (previousNormalized.isBlank() || currentNormalized.isBlank()) {
            return transcript
        }

        val maxOverlap = minOf(MAX_CHARACTER_OVERLAP, previousNormalized.length, currentNormalized.length)
        for (candidate in maxOverlap downTo MIN_CHARACTER_OVERLAP) {
            if (previousNormalized.endsWith(currentNormalized.take(candidate))) {
                val rawCutIndexExclusive = currentMap.rawIndexByNormalizedPosition[candidate - 1] + 1
                return transcript.substring(rawCutIndexExclusive).trimStart()
            }
        }

        return transcript
    }

    private fun trimCharacterSuffixOverlap(transcript: String, reference: String): String {
        val currentMap = buildNormalizedTextMap(transcript)
        val referenceMap = buildNormalizedTextMap(reference)
        val currentNormalized = currentMap.normalized
        val referenceNormalized = referenceMap.normalized

        if (currentNormalized.isBlank() || referenceNormalized.isBlank()) {
            return transcript
        }

        val maxOverlap = minOf(MAX_CHARACTER_OVERLAP, currentNormalized.length, referenceNormalized.length)
        for (candidate in maxOverlap downTo MIN_CHARACTER_OVERLAP) {
            if (currentNormalized.endsWith(referenceNormalized.take(candidate))) {
                val overlapStartNormIndex = currentNormalized.length - candidate
                if (overlapStartNormIndex <= 0) {
                    return ""
                }
                val rawCutIndexExclusive = currentMap.rawIndexByNormalizedPosition[overlapStartNormIndex - 1] + 1
                return transcript.substring(0, rawCutIndexExclusive).trimEnd()
            }
        }

        return transcript
    }

    private fun appendToRecentTail(existing: String, next: String): String {
        val combined = tokenizeWords(
            listOf(existing, next)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        )
        return combined.takeLast(RECENT_TAIL_WORDS).joinToString(" ")
    }

    private fun tokenizeWords(text: String): List<String> {
        return text.split(WORD_SPLIT_REGEX).filter { it.isNotBlank() }
    }

    private fun normalizeToken(token: String): String {
        return token
            .lowercase(Locale.ROOT)
            .trim { !it.isLetterOrDigit() }
    }

    private suspend fun emitFinalTranscript(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            return
        }
        _transcriptionState.emit(
            LogEntry(
                text = trimmed,
                timestamp = System.currentTimeMillis(),
                isFinal = true,
            )
        )
        recentTranscriptTail = appendToRecentTail(recentTranscriptTail, trimmed)
    }

    private fun buildNormalizedTextMap(text: String): NormalizedTextMap {
        val normalizedBuilder = StringBuilder()
        val rawIndexMap = mutableListOf<Int>()
        var lastWasSpace = true

        text.forEachIndexed { index, char ->
            val normalizedChar = when {
                char.isLetterOrDigit() -> char.lowercaseChar()
                char.isWhitespace() -> ' '
                else -> null
            }

            when {
                normalizedChar == null -> Unit
                normalizedChar == ' ' -> {
                    if (!lastWasSpace && normalizedBuilder.isNotEmpty()) {
                        normalizedBuilder.append(' ')
                        rawIndexMap.add(index)
                        lastWasSpace = true
                    }
                }
                else -> {
                    normalizedBuilder.append(normalizedChar)
                    rawIndexMap.add(index)
                    lastWasSpace = false
                }
            }
        }

        while (normalizedBuilder.isNotEmpty() && normalizedBuilder.last() == ' ') {
            normalizedBuilder.deleteCharAt(normalizedBuilder.lastIndex)
            rawIndexMap.removeLast()
        }

        return NormalizedTextMap(
            normalized = normalizedBuilder.toString(),
            rawIndexByNormalizedPosition = rawIndexMap.toIntArray(),
        )
    }

    private data class NormalizedTextMap(
        val normalized: String,
        val rawIndexByNormalizedPosition: IntArray,
    )

    private data class ClipRequest(
        val pcmBytes: ByteArray,
        val newAudioSamples: Int,
        val hasOverlapFromPrevious: Boolean,
        val hasForwardOverlap: Boolean,
        val cutReason: ClipCutReason,
    ) {
        val newAudioSeconds: Double
            get() = newAudioSamples.toDouble() / SAMPLE_RATE.toDouble()
    }

    private enum class ClipCutReason {
        Silence,
        MaxDuration,
        FinalFlush,
    }

    private companion object {
        const val TAG = "GemmaTranscription"
        const val SAMPLE_RATE = 16_000
        const val BYTES_PER_SAMPLE = 2
        const val MIN_CLIP_SECONDS = 5
        const val MIN_CLIP_SAMPLES = SAMPLE_RATE * MIN_CLIP_SECONDS
        const val MAX_CLIP_SECONDS = 20
        const val MAX_CLIP_SAMPLES = SAMPLE_RATE * MAX_CLIP_SECONDS
        const val FORCED_OVERLAP_SECONDS = 2
        const val FORCED_OVERLAP_SAMPLES = SAMPLE_RATE * FORCED_OVERLAP_SECONDS
        const val MIN_FINAL_NEW_AUDIO_SAMPLES = SAMPLE_RATE
        const val CLIP_CHANNEL_CAPACITY = 4
        const val TRANSCRIPTION_GENERATION_TOKENS = 256
        const val WAV_HEADER_SIZE = 44
        const val MAX_OVERLAP_WORDS = 24
        const val STRONG_TOKEN_MATCH_SCORE = 0.75
        const val MIN_OVERLAP_AVERAGE_SCORE = 0.75
        const val RECENT_TAIL_WORDS = 80
        const val MIN_CHARACTER_OVERLAP = 20
        const val MAX_CHARACTER_OVERLAP = 160
        const val SILENCE_HOLD_MILLIS = 350
        const val SILENCE_HOLD_SAMPLES = SAMPLE_RATE * SILENCE_HOLD_MILLIS / 1000
        const val SILENCE_AMPLITUDE_THRESHOLD = 0.015f
        const val AUDIO_PROMPT_NO_CONTEXT = "Return only the transcript for this audio clip."
        val WORD_SPLIT_REGEX = Regex("\\s+")
    }
}
