package com.example.voxtranscribe.data.parakeet

import android.util.Log
import com.example.voxtranscribe.data.nemotron.NemotronNative
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ParakeetRuntimeStatus(
    val activeBackend: String? = null,
    val activeLanguage: String? = null,
    val activeModelPath: String? = null,
    val lastError: String? = null,
)

data class ParakeetStreamResult(
    val text: String,
    val isEndOfUtterance: Boolean,
)

@Singleton
class ParakeetRuntimeManager @Inject constructor(
    private val settingsRepository: ParakeetSettingsRepository,
    private val importRepository: ParakeetImportRepository,
) {
    private val mutex = Mutex()
    private val _runtimeStatus = MutableStateFlow(ParakeetRuntimeStatus())
    val runtimeStatus: StateFlow<ParakeetRuntimeStatus> = _runtimeStatus.asStateFlow()

    private var loadedModelId: ParakeetModelId? = null
    private var loadedModelPath: String? = null
    private var modelHandle: Long = 0L
    private var streamHandle: Long = 0L

    suspend fun beginStream() {
        mutex.withLock {
            ensureModelLoaded()
            closeStreamLocked()

            val language = settingsRepository.transcriptionLanguage.value
            val handle = NemotronNative.beginStream(modelHandle, language.nemotronLanguageId)
            if (handle == 0L) {
                val error = "The selected Nemotron ONNX model did not start a streaming session."
                _runtimeStatus.value = _runtimeStatus.value.copy(lastError = error)
                throw IllegalStateException(error)
            }

            streamHandle = handle
            _runtimeStatus.value = _runtimeStatus.value.copy(
                activeBackend = "ort-cpu",
                activeLanguage = language.targetLang,
                lastError = null,
            )
        }
    }

    suspend fun feed(samples: FloatArray): ParakeetStreamResult {
        return mutex.withLock {
            val handle = streamHandle
            check(handle != 0L) { "Parakeet stream is not active." }

            ParakeetStreamResult(
                text = NemotronNative.feedStream(handle, samples),
                isEndOfUtterance = false,
            )
        }
    }

    suspend fun finalizeStream(): String {
        return mutex.withLock {
            val handle = streamHandle
            if (handle == 0L) {
                return@withLock ""
            }

            val text = NemotronNative.finalizeStream(handle)
            closeStreamLocked()
            text
        }
    }

    suspend fun cleanup() {
        mutex.withLock {
            closeStreamLocked()
            closeModelLocked()
        }
    }

    private fun ensureModelLoaded() {
        val selectedModelId = settingsRepository.selectedModelId.value
            ?: throw IllegalStateException("Import and select the Nemotron ONNX ZIP before recording.")
        val modelPath = importRepository.getImportedModelPath(selectedModelId)
            ?: throw IllegalStateException("The selected Nemotron ONNX model is not imported.")

        if (
            modelHandle != 0L &&
            loadedModelId == selectedModelId &&
            loadedModelPath == modelPath
        ) {
            return
        }

        closeStreamLocked()
        closeModelLocked()

        val handle = NemotronNative.loadModel(modelPath)
        if (handle == 0L) {
            throw IllegalStateException("Failed to load the selected Nemotron ONNX model.")
        }

        modelHandle = handle
        loadedModelId = selectedModelId
        loadedModelPath = modelPath
        _runtimeStatus.value = ParakeetRuntimeStatus(
            activeBackend = "ort-cpu",
            activeLanguage = settingsRepository.transcriptionLanguage.value.targetLang,
            activeModelPath = modelPath,
            lastError = null,
        )
        Log.i(
            TAG,
            "Loaded Nemotron ONNX model from $modelPath with ONNX Runtime GenAI CPU backend",
        )
    }

    private fun closeStreamLocked() {
        if (streamHandle != 0L) {
            NemotronNative.freeStream(streamHandle)
            streamHandle = 0L
        }
    }

    private fun closeModelLocked() {
        if (modelHandle != 0L) {
            NemotronNative.freeModel(modelHandle)
            modelHandle = 0L
        }
        loadedModelId = null
        loadedModelPath = null
    }

    private companion object {
        const val TAG = "ParakeetRuntime"
    }
}
