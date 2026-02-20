package com.example.voxtranscribe.data

import android.util.Log
import com.example.voxtranscribe.domain.LogEntry
import com.example.voxtranscribe.domain.TranscriptionRepository
import com.example.voxtranscribe.data.EngineState
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
    private var adaptiveMinDecodeSamples = SAMPLE_RATE * 2
    private var adaptiveMaxTokens = 256

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
                
                if (handle != 0L) {
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
                        initStreamInternal(
                            maxBufferSamples = SAMPLE_RATE * 120, // 120s stable window
                            enableIncrementalEncoder = true,
                            enablePersistentStreamState = true,
                            minDecodeSamples = adaptiveMinDecodeSamples,
                            maxTokens = 256
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
                Log.e(TAG, "Critical failure during Voxtral init", e)
            }
        }
    }
    
    private fun initStreamInternal(
        maxBufferSamples: Int, 
        enableIncrementalEncoder: Boolean,
        enablePersistentStreamState: Boolean,
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
            enablePersistentStreamState,
            minDecodeSamples,
            maxTokens
        )
        return streamHandle != 0L
    }

    override fun startListening() {
        if (_engineState.value != EngineState.Ready) return

        Log.d(TAG, "Starting listening...")
        
        fullAudioHistory.clear()
        liveAccumulator.setLength(0)
        _partialText.value = ""
        lastRtf = 1.0
        lastSuccessCount = 0
        adaptiveMinDecodeSamples = SAMPLE_RATE * 2
        adaptiveMaxTokens = 256
        
        transcriptionJob?.cancel()
        processJob?.cancel()
        
        scope.launch(nativeDispatcher) {
            initStreamInternal(
                maxBufferSamples = SAMPLE_RATE * 120,
                enableIncrementalEncoder = true,
                enablePersistentStreamState = true,
                minDecodeSamples = adaptiveMinDecodeSamples,
                maxTokens = adaptiveMaxTokens
            )
        }
        
        val queue = Channel<FloatArray>(capacity = Channel.UNLIMITED)
        audioQueue = queue
        
        audioRecorder.startRecording()

        transcriptionJob = scope.launch {
            val batchSamples = SAMPLE_RATE // 1.0s batching for stability
            var batchBuffer = FloatArray(batchSamples)
            var batchPos = 0
            
            try {
                audioRecorder.audioFlow.collect { audioBuffer: FloatArray ->
                    synchronized(fullAudioHistory) {
                        fullAudioHistory.add(audioBuffer)
                    }
                    
                    var inputPos = 0
                    while (inputPos < audioBuffer.size) {
                        val toCopy = minOf(batchSamples - batchPos, audioBuffer.size - inputPos)
                        System.arraycopy(audioBuffer, inputPos, batchBuffer, batchPos, toCopy)
                        batchPos += toCopy
                        inputPos += toCopy
                        
                        if (batchPos >= batchSamples) {
                            queue.send(batchBuffer.copyOf())
                            batchPos = 0
                        }
                    }
                }
            } finally {
                if (batchPos > 0) {
                    queue.send(batchBuffer.copyOfRange(0, batchPos))
                }
                queue.close()
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
                 voxtralJni.streamDecode(streamHandle)
            }
            
            if (text.isNotEmpty()) {
                liveAccumulator.append(text)
                _partialText.value = liveAccumulator.toString().trim()
                Log.d(TAG, "Live Delta: '$text'")
            }
            
            val stats = withContext(nativeDispatcher) {
                voxtralJni.streamGetStats(streamHandle)
            }
            stats?.let {
                if (it.decodeSuccess > lastSuccessCount) {
                    lastSuccessCount = it.decodeSuccess
                    lastRtf = it.lastRtf
                    Log.d(TAG, "Live Stats: RTF=${"%.2f".format(it.lastRtf)}, Success=${it.decodeSuccess}")
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
            audioRecorder.audioFlow.collect { buffer: FloatArray ->
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
        audioRecorder.release()
    }

    override suspend fun transcribeTestAudio(): String {
        if (_engineState.value != EngineState.Ready) return "Engine not ready"
        
        val buffer = testSample ?: run {
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
                 val text = voxtralJni.transcribe(handle, buffer, 256)
                 val end = System.currentTimeMillis()
                 val rtf = (end - start) / (buffer.size / 16.0)
                 "Test complete. RTF: ${"%.2f".format(rtf / 1000.0)}, Time: ${end - start}ms\nResult: '$text'"
            }
        } catch (e: Exception) {
            "Test failed: ${e.message}"
        }
    }

    override suspend fun stopListening() {
        Log.d(TAG, "Stopping listening...")
        
        audioRecorder.stopRecording()
        
        transcriptionJob?.join()
        transcriptionJob = null
        
        processJob?.join()
        processJob = null
        
        val remainingText = withContext(nativeDispatcher) {
            if (streamHandle != 0L) {
                voxtralJni.streamFlush(streamHandle)
            } else ""
        }
        
        if (remainingText.isNotEmpty()) {
            liveAccumulator.append(remainingText)
            _partialText.value = liveAccumulator.toString().trim()
            Log.d(TAG, "Flush Delta: '$remainingText'")
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
        
        audioRecorder.release()
        Log.d(TAG, "Transcription finished.")
    }

    override fun clear() {
        liveAccumulator.setLength(0)
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
