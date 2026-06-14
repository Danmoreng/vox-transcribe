package com.example.voxtranscribe.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voxtranscribe.data.AppLanguage
import com.example.voxtranscribe.data.AppLanguageRepository
import com.example.voxtranscribe.data.ModelDownloadProgress
import com.example.voxtranscribe.data.gemma.GemmaDownloadResult
import com.example.voxtranscribe.data.gemma.GemmaImportRepository
import com.example.voxtranscribe.data.gemma.GemmaModelCatalog
import com.example.voxtranscribe.data.gemma.GemmaSettingsRepository
import com.example.voxtranscribe.data.parakeet.ParakeetDownloadResult
import com.example.voxtranscribe.data.parakeet.ParakeetImportRepository
import com.example.voxtranscribe.data.parakeet.ParakeetModelCatalog
import com.example.voxtranscribe.data.parakeet.ParakeetSettingsRepository
import com.example.voxtranscribe.data.parakeet.ParakeetTranscriptionLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GemmaModelUiState(
    val hasSpeechModel: Boolean = false,
    val hasTextAiModel: Boolean = false,
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val parakeetTranscriptionLanguage: ParakeetTranscriptionLanguage = ParakeetTranscriptionLanguage.AUTO,
    val showDebugStats: Boolean = false,
    val isImporting: Boolean = false,
    val downloadProgress: ModelDownloadProgress? = null,
    val speechModelDownloadSizeBytes: Long = ParakeetModelCatalog.streamingModel.downloadSizeBytes,
    val textAiModelDownloadSizeBytes: Long = GemmaModelCatalog.recommendedTextModel.downloadSizeBytes,
) {
    val setupComplete: Boolean
        get() = hasSpeechModel && hasTextAiModel
}

@HiltViewModel
class GemmaModelViewModel @Inject constructor(
    private val appLanguageRepository: AppLanguageRepository,
    private val settingsRepository: GemmaSettingsRepository,
    private val importRepository: GemmaImportRepository,
    private val parakeetSettingsRepository: ParakeetSettingsRepository,
    private val parakeetImportRepository: ParakeetImportRepository,
) : ViewModel() {

    private val _isImporting = MutableStateFlow(false)
    private val _downloadProgress = MutableStateFlow<ModelDownloadProgress?>(null)

    val uiState: StateFlow<GemmaModelUiState> = combine(
        importRepository.modelStatuses,
        parakeetImportRepository.modelStatuses,
        appLanguageRepository.appLanguage,
        parakeetSettingsRepository.transcriptionLanguage,
        parakeetSettingsRepository.showDebugStats,
        _isImporting,
        _downloadProgress,
    ) { values ->
        val gemmaStatuses = values[0] as List<*>
        val parakeetStatuses = values[1] as List<*>
        @Suppress("UNCHECKED_CAST")
        val typedGemmaStatuses = gemmaStatuses as List<com.example.voxtranscribe.data.gemma.GemmaImportedModelStatus>
        @Suppress("UNCHECKED_CAST")
        val typedParakeetStatuses = parakeetStatuses as List<com.example.voxtranscribe.data.parakeet.ParakeetImportedModelStatus>

        GemmaModelUiState(
            hasTextAiModel = typedGemmaStatuses.any { it.isImported },
            hasSpeechModel = typedParakeetStatuses.any { it.isImported },
            appLanguage = values[2] as AppLanguage,
            parakeetTranscriptionLanguage = values[3] as ParakeetTranscriptionLanguage,
            showDebugStats = values[4] as Boolean,
            isImporting = values[5] as Boolean,
            downloadProgress = values[6] as ModelDownloadProgress?,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = GemmaModelUiState(),
    )

    init {
        importRepository.refresh()
        parakeetImportRepository.refresh()
        ensureSelectedModels()
    }

    fun downloadParakeetModel() {
        viewModelScope.launch {
            _isImporting.value = true
            val result = parakeetImportRepository.downloadDefaultModel { progress ->
                _downloadProgress.value = progress
            }
            when (result) {
                is ParakeetDownloadResult.Success -> {
                    parakeetSettingsRepository.setSelectedModelId(result.model.id)
                }
                is ParakeetDownloadResult.Failure -> Unit
            }
            _downloadProgress.value = null
            _isImporting.value = false
        }
    }

    fun downloadRecommendedTextAiModel() {
        viewModelScope.launch {
            _isImporting.value = true
            val result = importRepository.downloadRecommendedTextModel { progress ->
                _downloadProgress.value = progress
            }
            when (result) {
                is GemmaDownloadResult.Success -> {
                    settingsRepository.setSelectedModelId(result.model.id)
                }
                is GemmaDownloadResult.Failure -> Unit
            }
            _downloadProgress.value = null
            _isImporting.value = false
        }
    }

    fun setParakeetTranscriptionLanguage(language: ParakeetTranscriptionLanguage) {
        viewModelScope.launch {
            parakeetSettingsRepository.setTranscriptionLanguage(language)
        }
    }

    fun setAppLanguage(language: AppLanguage, onApplied: () -> Unit = {}) {
        viewModelScope.launch {
            appLanguageRepository.setAppLanguage(language)
            onApplied()
        }
    }

    fun setShowDebugStats(enabled: Boolean) {
        viewModelScope.launch {
            parakeetSettingsRepository.setShowDebugStats(enabled)
        }
    }

    private fun ensureSelectedModels() {
        viewModelScope.launch {
            val statuses = importRepository.modelStatuses.value
            val selectedModelId = settingsRepository.selectedModelId.value
            val selectedStillInstalled = statuses.any { it.spec.id == selectedModelId && it.isImported }
            if (!selectedStillInstalled) {
                settingsRepository.setSelectedModelId(statuses.firstOrNull { it.isImported }?.spec?.id)
            }
        }
        viewModelScope.launch {
            val statuses = parakeetImportRepository.modelStatuses.value
            val selectedModelId = parakeetSettingsRepository.selectedModelId.value
            val selectedStillInstalled = statuses.any { it.spec.id == selectedModelId && it.isImported }
            if (!selectedStillInstalled) {
                parakeetSettingsRepository.setSelectedModelId(statuses.firstOrNull { it.isImported }?.spec?.id)
            }
        }
    }
}
