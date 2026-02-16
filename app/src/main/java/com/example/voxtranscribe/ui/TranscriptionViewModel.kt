package com.example.voxtranscribe.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voxtranscribe.domain.TranscriptionRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TranscriptionStats(
    val isOffline: Boolean = false,
    val durationSeconds: Long = 0,
    val wordCount: Int = 0
)

class TranscriptionViewModel(private val repository: TranscriptionRepository) : ViewModel() {

    val transcriptionState: StateFlow<String> = repository.transcriptionState
    
    private val _amplitudes = MutableStateFlow<List<Float>>(emptyList())
    val amplitudes: StateFlow<List<Float>> = _amplitudes.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _durationSeconds = MutableStateFlow(0L)
    private var timerJob: Job? = null

    val stats: StateFlow<TranscriptionStats> = combine(
        repository.isOfflineModel,
        _durationSeconds,
        transcriptionState
    ) { isOffline, duration, text ->
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        TranscriptionStats(isOffline, duration, words)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TranscriptionStats())

    init {
        repository.amplitudeState
            .onEach { amplitude ->
                _amplitudes.value = (_amplitudes.value + amplitude).takeLast(50)
            }
            .launchIn(viewModelScope)
    }

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
        repository.clear()
        _durationSeconds.value = 0
    }

    override fun onCleared() {
        super.onCleared()
        repository.cleanup()
    }
}
