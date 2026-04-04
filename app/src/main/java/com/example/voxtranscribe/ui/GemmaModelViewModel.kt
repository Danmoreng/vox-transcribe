package com.example.voxtranscribe.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voxtranscribe.data.gemma.GemmaImportRepository
import com.example.voxtranscribe.data.gemma.GemmaImportResult
import com.example.voxtranscribe.data.gemma.GemmaImportedModelStatus
import com.example.voxtranscribe.data.gemma.GemmaModelId
import com.example.voxtranscribe.data.gemma.GemmaRuntimeManager
import com.example.voxtranscribe.data.gemma.GemmaSettingsRepository
import com.example.voxtranscribe.data.gemma.GemmaTranscriptionLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GemmaModelCardUiState(
    val status: GemmaImportedModelStatus,
    val isSelected: Boolean,
)

data class GemmaModelUiState(
    val models: List<GemmaModelCardUiState> = emptyList(),
    val selectedModelId: GemmaModelId? = null,
    val transcriptionLanguage: GemmaTranscriptionLanguage = GemmaTranscriptionLanguage.AUTO,
    val runtimeGpuDelegateAvailable: Boolean? = null,
    val runtimeActiveBackend: String? = null,
    val runtimeAudioBackend: String? = null,
    val runtimeFallbackReason: String? = null,
    val isImporting: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class GemmaModelViewModel @Inject constructor(
    private val settingsRepository: GemmaSettingsRepository,
    private val importRepository: GemmaImportRepository,
    private val runtimeManager: GemmaRuntimeManager,
) : ViewModel() {

    private val _isImporting = MutableStateFlow(false)
    private val _message = MutableStateFlow<String?>(null)

    private val settingsUiState = combine(
        importRepository.modelStatuses,
        settingsRepository.selectedModelId,
        settingsRepository.transcriptionLanguage,
        runtimeManager.runtimeStatus,
    ) { statuses, selectedModelId, transcriptionLanguage, runtimeStatus ->
        val installedModelIds = statuses.filter { it.isImported }.map { it.spec.id }.toSet()
        val resolvedSelectedModelId = selectedModelId?.takeIf { installedModelIds.contains(it) }
        GemmaModelUiState(
            models = statuses.map { status ->
                GemmaModelCardUiState(
                    status = status,
                    isSelected = resolvedSelectedModelId == status.spec.id,
                )
            },
            selectedModelId = resolvedSelectedModelId,
            transcriptionLanguage = transcriptionLanguage,
            runtimeGpuDelegateAvailable = runtimeStatus.gpuDelegateAvailable,
            runtimeActiveBackend = runtimeStatus.activeBackend,
            runtimeAudioBackend = runtimeStatus.audioBackend,
            runtimeFallbackReason = runtimeStatus.fallbackReason,
        )
    }

    val uiState: StateFlow<GemmaModelUiState> = combine(
        settingsUiState,
        _isImporting,
        _message,
    ) { baseState, isImporting, message ->
        baseState.copy(
            isImporting = isImporting,
            message = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = GemmaModelUiState(),
    )

    init {
        importRepository.refresh()
        viewModelScope.launch {
            val statuses = importRepository.modelStatuses.value
            val selectedModelId = settingsRepository.selectedModelId.value
            val selectedStillInstalled = statuses.any { it.spec.id == selectedModelId && it.isImported }
            if (!selectedStillInstalled) {
                val fallback = statuses.firstOrNull { it.isImported }?.spec?.id
                settingsRepository.setSelectedModelId(fallback)
            }
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            when (val result = importRepository.importModelFromUri(uri)) {
                is GemmaImportResult.Success -> {
                    settingsRepository.setSelectedModelId(result.model.id)
                    _message.value = "${result.model.displayName} imported successfully."
                }
                is GemmaImportResult.UnsupportedFile -> {
                    _message.value = result.message
                }
                is GemmaImportResult.Failure -> {
                    _message.value = result.message
                }
            }
            _isImporting.value = false
        }
    }

    fun selectModel(modelId: GemmaModelId) {
        viewModelScope.launch {
            settingsRepository.setSelectedModelId(modelId)
            _message.value = "Selected ${modelId.name.replace('_', ' ')}."
        }
    }

    fun deleteModel(modelId: GemmaModelId) {
        viewModelScope.launch {
            val deleted = importRepository.deleteImportedModel(modelId)
            if (deleted) {
                val remainingImported = importRepository.modelStatuses.value.filter { it.isImported }
                val fallbackSelection = remainingImported.firstOrNull()?.spec?.id
                if (settingsRepository.selectedModelId.value == modelId) {
                    settingsRepository.setSelectedModelId(fallbackSelection)
                }
                _message.value = "Removed imported model."
            } else {
                _message.value = "Failed to remove the imported model."
            }
        }
    }

    fun setTranscriptionLanguage(language: GemmaTranscriptionLanguage) {
        viewModelScope.launch {
            settingsRepository.setTranscriptionLanguage(language)
            _message.value = "Transcription language set to ${language.displayName}."
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
