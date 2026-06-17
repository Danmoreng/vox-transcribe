package com.example.voxtranscribe.data.gemma

import android.content.Context
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

    private fun readSelectedModelId(): GemmaModelId? {
        val storedValue = prefs.getString(KEY_SELECTED_MODEL_ID, null) ?: return null
        val storedModelId = GemmaModelId.entries.firstOrNull { it.name == storedValue } ?: return null
        return storedModelId.takeIf { modelId ->
            GemmaModelCatalog.supportedModels.any { it.id == modelId }
        }
    }

    private companion object {
        const val PREFS_NAME = "gemma_settings"
        const val KEY_SELECTED_MODEL_ID = "selected_model_id"
    }
}
