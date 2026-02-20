package com.example.voxtranscribe.data

import android.util.Log
import com.example.voxtranscribe.domain.LogEntry
import com.example.voxtranscribe.domain.TranscriptionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sin

enum class EngineState {
    Uninitialized, Loading, Ready, Error
}

@Singleton
class VoxtralTranscriptionRepository @Inject constructor(
    private val voxtralJni: VoxtralJni,
    private val modelManager: VoxtralModelManager
) : TranscriptionRepository {

    private val TAG = "VoxtralRepo"
    private val scope = CoroutineScope(Dispatchers.Default)
    private val nativeDispatcher = Dispatchers.IO.limitedParallelism(1)
    
    private var transcriptionJob: Job? = null
    private var processJob: Job? = null
    private var handle: Long = 0
    private val audioRecorder = AudioRecorder()

    private val _transcriptionState = MutableSharedFlow<LogEntry>(replay = 1)
    override val transcriptionState: SharedFlow<LogEntry> = _transcriptionState.asSharedFlow()

    private val _partialText = MutableStateFlow("")
    override val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _isOfflineModel = MutableStateFlow(true)
    override val isOfflineModel: StateFlow<Boolean> = _isOfflineModel.asStateFlow()

    private val _engineState = MutableStateFlow(EngineState.Uninitialized)
    override val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _gpuBackend = MutableStateFlow(1) // 1 = Auto (Default)
    val gpuBackend: StateFlow<Int> = _gpuBackend.asStateFlow()

    private val SAMPLE_RATE = 16000
    private var audioQueue: Channel<FloatArray>? = null
    private val fullAudioHistory = ArrayList<FloatArray>()
    private var streamHandle: Long = 0
    private val liveAccumulator = StringBuilder()
    
    private var lastRtf = 1.0
    private var lastSuccessCount: Long = 0
    private var skipNextDecode = false
    private var adaptiveMinDecodeSamples = SAMPLE_RATE * 2
    private var adaptiveMaxTokens = 24

    fun setGpuBackend(backend: Int) {
        if (_gpuBackend.value != backend) {
            _gpuBackend.value = backend
            if (_engineState.value == EngineState.Ready || _engineState.value == EngineState.Error) {
                loadModel()
            }
        }
    }

    fun getSelectedModelName(): String {
        return modelManager.getSelectedModel()?.name ?: "None"
    }

    fun loadModel() {
        if (_engineState.value == EngineState.Loading) {
            return
        }
        
        scope.launch {
            val modelPath = modelManager.getModelPath()
            if (modelPath == null) {
                 Log.e(TAG, "Voxtral model not found")
                 _engineState.value = EngineState.Error
                 return@launch
            }

            try {
                _engineState.value = EngineState.Loading
                Log.d(TAG, "Initializing Voxtral engine with backend: ${_gpuBackend.value} and model: $modelPath")
                
                // Cleanup previous handle if exists
                if (handle != 0L) {
                    // We need to cleanup on native dispatcher
                    withContext(nativeDispatcher) {
                        if (streamHandle != 0L) {
                            voxtralJni.streamFree(streamHandle)
                            streamHandle = 0
                        }
                        voxtralJni.free(handle)
                        handle = 0
                    }
                }

                handle = withContext(nativeDispatcher) {
                    voxtralJni.init(modelPath, 0, _gpuBackend.value, 0)
                }
                
                if (handle != 0L) {
                    val initialized = withContext(nativeDispatcher) {
                        // Restore 5s context for semantic stability, 2s cadence baseline
                        initStreamInternal(
                            maxBufferSamples = SAMPLE_RATE * 5,
                            enableIncrementalEncoder = true,
                            minDecodeSamples = SAMPLE_RATE * 2,
                            maxTokens = 24
                        )
                    }
                    if (initialized) {
                        _engineState.value = EngineState.Ready
                        Log.i(TAG, "Voxtral engine and stream initialized successfully")
                    } else {
                        _engineState.value = EngineState.Error
                        Log.e(TAG, "Failed to initialize Voxtral stream")
                    }
                } else {
                    _engineState.value = EngineState.Error
                    Log.e(TAG, "Failed to initialize Voxtral engine (Handle is 0)")
                }
            } catch (e: Throwable) {
                _engineState.value = EngineState.Error
                Log.e(TAG, "Critical failure during Voxtral init (possible native crash)", e)
            }
        }
    }
    
    // Must be called on nativeDispatcher
    private fun initStreamInternal(
        maxBufferSamples: Int, 
        enableIncrementalEncoder: Boolean,
        minDecodeSamples: Int,
        maxTokens: Int
    ): Boolean {
        if (handle == 0L) return false
        if (streamHandle != 0L) {
            voxtralJni.streamFree(streamHandle)
            streamHandle = 0
        }
        
        streamHandle = voxtralJni.streamInit(
            handle, 
            maxBufferSamples, 
            enableIncrementalEncoder,
            minDecodeSamples,
            maxTokens
        )
        return streamHandle != 0L
    }

    override fun startListening() {
        if (_engineState.value != EngineState.Ready) return

        Log.d(TAG, "Starting listening...")
        audioRecorder.startRecording()
        
        fullAudioHistory.clear()
        liveAccumulator.setLength(0)
        _partialText.value = ""
        lastRtf = 1.0
        lastSuccessCount = 0
        skipNextDecode = false
        adaptiveMinDecodeSamples = SAMPLE_RATE * 2 // 2.0s cadence baseline
        adaptiveMaxTokens = 24
        
        transcriptionJob?.cancel()
        processJob?.cancel()
        
        // Ensure stream is reset to optimized live settings: 5s context, 2s cadence
        scope.launch(nativeDispatcher) {
            initStreamInternal(
                maxBufferSamples = SAMPLE_RATE * 5,
                enableIncrementalEncoder = true,
                minDecodeSamples = adaptiveMinDecodeSamples,
                maxTokens = adaptiveMaxTokens
            )
        }
        
        // Sequential processing: Use an unlimited channel to avoid dropping audio chunks
        val queue = Channel<FloatArray>(capacity = Channel.UNLIMITED)
        audioQueue = queue
        
        transcriptionJob = scope.launch {
            audioRecorder.audioFlow.collect { audioBuffer ->
                synchronized(fullAudioHistory) {
                    fullAudioHistory.add(audioBuffer)
                }
                queue.send(audioBuffer)
            }
        }

        processJob = scope.launch {
            for (chunk in queue) {
                processLiveBuffer(chunk)
            }
        }
    }

    private suspend fun processLiveBuffer(buffer: FloatArray) {
        if (handle == 0L || streamHandle == 0L) return

        try {
            val text = withContext(nativeDispatcher) {
                 voxtralJni.streamPush(streamHandle, buffer)
                 
                 // Sequential Processing: skipping is now disabled to ensure full accuracy
                 /*
                 if (skipNextDecode) {
                     skipNextDecode = false
                     return@withContext ""
                 }
                 */
                 
                 voxtralJni.streamDecode(streamHandle)
            }
            
            if (text.isNotEmpty()) {
                liveAccumulator.append(text)
                _partialText.value = liveAccumulator.toString().trim()
                Log.d(TAG, "Live Delta: '$text'")
            }
            
            // Stats check for adaptation (logging only, no skipping)
            val stats = withContext(nativeDispatcher) {
                voxtralJni.streamGetStats(streamHandle)
            }
            stats?.let {
                if (it.decodeSuccess > lastSuccessCount) {
                    lastSuccessCount = it.decodeSuccess
                    lastRtf = it.lastRtf
                    val rtfStr = "%.2f".format(it.lastRtf)
                    val encStr = "%.1f".format(it.lastEncoderMs)
                    Log.d(TAG, "Live Stats: RTF=$rtfStr, Enc=${encStr}ms, Cadence=${adaptiveMinDecodeSamples/16000.0}s, MaxTokens=$adaptiveMaxTokens")
                    
                    // Adaptive logic for parameters only (RTF > 1.0 means lagging)
                    var changed = false
                    if (it.lastRtf > 1.2) {
                        // Throttling: Increase cadence and reduce token budget to catch up
                        if (adaptiveMinDecodeSamples < SAMPLE_RATE * 4) {
                            adaptiveMinDecodeSamples += (SAMPLE_RATE * 0.5).toInt()
                            changed = true
                        }
                        if (adaptiveMaxTokens > 12) {
                            adaptiveMaxTokens -= 4
                            changed = true
                        }
                    } else if (it.lastRtf < 0.8) {
                        // Relax: Gradually return to baseline (2.0s)
                        if (adaptiveMinDecodeSamples > SAMPLE_RATE * 2) {
                            adaptiveMinDecodeSamples -= (SAMPLE_RATE * 0.25).toInt()
                            changed = true
                        }
                        if (adaptiveMaxTokens < 24) {
                            adaptiveMaxTokens += 4
                            changed = true
                        }
                    }

                    if (changed) {
                        Log.d(TAG, "Adapting parameters: Cadence=${adaptiveMinDecodeSamples/16000.0}s, MaxTokens=$adaptiveMaxTokens")
                        scope.launch(nativeDispatcher) {
                            initStreamInternal(
                                maxBufferSamples = SAMPLE_RATE * 5, // Keep context at 5s baseline
                                enableIncrementalEncoder = true,
                                minDecodeSamples = adaptiveMinDecodeSamples,
                                maxTokens = adaptiveMaxTokens
                            )
                        }
                    }
                    
                    // skipNextDecode = it.lastRtf > 2.0 // DISABLED: Sequential processing required
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during live transcription: ${e.message}")
        }
    }
    
    private var testSample: FloatArray? = null
    private var isRecordingTestSample = false

    fun startRecordingTestSample() {
        isRecordingTestSample = true
        fullAudioHistory.clear()
        audioRecorder.startRecording()
        scope.launch {
            audioRecorder.audioFlow.collect { buffer ->
                if (isRecordingTestSample) {
                    synchronized(fullAudioHistory) {
                        fullAudioHistory.add(buffer)
                    }
                }
            }
        }
    }

    fun stopRecordingTestSample() {
        isRecordingTestSample = false
        audioRecorder.stopRecording()
        synchronized(fullAudioHistory) {
            val totalSamples = fullAudioHistory.sumOf { it.size }
            val fullBuffer = FloatArray(totalSamples)
            var offset = 0
            for (chunk in fullAudioHistory) {
                System.arraycopy(chunk, 0, fullBuffer, offset, chunk.size)
                offset += chunk.size
            }
            testSample = fullBuffer
        }
    }

    suspend fun transcribeTestAudio(): String {
        if (_engineState.value != EngineState.Ready) return "Engine not ready"
        
        val buffer = testSample ?: run {
            // Fallback to 3s silence/sine if no sample recorded
            FloatArray(16000 * 3).also { buf ->
                val freq = 440.0
                for (i in buf.indices) {
                    val t = i / 16000.0
                    buf[i] = (0.1 * sin(2.0 * Math.PI * freq * t)).toFloat()
                }
            }
        }
        
        return try {
            withContext(nativeDispatcher) {
                 val start = System.currentTimeMillis()
                 // Use the one-shot transcribe for the test sample
                 val text = voxtralJni.transcribe(handle, buffer, 64)
                 val end = System.currentTimeMillis()
                 val rtf = (end - start) / (buffer.size / 16.0) // RTF = processing_time / audio_duration_ms
                 "Test complete. RTF: ${"%.2f".format(rtf / 1000.0)}, Time: ${end - start}ms\nResult: '$text'"
            }
        } catch (e: Exception) {
            "Test failed: ${e.message}"
        }
    }

    override suspend fun stopListening() {
        Log.d(TAG, "Stopping listening...")
        
        audioRecorder.stopRecording()
        transcriptionJob?.cancel()
        transcriptionJob = null
        
        audioQueue?.close()
        processJob?.join()
        processJob = null
        
        // NEW: Sequential processing captures everything, so one-shot post-processing is removed.
        // processFullHistory() 
        
        // Final flush of the stream to get any remaining audio
        val remainingText = withContext(nativeDispatcher) {
            if (streamHandle != 0L) {
                voxtralJni.streamFlush(streamHandle)
            } else ""
        }
        
        if (remainingText.isNotEmpty()) {
            liveAccumulator.append(remainingText)
            _partialText.value = liveAccumulator.toString().trim()
        }
        
        val finalText = liveAccumulator.toString().trim()
        if (finalText.isNotEmpty()) {
            _transcriptionState.emit(
                LogEntry(
                    timestamp = System.currentTimeMillis(),
                    text = finalText,
                    isFinal = true
                )
            )
        }
        
        liveAccumulator.setLength(0)
        _partialText.value = ""
        Log.d(TAG, "Transcription finished.")
    }
    
    private suspend fun processFullHistory() {
        if (handle == 0L) return
        
        try {
            // Snapshot history
            val audioData = synchronized(fullAudioHistory) {
                fullAudioHistory.toList()
            }
            
            if (audioData.isEmpty()) {
                Log.w(TAG, "No audio recorded to transcribe.")
                return
            }

            // 1. Flatten all recorded chunks into one large buffer
            val totalSamples = audioData.sumOf { it.size }
            val fullBuffer = FloatArray(totalSamples)
            var offset = 0
            for (chunk in audioData) {
                System.arraycopy(chunk, 0, fullBuffer, offset, chunk.size)
                offset += chunk.size
            }

            Log.d(TAG, "Running one-shot offline transcription on ${fullBuffer.size} samples...")
            _partialText.value = "Saving... (Accurately transcribing full history...)"

            // 2. Call the one-shot transcribe API (no rolling window)
            // Use higher token budget for accuracy
            val finalText = withContext(nativeDispatcher) {
                 voxtralJni.transcribe(handle, fullBuffer, 192)
            }
            
            Log.i(TAG, "Final Transcription: $finalText")
            
            if (finalText.isNotEmpty()) {
                _transcriptionState.emit(
                    LogEntry(
                        timestamp = System.currentTimeMillis(),
                        text = finalText,
                        isFinal = true
                    )
                )
            }
            
            // 3. Cleanup live state
            liveAccumulator.setLength(0)
            _partialText.value = ""
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during full transcription: ${e.message}")
        }
    }

    override fun clear() {
        _partialText.value = ""
    }
    
    override fun cleanup() {
        scope.launch(nativeDispatcher) {
            if (streamHandle != 0L) {
                voxtralJni.streamFree(streamHandle)
                streamHandle = 0
            }
            if (handle != 0L) {
                voxtralJni.free(handle)
                handle = 0
            }
            _engineState.value = EngineState.Uninitialized
        }
    }
}
