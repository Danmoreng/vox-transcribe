package com.example.voxtranscribe.data

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioRecorder {
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Voxtral expects 16kHz, 16-bit PCM, Mono
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
    private val bufferSize = minBufferSize * 2

    private var _audioChannel = Channel<FloatArray>(Channel.UNLIMITED)
    val audioFlow: Flow<FloatArray> get() = _audioChannel.receiveAsFlow()

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (recordingJob?.isActive == true) return

        try {
            // Re-create channel to ensure it's fresh and not closed from previous session
            _audioChannel = Channel(Channel.UNLIMITED)
            
            Log.d("AudioRecorder", "Initializing AudioRecord with buffer size: $bufferSize")
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioEncoding,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioRecorder", "AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()
            Log.d("AudioRecorder", "Started recording")

            recordingJob = scope.launch {
                val buffer = ShortArray(bufferSize / 2)
                try {
                    while (isActive) {
                        val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (readCount > 0) {
                            val floatBuffer = FloatArray(readCount)
                            for (i in 0 until readCount) {
                                floatBuffer[i] = buffer[i] / 32768.0f
                            }
                            _audioChannel.send(floatBuffer)
                        } else if (readCount < 0) {
                            Log.e("AudioRecorder", "AudioRecord read error: $readCount")
                            break
                        } else {
                            // readCount == 0, avoid tight loop if recorder was stopped
                            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_STOPPED) {
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AudioRecorder", "Error in recording loop", e)
                } finally {
                    _audioChannel.close()
                    Log.d("AudioRecorder", "Audio channel closed")
                }
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error starting recording", e)
        }
    }

    fun stopRecording() {
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                 audioRecord?.stop()
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error stopping AudioRecord", e)
        }
        
        // We cancel the job to break the while(isActive) loop
        recordingJob?.cancel()
        recordingJob = null
        
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error releasing AudioRecord", e)
        }
        audioRecord = null
        Log.d("AudioRecorder", "Stopped recording")
    }
}
