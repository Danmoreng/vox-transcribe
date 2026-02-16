package com.example.voxtranscribe.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voxtranscribe.data.NotesRepository
import com.example.voxtranscribe.data.db.Note
import com.example.voxtranscribe.domain.TranscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TranscriptionStats(
    val isOffline: Boolean = false,
    val durationSeconds: Long = 0,
    val wordCount: Int = 0
)

@HiltViewModel
class TranscriptionViewModel @Inject constructor(
    private val speechRepository: TranscriptionRepository,
    private val notesRepository: NotesRepository
) : ViewModel() {

    // Persistent data
    val allNotes: StateFlow<List<Note>> = notesRepository.getAllNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var currentNoteId: Long? = null
    private val _accumulatedText = MutableStateFlow("")
    
    val transcriptionState: StateFlow<String> = combine(
        _accumulatedText,
        speechRepository.partialText
    ) { accumulated, partial ->
        if (partial.isEmpty()) accumulated else if (accumulated.isEmpty()) partial else "$accumulated\n$partial"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _durationSeconds = MutableStateFlow(0L)
    private var timerJob: Job? = null

    init {
        // Collect finalized segments and save to DB
        viewModelScope.launch {
            speechRepository.transcriptionState.collect { entry ->
                if (entry.isFinal) {
                    _accumulatedText.value = if (_accumulatedText.value.isEmpty()) {
                        entry.text
                    } else {
                        "${_accumulatedText.value}\n${entry.text}"
                    }
                    
                    // Save to Room if we have an active session
                    currentNoteId?.let { id ->
                        notesRepository.insertSegment(id, entry.text, true)
                    }
                }
            }
        }
    }

    val stats: StateFlow<TranscriptionStats> = combine(
        speechRepository.isOfflineModel,
        _durationSeconds,
        transcriptionState
    ) { isOffline, duration, text ->
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        TranscriptionStats(isOffline, duration, words)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TranscriptionStats())

    fun startRecording() {
        if (_isListening.value) return
        
        viewModelScope.launch {
            _accumulatedText.value = ""
            _durationSeconds.value = 0
            
            // 1. Create Note in DB
            val title = "Note @ ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
            currentNoteId = notesRepository.createNote(title)
            
            // 2. Start Engine
            startTimer()
            speechRepository.startListening()
            _isListening.value = true
        }
    }

    fun stopRecording() {
        stopTimer()
        speechRepository.stopListening()
        _isListening.value = false
        
        // Update end time
        viewModelScope.launch {
            currentNoteId?.let { id ->
                // In a real app we'd fetch the note and update endTime
                // For now, we just reset the ID to close the session
            }
            currentNoteId = null
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                _durationSeconds.value += 1
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    fun deleteNote(note: Note) {
        // Implementation for deleting notes
    }

    override fun onCleared() {
        super.onCleared()
        speechRepository.cleanup()
    }
}
