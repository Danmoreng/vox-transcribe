package com.example.voxtranscribe.data.parakeet

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ParakeetRuntimeStatus(
    val abiVersion: Int? = null,
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
            val handle = ParakeetNative.beginStream(modelHandle, language.targetLang)
            if (handle == 0L) {
                val error = lastNativeErrorLocked().ifBlank {
                    "The selected model did not start a streaming session."
                }
                _runtimeStatus.value = _runtimeStatus.value.copy(lastError = error)
                throw IllegalStateException(error)
            }

            streamHandle = handle
            _runtimeStatus.value = _runtimeStatus.value.copy(
                activeBackend = ParakeetNative.activeBackendName(),
                activeLanguage = language.targetLang,
                lastError = null,
            )
        }
    }

    suspend fun feed(samples: FloatArray): ParakeetStreamResult {
        return mutex.withLock {
            val handle = streamHandle
            check(handle != 0L) { "Parakeet stream is not active." }

            val text = ParakeetNative.feedStream(handle, samples)
            if (text == null) {
                val error = lastNativeErrorLocked().ifBlank { "Parakeet stream feed failed." }
                _runtimeStatus.value = _runtimeStatus.value.copy(lastError = error)
                throw IllegalStateException(error)
            }
            ParakeetStreamResult(
                text = text,
                isEndOfUtterance = ParakeetNative.lastFeedHadEou(handle),
            )
        }
    }

    suspend fun finalizeStream(): String {
        return mutex.withLock {
            val handle = streamHandle
            if (handle == 0L) {
                return@withLock ""
            }

            val text = ParakeetNative.finalizeStream(handle)
            closeStreamLocked()
            if (text == null) {
                val error = lastNativeErrorLocked().ifBlank { "Parakeet stream finalization failed." }
                _runtimeStatus.value = _runtimeStatus.value.copy(lastError = error)
                throw IllegalStateException(error)
            }
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
            ?: throw IllegalStateException("Import and select the Nemotron streaming GGUF before recording.")
        val modelPath = importRepository.getImportedModelPath(selectedModelId)
            ?: throw IllegalStateException("The selected Parakeet model is not imported.")

        if (
            modelHandle != 0L &&
            loadedModelId == selectedModelId &&
            loadedModelPath == modelPath
        ) {
            return
        }

        closeStreamLocked()
        closeModelLocked()

        val threadCount = Runtime.getRuntime().availableProcessors().coerceIn(2, MAX_CPU_THREADS)
        ParakeetNative.setThreadCount(threadCount)
        val handle = ParakeetNative.loadModel(modelPath)
        if (handle == 0L) {
            throw IllegalStateException("Failed to load the selected Parakeet GGUF.")
        }

        modelHandle = handle
        loadedModelId = selectedModelId
        loadedModelPath = modelPath
        _runtimeStatus.value = ParakeetRuntimeStatus(
            abiVersion = ParakeetNative.abiVersion(),
            activeBackend = "loading",
            activeLanguage = settingsRepository.transcriptionLanguage.value.targetLang,
            activeModelPath = modelPath,
            lastError = null,
        )
        Log.i(
            TAG,
            "Loaded Parakeet model from $modelPath with backend " +
                "${ParakeetNative.activeBackendName()} and $threadCount CPU fallback threads",
        )
    }

    private fun closeStreamLocked() {
        if (streamHandle != 0L) {
            ParakeetNative.freeStream(streamHandle)
            streamHandle = 0L
        }
    }

    private fun closeModelLocked() {
        if (modelHandle != 0L) {
            ParakeetNative.freeModel(modelHandle)
            modelHandle = 0L
        }
        loadedModelId = null
        loadedModelPath = null
    }

    private fun lastNativeErrorLocked(): String {
        return if (modelHandle == 0L) "" else ParakeetNative.lastError(modelHandle)
    }

    private companion object {
        const val TAG = "ParakeetRuntime"
        const val MAX_CPU_THREADS = 6
    }
}
