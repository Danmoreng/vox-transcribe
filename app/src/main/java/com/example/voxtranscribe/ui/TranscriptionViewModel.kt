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

    val allNotes: StateFlow<List<Note>> = notesRepository.getAllNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private var currentNoteId: Long? = null
    private val _accumulatedText = MutableStateFlow("")
    
    val transcriptionState: StateFlow<String> = combine(
        _accumulatedText,
        speechRepository.partialText,
        _isListening
    ) { accumulated, partial, listening ->
        if (listening) {
            if (partial.isEmpty()) accumulated else if (accumulated.isEmpty()) partial else "$accumulated\n$partial"
        } else {
            // After listening stops, prioritize the final accumulated text, 
            // but fallback to partial if accumulated is still empty (finalizing...)
            accumulated.ifEmpty { partial }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _durationSeconds = MutableStateFlow(0L)
    private var timerJob: Job? = null
    
    val engineState = speechRepository.engineState

    init {
        // Collect finalized segments for UI display ONLY
        // Persistence is handled by TranscriptionService to ensure reliability even if UI is closed
        viewModelScope.launch {
            speechRepository.transcriptionState.collect { entry ->
                if (entry.isFinal) {
                    _accumulatedText.value = if (_accumulatedText.value.isEmpty()) {
                        entry.text
                    } else {
                        "${_accumulatedText.value}\n${entry.text}"
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
        if (speechRepository.engineState.value != com.example.voxtranscribe.data.EngineState.Ready) return
        
        viewModelScope.launch {
            _accumulatedText.value = ""
            _durationSeconds.value = 0
            
            // 1. Create Note in DB
            val title = "Note @ ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}"
            currentNoteId = notesRepository.createNote(title)
            
            // 2. Start Service with Note ID
            val intent = Intent(context, TranscriptionService::class.java).apply {
                action = TranscriptionService.ACTION_START
                putExtra(TranscriptionService.EXTRA_NOTE_ID, currentNoteId)
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
        // Keep currentNoteId for navigation/reference if needed, but session is done
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
        viewModelScope.launch {
            notesRepository.deleteNote(note)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Don't cleanup repository here, service might be using it or we might want to keep model loaded
    }
}
