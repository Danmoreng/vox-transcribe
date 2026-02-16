package com.example.voxtranscribe.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voxtranscribe.domain.TranscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TranscriptionStats(
    val isOffline: Boolean = false,
    val durationSeconds: Long = 0,
    val wordCount: Int = 0
)

@HiltViewModel
class TranscriptionViewModel @Inject constructor(private val repository: TranscriptionRepository) : ViewModel() {

    private val _accumulatedText = MutableStateFlow("")
    
    val transcriptionState: StateFlow<String> = combine(
        _accumulatedText,
        repository.partialText
    ) { accumulated, partial ->
        if (partial.isEmpty()) accumulated else if (accumulated.isEmpty()) partial else "$accumulated\n$partial"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _durationSeconds = MutableStateFlow(0L)
    private var timerJob: Job? = null

    init {
        viewModelScope.launch {
            repository.transcriptionState.collect { entry ->
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
        repository.isOfflineModel,
        _durationSeconds,
        transcriptionState
    ) { isOffline, duration, text ->
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        TranscriptionStats(isOffline, duration, words)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TranscriptionStats())

    fun toggleListening() {
        if (_isListening.value) {
            stopTimer()
            repository.stopListening()
            _isListening.value = false
        } else {
            startTimer()
            repository.startListening()
            _isListening.value = true
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

    fun clearText() {
        _accumulatedText.value = ""
        repository.clear()
        _durationSeconds.value = 0
    }

    override fun onCleared() {
        super.onCleared()
        repository.cleanup()
    }
}
