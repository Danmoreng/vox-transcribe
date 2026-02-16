package com.example.voxtranscribe.data

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.voxtranscribe.domain.TranscriptionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidSpeechRecognizerImpl(private val context: Context) : TranscriptionRepository, RecognitionListener {

    private val speechRecognizer: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    
    private val _transcriptionState = MutableStateFlow("")
    override val transcriptionState: StateFlow<String> = _transcriptionState.asStateFlow()

    private val _amplitudeState = MutableStateFlow(0f)
    override val amplitudeState: StateFlow<Float> = _amplitudeState.asStateFlow()

    private val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

    override fun startListening() {
        speechRecognizer.setRecognitionListener(this)
        speechRecognizer.startListening(recognizerIntent)
    }

    override fun stopListening() {
        speechRecognizer.stopListening()
    }

    override fun cleanup() {
        speechRecognizer.destroy()
    }

    // RecognitionListener callbacks
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {
        // Convert rmsdB to a 0-1.0 range for the visualizer.
        // rmsdB typically ranges from -2 to 10 or so.
        val normalized = ((rmsdB + 2) / 12).coerceIn(0f, 1f)
        _amplitudeState.value = normalized
    }
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onError(error: Int) {
        // Handle errors as needed for v1
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            _transcriptionState.value = matches[0]
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            _transcriptionState.value = matches[0]
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
}
