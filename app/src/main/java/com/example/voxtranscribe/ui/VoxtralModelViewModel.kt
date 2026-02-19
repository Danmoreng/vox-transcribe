package com.example.voxtranscribe.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voxtranscribe.data.EngineState
import com.example.voxtranscribe.data.VoxtralModelManager
import com.example.voxtranscribe.data.VoxtralTranscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VoxtralModelViewModel @Inject constructor(
    private val modelManager: VoxtralModelManager,
    private val voxtralRepo: VoxtralTranscriptionRepository
) : ViewModel() {

    private val _isModelAvailable = MutableStateFlow(false)
    val isModelAvailable: StateFlow<Boolean> = _isModelAvailable.asStateFlow()

    private val _isCopying = MutableStateFlow(false)
    val isCopying: StateFlow<Boolean> = _isCopying.asStateFlow()

    private val _copyResult = MutableStateFlow<String?>(null)
    val copyResult: StateFlow<String?> = _copyResult.asStateFlow()
    
    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    val engineState: StateFlow<EngineState> = voxtralRepo.engineState

    init {
        checkModelStatus()
        if (_isModelAvailable.value) {
            loadEngine()
        }
    }

    fun checkModelStatus() {
        _isModelAvailable.value = modelManager.isModelAvailable()
    }

    fun copyModel(uri: Uri) {
        viewModelScope.launch {
            _isCopying.value = true
            _copyResult.value = null
            
            val success = modelManager.copyModelFromUri(uri)
            
            _isCopying.value = false
            if (success) {
                _copyResult.value = "Model imported successfully!"
                checkModelStatus()
                loadEngine()
            } else {
                _copyResult.value = "Failed to copy model."
            }
        }
    }
    
    fun loadEngine() {
        voxtralRepo.loadModel()
    }
    
    fun runTest() {
        viewModelScope.launch {
            _testResult.value = "Running test..."
            val result = voxtralRepo.transcribeTestAudio()
            _testResult.value = result
        }
    }
    
    fun clearResult() {
        _copyResult.value = null
        _testResult.value = null
    }
}
