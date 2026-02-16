package com.example.voxtranscribe.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voxtranscribe.data.NotesRepository
import com.example.voxtranscribe.data.TranscriptionService
import com.example.voxtranscribe.data.db.Note
import com.example.voxtranscribe.domain.TranscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

data class TranscriptionStats(
    val isOffline: Boolean = false,
    val durationSeconds: Long = 0,
    val wordCount: Int = 0
)

@HiltViewModel
class TranscriptionViewModel @Inject constructor(
    private val speechRepository: TranscriptionRepository,
    private val notesRepository: NotesRepository,
    @ApplicationContext private val context: Context
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
            val title = "Note @ ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}"
            currentNoteId = notesRepository.createNote(title)
            
            // 2. Start Service
            val intent = Intent(context, TranscriptionService::class.java).apply {
                action = TranscriptionService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            
            startTimer()
            _isListening.value = true
        }
    }

    fun stopRecording() {
        stopTimer()
        
        // Stop Service
        val intent = Intent(context, TranscriptionService::class.java).apply {
            action = TranscriptionService.ACTION_STOP
        }
        context.startService(intent)
        
        _isListening.value = false
        currentNoteId = null
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
