package com.example.voxtranscribe.data

import android.util.Log
import com.example.voxtranscribe.domain.LogEntry
import com.example.voxtranscribe.domain.TranscriptionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val CHUNK_DURATION_SEC = 2 
    private val MIN_SAMPLES = SAMPLE_RATE * CHUNK_DURATION_SEC
    
    private var audioQueue: Channel<FloatArray>? = null

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
                    streamHandle = voxtralJni.streamInit(handle)
                    if (streamHandle != 0L) {
                        _engineState.value = EngineState.Ready
                        Log.i(TAG, "Voxtral engine and stream initialized successfully")
                    } else {
                        _engineState.value = EngineState.Error
                        Log.e(TAG, "Failed to initialize stream")
                    }
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

    override fun startListening() {
        if (_engineState.value != EngineState.Ready) {
            Log.e(TAG, "Cannot start listening: Engine state is ${_engineState.value}")
            return 
        }

        Log.d(TAG, "Starting listening...")
        audioRecorder.startRecording()
        
        transcriptionJob?.cancel()
        processJob?.cancel()
        
        audioQueue = Channel(Channel.UNLIMITED)
        
        transcriptionJob = scope.launch {
            Log.d(TAG, "Transcription producer started")
            audioRecorder.audioFlow.collect { audioBuffer ->
                audioQueue?.send(audioBuffer)
            }
        }

        processJob = scope.launch {
            Log.d(TAG, "Transcription consumer started")
            
            val queue = audioQueue ?: return@launch
            for (chunk in queue) {
                processBuffer(chunk)
            }
            
            // Channel closed, process remaining
            Log.d(TAG, "Transcription consumer finished")
        }
    }

    private suspend fun processBuffer(buffer: FloatArray) {
        if (handle == 0L || streamHandle == 0L) {
            Log.e(TAG, "Handle is 0, cannot process buffer")
            return
        }

        try {
            // Push audio to native stream buffer
            withContext(Dispatchers.IO) {
                 voxtralJni.streamPush(streamHandle, buffer)
            }
            
            // Attempt decode (will only happen if enough audio is buffered)
            val text = withContext(Dispatchers.IO) {
                 voxtralJni.streamDecode(streamHandle)
            }
            
            if (text.isNotEmpty()) {
                Log.i(TAG, "Transcribed: $text")
                
                _transcriptionState.emit(
                    LogEntry(
                        timestamp = System.currentTimeMillis(),
                        text = text,
                        isFinal = true
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during transcription: ${e.message}")
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
            // For one-off test, we can still use the direct transcribe API
            // Or we could use the stream API, but let's keep it simple for now
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
        
        // Signal consumer to finish
        audioQueue?.close()
        
        // Wait for consumer to finish flushing
        Log.d(TAG, "Waiting for processJob to finish flushing...")
        processJob?.join()
        
        // Flush remaining audio in stream
        if (streamHandle != 0L) {
             val finalHash = withContext(Dispatchers.IO) {
                 voxtralJni.streamFlush(streamHandle)
             }
             if (finalHash.isNotEmpty()) {
                 Log.i(TAG, "Final flush: $finalHash")
                 _transcriptionState.emit(
                    LogEntry(
                        timestamp = System.currentTimeMillis(),
                        text = finalHash,
                        isFinal = true
                    )
                )
             }
        }
        
        Log.d(TAG, "processJob finished.")
        processJob = null
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
