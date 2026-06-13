package com.example.voxtranscribe.data.parakeet

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

@Singleton
class ParakeetSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _selectedModelId = MutableStateFlow(readSelectedModelId())
    val selectedModelId: StateFlow<ParakeetModelId?> = _selectedModelId.asStateFlow()

    private val _transcriptionLanguage = MutableStateFlow(readTranscriptionLanguage())
    val transcriptionLanguage: StateFlow<ParakeetTranscriptionLanguage> = _transcriptionLanguage.asStateFlow()

    private val _showDebugStats = MutableStateFlow(readShowDebugStats())
    val showDebugStats: StateFlow<Boolean> = _showDebugStats.asStateFlow()

    suspend fun setSelectedModelId(modelId: ParakeetModelId?) {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putString(KEY_SELECTED_MODEL_ID, modelId?.name)
                .apply()
            _selectedModelId.value = modelId
        }
    }

    suspend fun setTranscriptionLanguage(language: ParakeetTranscriptionLanguage) {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putString(KEY_TRANSCRIPTION_LANGUAGE, language.name)
                .apply()
            _transcriptionLanguage.value = language
        }
    }

    suspend fun setShowDebugStats(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putBoolean(KEY_SHOW_DEBUG_STATS, enabled)
                .apply()
            _showDebugStats.value = enabled
        }
    }

    private fun readSelectedModelId(): ParakeetModelId? {
        val storedValue = prefs.getString(KEY_SELECTED_MODEL_ID, null) ?: return null
        return ParakeetModelId.entries.firstOrNull { it.name == storedValue }
    }

    private fun readTranscriptionLanguage(): ParakeetTranscriptionLanguage {
        val storedValue = prefs.getString(KEY_TRANSCRIPTION_LANGUAGE, null)
            ?: return ParakeetTranscriptionLanguage.AUTO
        return ParakeetTranscriptionLanguage.entries.firstOrNull { it.name == storedValue }
            ?: ParakeetTranscriptionLanguage.AUTO
    }

    private fun readShowDebugStats(): Boolean {
        return prefs.getBoolean(KEY_SHOW_DEBUG_STATS, false)
    }

    private companion object {
        const val PREFS_NAME = "parakeet_settings"
        const val KEY_SELECTED_MODEL_ID = "selected_model_id"
        const val KEY_TRANSCRIPTION_LANGUAGE = "transcription_language"
        const val KEY_SHOW_DEBUG_STATS = "show_debug_stats"
    }
}
