package com.example.voxtranscribe.data

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.voxtranscribe.domain.LogEntry
import com.example.voxtranscribe.domain.TranscriptionRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidSpeechRecognizerImpl(private val context: Context) : TranscriptionRepository, RecognitionListener {

    private val _isOfflineModel = MutableStateFlow(false)
    override val isOfflineModel: StateFlow<Boolean> = _isOfflineModel.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    
    private val _transcriptionState = MutableSharedFlow<LogEntry>(replay = 1)
    override val transcriptionState: SharedFlow<LogEntry> = _transcriptionState.asSharedFlow()

    private val _partialText = MutableStateFlow("")
    override val partialText: StateFlow<String> = _partialText.asStateFlow()

    private var isActive = false

    private val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
    }

    private fun ensureRecognizer() {
        if (speechRecognizer == null) {
            speechRecognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                _isOfflineModel.value = true
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                _isOfflineModel.value = false
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            speechRecognizer?.setRecognitionListener(this)
        }
    }

    override fun startListening() {
        isActive = true
        ensureRecognizer()
        speechRecognizer?.startListening(recognizerIntent)
    }

    override suspend fun stopListening() {
        isActive = false
        speechRecognizer?.stopListening()
    }

    override fun clear() {
        _partialText.value = ""
    }

    override fun cleanup() {
        isActive = false
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun restartListening() {
        if (isActive) {
            ensureRecognizer()
            speechRecognizer?.startListening(recognizerIntent)
        }
    }

    // RecognitionListener callbacks
    override fun onReadyForSpeech(params: Bundle?) {
        Log.d("SpeechRecognizer", "Ready for speech")
    }
    
    override fun onBeginningOfSpeech() {}
    
    override fun onRmsChanged(rmsdB: Float) {}
    
    override fun onBufferReceived(buffer: ByteArray?) {}
    
    override fun onEndOfSpeech() {
        Log.d("SpeechRecognizer", "End of speech")
    }
    
    override fun onError(error: Int) {
        Log.e("SpeechRecognizer", "Error: $error")
        // Errors like ERROR_SPEECH_TIMEOUT (7) or ERROR_NO_MATCH (6) happen normally during silence
        if (isActive) {
            restartListening()
        }
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val text = matches[0]
            _partialText.value = ""
            val entry = LogEntry(
                text = text,
                timestamp = System.currentTimeMillis(),
                isFinal = true
            )
            _transcriptionState.tryEmit(entry)
        }
        
        if (isActive) {
            restartListening()
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            _partialText.value = matches[0]
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
}
