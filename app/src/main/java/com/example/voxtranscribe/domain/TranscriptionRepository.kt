package com.example.voxtranscribe.domain

import kotlinx.coroutines.flow.StateFlow

interface TranscriptionRepository {
    /**
     * Hot flow emitting the live transcription string.
     */
    val transcriptionState: StateFlow<String>
    
    /**
     * Hot flow emitting audio amplitude (0-1.0) for the UI visualizer.
     */
    val amplitudeState: StateFlow<Float> 
    
    fun startListening()
    fun stopListening()
    fun cleanup()
}
