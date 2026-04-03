package com.example.voxtranscribe.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voxtranscribe.data.gemma.GemmaImportRepository
import com.example.voxtranscribe.data.gemma.GemmaImportResult
import com.example.voxtranscribe.data.gemma.GemmaImportedModelStatus
import com.example.voxtranscribe.data.gemma.GemmaModelId
import com.example.voxtranscribe.data.gemma.GemmaSettingsRepository
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
    val isImporting: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class GemmaModelViewModel @Inject constructor(
    private val settingsRepository: GemmaSettingsRepository,
    private val importRepository: GemmaImportRepository,
) : ViewModel() {

    private val _isImporting = MutableStateFlow(false)
    private val _message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<GemmaModelUiState> = combine(
        importRepository.modelStatuses,
        settingsRepository.selectedModelId,
        _isImporting,
        _message,
    ) { statuses, selectedModelId, isImporting, message ->
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

    fun clearMessage() {
        _message.value = null
    }
}
