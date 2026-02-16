package com.example.voxtranscribe.domain

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface TranscriptionRepository {
    /**
     * SharedFlow emitting finalized log entries with timestamps.
     */
    val transcriptionState: SharedFlow<LogEntry>
    
    /**
     * StateFlow emitting current partial transcription for live display.
     */
    val partialText: StateFlow<String>

    val isOfflineModel: StateFlow<Boolean>
    
    fun startListening()
    fun stopListening()
    fun clear()
    fun cleanup()
}
