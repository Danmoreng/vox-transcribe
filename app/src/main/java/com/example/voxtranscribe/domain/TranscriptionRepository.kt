package com.example.voxtranscribe.domain

import kotlinx.coroutines.flow.StateFlow

interface TranscriptionRepository {
    val transcriptionState: StateFlow<String>
    val amplitudeState: StateFlow<Float> 
    val isOfflineModel: StateFlow<Boolean>
    
    fun startListening()
    fun stopListening()
    fun clear()
    fun cleanup()
}
