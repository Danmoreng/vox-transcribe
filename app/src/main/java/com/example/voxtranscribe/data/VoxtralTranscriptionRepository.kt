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
    private var transcriptionJob: Job? = null
    private var processJob: Job? = null
    private var handle: Long = 0
    private val audioRecorder = AudioRecorder()

    // replay=1 ensures the service receives the final event even if it connects late/reconnects
    private val _transcriptionState = MutableSharedFlow<LogEntry>(replay = 1)
    override val transcriptionState: SharedFlow<LogEntry> = _transcriptionState.asSharedFlow()

    private val _partialText = MutableStateFlow("")
    override val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _isOfflineModel = MutableStateFlow(true)
    override val isOfflineModel: StateFlow<Boolean> = _isOfflineModel.asStateFlow()

    private val _engineState = MutableStateFlow(EngineState.Uninitialized)
    override val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    // Buffer configuration
    private val SAMPLE_RATE = 16000
    private val CHUNK_DURATION_SEC = 1 
    private val MIN_SAMPLES = SAMPLE_RATE * CHUNK_DURATION_SEC
    
    private var audioQueue: Channel<FloatArray>? = null
    private val fullAudioHistory = java.util.Collections.synchronizedList(ArrayList<FloatArray>())

    private var streamHandle: Long = 0

    fun loadModel() {
        if (_engineState.value == EngineState.Loading || _engineState.value == EngineState.Ready) {
            Log.d(TAG, "Engine already loading or ready")
            return
        }
        
        scope.launch {
            if (!modelManager.isModelAvailable()) {
                 Log.e(TAG, "Voxtral model not found at ${modelManager.getModelPath()}")
                 _engineState.value = EngineState.Error
                 return@launch
            }

            try {
                _engineState.value = EngineState.Loading
                Log.d(TAG, "Initializing Voxtral engine...")
                
                handle = withContext(Dispatchers.IO) {
                    // gpuBackend: 1 = auto_detect, kvWindow: 0 = library default
                    // threads: 0 = library auto-detect (hw - 2)
                    voxtralJni.init(modelManager.getModelPath(), 0, 1, 0)
                }
                
                if (handle != 0L) {
                    initStream()
                } else {
                    _engineState.value = EngineState.Error
                    Log.e(TAG, "Failed to initialize Voxtral engine")
                }
            } catch (e: Exception) {
                _engineState.value = EngineState.Error
                Log.e(TAG, "Exception during Voxtral init", e)
            }
        }
    }
    
    private fun initStream() {
        if (handle == 0L) return
        if (streamHandle != 0L) voxtralJni.streamFree(streamHandle)
        
        streamHandle = voxtralJni.streamInit(handle)
        if (streamHandle != 0L) {
            _engineState.value = EngineState.Ready
            Log.i(TAG, "Voxtral engine and stream initialized successfully")
        } else {
            _engineState.value = EngineState.Error
            Log.e(TAG, "Failed to initialize stream")
        }
    }

    override fun startListening() {
        if (_engineState.value != EngineState.Ready) {
            Log.e(TAG, "Cannot start listening: Engine state is ${_engineState.value}")
            return 
        }

        Log.d(TAG, "Starting listening...")
        audioRecorder.startRecording()
        
        fullAudioHistory.clear()
        transcriptionJob?.cancel()
        processJob?.cancel()
        
        // Bounded channel for live preview (drop oldest to avoid lag spiral)
        audioQueue = Channel(capacity = 5, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        
        transcriptionJob = scope.launch {
            Log.d(TAG, "Transcription producer started")
            audioRecorder.audioFlow.collect { audioBuffer ->
                fullAudioHistory.add(audioBuffer) // Save full fidelity
                audioQueue?.trySend(audioBuffer)  // Best-effort live preview
            }
        }

        processJob = scope.launch {
            Log.d(TAG, "Transcription live consumer started")
            
            val queue = audioQueue ?: return@launch
            for (chunk in queue) {
                processLiveBuffer(chunk)
            }
            
            Log.d(TAG, "Transcription live consumer finished")
        }
    }

    private suspend fun processLiveBuffer(buffer: FloatArray) {
        if (handle == 0L || streamHandle == 0L) return

        try {
            _partialText.value = "..." // Indicate processing activity
            
            withContext(Dispatchers.IO) {
                 voxtralJni.streamPush(streamHandle, buffer)
            }
            
            val text = withContext(Dispatchers.IO) {
                 voxtralJni.streamDecode(streamHandle)
            }
            
            if (text.isNotEmpty()) {
                _partialText.value = text
                // NOTE: We do NOT emit to transcriptionState here to avoid duplicates/fragmentation in DB.
                // Live view is purely visual.
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during live transcription: ${e.message}")
        }
    }
    
    suspend fun transcribeTestAudio(): String {
        if (_engineState.value != EngineState.Ready) return "Engine not ready"
        
        val buffer = FloatArray(16000 * 3)
        val freq = 440.0
        for (i in buffer.indices) {
            val t = i / 16000.0
            buffer[i] = (0.1 * sin(2.0 * Math.PI * freq * t)).toFloat()
        }
        
        Log.d(TAG, "Running test transcription (3s sine wave)...")
        return try {
            val start = System.currentTimeMillis()
            val text = withContext(Dispatchers.IO) {
                 voxtralJni.transcribe(handle, buffer, 32)
            }
            val end = System.currentTimeMillis()
            val result = "Test complete (${end - start}ms). Result: '$text'"
            Log.d(TAG, result)
            result
        } catch (e: Exception) {
            Log.e(TAG, "Test failed", e)
            "Test failed: ${e.message}"
        }
    }

    override suspend fun stopListening() {
        Log.d(TAG, "Stopping listening...")
        
        audioRecorder.stopRecording()
        
        transcriptionJob?.cancel()
        transcriptionJob = null
        
        audioQueue?.close()
        processJob?.join() // Wait for live consumer to finish pending chunks
        processJob = null
        
        Log.d(TAG, "Starting full offline transcription of ${fullAudioHistory.size} chunks...")
        
        // Final offline pass on full history
        processFullHistory()
        
        Log.d(TAG, "Full processing finished.")
    }
    
    private suspend fun processFullHistory() {
        if (handle == 0L) return
        
        try {
            // Reset stream for clean full pass
            withContext(Dispatchers.IO) {
                initStream() 
            }
            
            if (streamHandle == 0L) return

            val resultAccumulator = StringBuilder()

            // Feed all audio in larger batches to optimize CPU throughput
            // 5s batches match our max_buffer_samples, minimizing redundant overlap encoding
            withContext(Dispatchers.IO) {
                val batchSize = 5 // 5 chunks of 1s each
                var currentBatch = ArrayList<Float>()
                
                for (chunk in fullAudioHistory) {
                    for (sample in chunk) currentBatch.add(sample)
                    
                    if (currentBatch.size >= SAMPLE_RATE * batchSize) {
                        voxtralJni.streamPush(streamHandle, currentBatch.toFloatArray())
                        val delta = voxtralJni.streamDecode(streamHandle)
                        if (delta.isNotEmpty()) resultAccumulator.append(delta)
                        currentBatch.clear()
                    }
                }
                
                // Push remaining
                if (currentBatch.isNotEmpty()) {
                    voxtralJni.streamPush(streamHandle, currentBatch.toFloatArray())
                }
                
                val finalHash = voxtralJni.streamFlush(streamHandle)
                if (finalHash.isNotEmpty()) {
                    resultAccumulator.append(finalHash)
                }
                
                val finalText = resultAccumulator.toString().trim()
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
            }
            
            // Clear partial text as we are done
            _partialText.value = ""
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during full transcription: ${e.message}")
        }
    }

    override fun clear() {
        _partialText.value = ""
    }
    
    override fun cleanup() {
        processJob?.cancel()
        if (streamHandle != 0L) {
            voxtralJni.streamFree(streamHandle)
            streamHandle = 0
        }
        if (handle != 0L) {
            voxtralJni.free(handle)
            handle = 0
            _engineState.value = EngineState.Uninitialized
        }
    }
}
