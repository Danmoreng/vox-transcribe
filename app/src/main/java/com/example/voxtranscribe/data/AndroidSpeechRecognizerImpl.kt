package com.example.voxtranscribe.data

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.voxtranscribe.domain.TranscriptionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidSpeechRecognizerImpl(private val context: Context) : TranscriptionRepository, RecognitionListener {

    private val _isOfflineModel = MutableStateFlow(false)
    override val isOfflineModel: StateFlow<Boolean> = _isOfflineModel.asStateFlow()

    private val speechRecognizer: SpeechRecognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
        _isOfflineModel.value = true
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    } else {
        _isOfflineModel.value = false
        SpeechRecognizer.createSpeechRecognizer(context)
    }
    
    private val _transcriptionState = MutableStateFlow("")
    override val transcriptionState: StateFlow<String> = _transcriptionState.asStateFlow()

    private var isActive = false
    private var accumulatedText = ""

    private val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
    }

    override fun startListening() {
        isActive = true
        speechRecognizer.setRecognitionListener(this)
        speechRecognizer.startListening(recognizerIntent)
    }

    override fun stopListening() {
        isActive = false
        speechRecognizer.stopListening()
    }

    override fun clear() {
        accumulatedText = ""
        _transcriptionState.value = ""
    }

    override fun cleanup() {
        isActive = false
        speechRecognizer.destroy()
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
        if (isActive) {
            speechRecognizer.startListening(recognizerIntent)
        }
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val newText = matches[0]
            accumulatedText = if (accumulatedText.isEmpty()) newText else "$accumulatedText $newText"
            _transcriptionState.value = accumulatedText
        }
        
        if (isActive) {
            speechRecognizer.startListening(recognizerIntent)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val partial = matches[0]
            _transcriptionState.value = if (accumulatedText.isEmpty()) partial else "$accumulatedText $partial"
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
}
