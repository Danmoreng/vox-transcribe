package com.example.voxtranscribe.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voxtranscribe.domain.TranscriptionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class TranscriptionViewModel(private val repository: TranscriptionRepository) : ViewModel() {

    val transcriptionState: StateFlow<String> = repository.transcriptionState
    
    private val _amplitudes = MutableStateFlow<List<Float>>(emptyList())
    val amplitudes: StateFlow<List<Float>> = _amplitudes.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    init {
        repository.amplitudeState
            .onEach { amplitude ->
                _amplitudes.value = (_amplitudes.value + amplitude).takeLast(50)
            }
            .launchIn(viewModelScope)
    }

    fun toggleListening() {
        if (_isListening.value) {
            repository.stopListening()
            _isListening.value = false
        } else {
            repository.startListening()
            _isListening.value = true
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.cleanup()
    }
}
