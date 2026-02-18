package com.example.voxtranscribe.data

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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

    private val _audioFlow = MutableSharedFlow<FloatArray>()
    val audioFlow: SharedFlow<FloatArray> = _audioFlow

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (recordingJob?.isActive == true) return

        try {
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
                val buffer = ShortArray(bufferSize / 2) // Read as shorts (16-bit)
                while (isActive) {
                    val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readCount > 0) {
                        // Convert ShortArray to FloatArray normalized to [-1.0, 1.0]
                        val floatBuffer = FloatArray(readCount)
                        for (i in 0 until readCount) {
                            floatBuffer[i] = buffer[i] / 32768.0f
                        }
                        // Log.v("AudioRecorder", "Emitting buffer of size: $readCount")
                        _audioFlow.emit(floatBuffer)
                    } else {
                        Log.w("AudioRecorder", "AudioRecord read returned: $readCount")
                        if (readCount < 0) break // Error
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error starting recording", e)
        }
    }

    fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null
        try {
            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                 audioRecord?.stop()
                 audioRecord?.release()
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error stopping recording", e)
        }
        audioRecord = null
        Log.d("AudioRecorder", "Stopped recording")
    }
}
