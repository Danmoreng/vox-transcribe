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

    private val _transcriptionState = MutableSharedFlow<LogEntry>()
    override val transcriptionState: SharedFlow<LogEntry> = _transcriptionState.asSharedFlow()

    private val _partialText = MutableStateFlow("")
    override val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _isOfflineModel = MutableStateFlow(true)
    override val isOfflineModel: StateFlow<Boolean> = _isOfflineModel.asStateFlow()

    private val _engineState = MutableStateFlow(EngineState.Uninitialized)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    // Buffer configuration
    private val SAMPLE_RATE = 16000
    private val CHUNK_DURATION_SEC = 2 
    private val MIN_SAMPLES = SAMPLE_RATE * CHUNK_DURATION_SEC
    
    // We recreate the channel on every start to avoid closed-channel issues on restart
    private var audioQueue: Channel<FloatArray>? = null

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
                    voxtralJni.init(modelManager.getModelPath(), 4)
                }
                
                if (handle != 0L) {
                    _engineState.value = EngineState.Ready
                    Log.i(TAG, "Voxtral engine initialized successfully")
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
        
        // Reset jobs and queue
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
            val accumulator = ArrayList<Float>(MIN_SAMPLES * 2)
            
            // Loop until channel is closed
            val queue = audioQueue ?: return@launch
            for (chunk in queue) {
                for (sample in chunk) {
                    accumulator.add(sample)
                }

                if (accumulator.size >= MIN_SAMPLES) {
                    Log.d(TAG, "Accumulator full (${accumulator.size} samples), processing...")
                    processBuffer(accumulator.toFloatArray())
                    accumulator.clear()
                }
            }
            
            // Channel closed, process remaining
            if (accumulator.isNotEmpty()) {
                Log.d(TAG, "Flushing final buffer of size: ${accumulator.size}")
                processBuffer(accumulator.toFloatArray())
                accumulator.clear()
            }
            Log.d(TAG, "Transcription consumer finished")
        }
    }

    private suspend fun processBuffer(buffer: FloatArray) {
        if (handle == 0L) {
            Log.e(TAG, "Handle is 0, cannot process buffer")
            return
        }

        Log.d(TAG, "Processing buffer of size: ${buffer.size} samples")
        
        try {
            var maxAmp = 0f
            for (s in buffer) {
                val absS = Math.abs(s)
                if (absS > maxAmp) maxAmp = absS
            }
            Log.d(TAG, "Max amplitude in buffer: $maxAmp")
            
            val text = withContext(Dispatchers.IO) {
                 voxtralJni.transcribe(handle, buffer, 64) 
            }
            
            if (text.isNotEmpty()) {
                Log.i(TAG, "Transcribed: $text")
                _partialText.emit(text)
                
                _transcriptionState.emit(
                    LogEntry(
                        timestamp = System.currentTimeMillis(),
                        text = text,
                        isFinal = true
                    )
                )
            } else {
                Log.d(TAG, "Transcription returned empty string")
            }
        } catch (e: Exception) {
            // If job is cancelled, we might see it here. Log but don't crash.
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

    override fun stopListening() {
        Log.d(TAG, "Stopping listening...")
        
        // 1. Stop recording (producer source)
        audioRecorder.stopRecording()
        
        // 2. Stop producer job
        transcriptionJob?.cancel()
        transcriptionJob = null
        
        // 3. Close channel to signal consumer to finish and flush
        audioQueue?.close()
        
        // 4. Do NOT cancel processJob immediately. Let it drain.
        // It will complete when the channel is empty.
        // However, if we start again immediately, startListening cancels processJob.
        // That is acceptable (race condition on rapid toggle).
        
        // Note: processJob remains non-null until next start or explicit cleanup
    }

    override fun clear() {
        _partialText.value = ""
    }
    
    override fun cleanup() {
        processJob?.cancel() // Force stop on cleanup
        if (handle != 0L) {
            voxtralJni.free(handle)
            handle = 0
            _engineState.value = EngineState.Uninitialized
        }
    }
}
