package com.example.voxtranscribe.data.gemma

import android.content.Context
import com.example.voxtranscribe.data.ai.AiOutputLanguage
import com.example.voxtranscribe.data.ai.AiSummaryStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GemmaSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _selectedModelId = MutableStateFlow(readSelectedModelId())
    val selectedModelId: StateFlow<GemmaModelId?> = _selectedModelId.asStateFlow()

    private val _aiOutputLanguage = MutableStateFlow(readAiOutputLanguage())
    val aiOutputLanguage: StateFlow<AiOutputLanguage> = _aiOutputLanguage.asStateFlow()

    private val _aiSummaryStyle = MutableStateFlow(readAiSummaryStyle())
    val aiSummaryStyle: StateFlow<AiSummaryStyle> = _aiSummaryStyle.asStateFlow()

    fun getSelectedModelSpec(): GemmaModelSpec? {
        return _selectedModelId.value?.let { selectedId ->
            GemmaModelCatalog.supportedModels.firstOrNull { it.id == selectedId }
        }
    }

    suspend fun setSelectedModelId(modelId: GemmaModelId?) {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putString(KEY_SELECTED_MODEL_ID, modelId?.name)
                .apply()
            _selectedModelId.value = modelId
        }
    }

    suspend fun setAiOutputLanguage(language: AiOutputLanguage) {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putString(KEY_AI_OUTPUT_LANGUAGE, language.name)
                .apply()
            _aiOutputLanguage.value = language
        }
    }

    suspend fun setAiSummaryStyle(style: AiSummaryStyle) {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putString(KEY_AI_SUMMARY_STYLE, style.name)
                .apply()
            _aiSummaryStyle.value = style
        }
    }

    private fun readSelectedModelId(): GemmaModelId? {
        val storedValue = prefs.getString(KEY_SELECTED_MODEL_ID, null) ?: return null
        return GemmaModelId.entries.firstOrNull { it.name == storedValue }
    }

    private fun readAiOutputLanguage(): AiOutputLanguage {
        val storedValue = prefs.getString(KEY_AI_OUTPUT_LANGUAGE, null)
            ?: return AiOutputLanguage.MATCH_TRANSCRIPT
        return AiOutputLanguage.entries.firstOrNull { it.name == storedValue }
            ?: AiOutputLanguage.MATCH_TRANSCRIPT
    }

    private fun readAiSummaryStyle(): AiSummaryStyle {
        val storedValue = prefs.getString(KEY_AI_SUMMARY_STYLE, null)
            ?: return AiSummaryStyle.EXECUTIVE
        return AiSummaryStyle.entries.firstOrNull { it.name == storedValue }
            ?: AiSummaryStyle.EXECUTIVE
    }

    private companion object {
        const val PREFS_NAME = "gemma_settings"
        const val KEY_SELECTED_MODEL_ID = "selected_model_id"
        const val KEY_AI_OUTPUT_LANGUAGE = "ai_output_language"
        const val KEY_AI_SUMMARY_STYLE = "ai_summary_style"
    }
}
