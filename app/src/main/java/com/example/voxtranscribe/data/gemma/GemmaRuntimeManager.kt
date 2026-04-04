package com.example.voxtranscribe.data.gemma

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tflite.gpu.support.TfLiteGpu
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class GemmaRuntimeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: GemmaSettingsRepository,
    private val importRepository: GemmaImportRepository,
) {
    private val mutex = Mutex()

    private var loadedModelId: GemmaModelId? = null
    private var loadedModelPath: String? = null
    private var loadedContextTokens: Int? = null
    private var loadedAudioEnabled = false
    private var engine: Engine? = null
    private var gpuAvailabilityChecked = false
    private var gpuDelegateAvailable = false

    suspend fun generateText(prompt: String, requestedGenerationTokens: Int = DEFAULT_GENERATION_TOKENS): String {
        return mutex.withLock {
            val selectedModelId = settingsRepository.selectedModelId.value
                ?: throw IllegalStateException("No Gemma model is selected.")
            val modelPath = importRepository.getImportedModelPath(selectedModelId)
                ?: throw IllegalStateException("The selected Gemma model is not imported.")
            val requiredContextTokens = requiredContextTokens(requestedGenerationTokens)

            ensureEngineLoaded(
                modelId = selectedModelId,
                modelPath = modelPath,
                requiredContextTokens = requiredContextTokens,
                enableAudio = false,
            )

            val conversation = createConversation()
            try {
                runConversation(conversation, listOf(Content.Text(prompt)))
            } finally {
                conversation.close()
            }
        }
    }

    suspend fun transcribeAudioClip(
        audioBytes: ByteArray,
        previousTranscriptTail: String?,
        requestedGenerationTokens: Int = DEFAULT_AUDIO_GENERATION_TOKENS,
    ): String {
        return mutex.withLock {
            val selectedModelId = settingsRepository.selectedModelId.value
                ?: throw IllegalStateException("No Gemma model is selected.")
            val modelPath = importRepository.getImportedModelPath(selectedModelId)
                ?: throw IllegalStateException("The selected Gemma model is not imported.")
            val requiredContextTokens = requiredContextTokens(requestedGenerationTokens)
            val transcriptionLanguage = settingsRepository.transcriptionLanguage.value

            ensureEngineLoaded(
                modelId = selectedModelId,
                modelPath = modelPath,
                requiredContextTokens = requiredContextTokens,
                enableAudio = true,
            )

            val conversation = createConversation(
                audioMode = true,
                systemInstruction = buildAudioSystemInstruction(transcriptionLanguage),
            )
            try {
                runConversation(
                    conversation = conversation,
                    contents = listOf(
                        Content.AudioBytes(audioBytes),
                        Content.Text(buildAudioUserPrompt(previousTranscriptTail)),
                    ),
                )
            } finally {
                conversation.close()
            }
        }
    }

    suspend fun cleanup() {
        mutex.withLock {
            closeEngine()
        }
    }

    private fun ensureEngineLoaded(
        modelId: GemmaModelId,
        modelPath: String,
        requiredContextTokens: Int,
        enableAudio: Boolean,
    ) {
        if (
            engine != null &&
            loadedModelId == modelId &&
            loadedModelPath == modelPath &&
            (loadedContextTokens ?: 0) >= requiredContextTokens &&
            (loadedAudioEnabled || !enableAudio)
        ) {
            return
        }

        closeEngine()

        val backendsToTry = buildBackendsToTry()
        var lastError: Throwable? = null

        for (backend in backendsToTry) {
            var createdEngine: Engine? = null
            try {
                createdEngine = Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        audioBackend = if (enableAudio) Backend.CPU() else null,
                        maxNumTokens = requiredContextTokens,
                    )
                )
                createdEngine.initialize()
                Log.i(TAG, "Initialized Gemma engine with backend=${backend.label()}")
                engine = createdEngine
                loadedModelId = modelId
                loadedModelPath = modelPath
                loadedContextTokens = requiredContextTokens
                loadedAudioEnabled = enableAudio
                return
            } catch (t: Throwable) {
                runCatching { createdEngine?.close() }
                Log.w(TAG, "Failed to initialize Gemma engine with backend=$backend", t)
                lastError = t
            }
        }

        throw IllegalStateException(
            "Failed to initialize the selected Gemma model.",
            lastError,
        )
    }

    private fun buildBackendsToTry(): List<Backend> {
        val backends = mutableListOf<Backend>()
        if (isGpuDelegateAvailable()) {
            backends += Backend.GPU()
        }
        backends += Backend.CPU()
        return backends
    }

    private fun isGpuDelegateAvailable(): Boolean {
        if (gpuAvailabilityChecked) {
            return gpuDelegateAvailable
        }

        gpuDelegateAvailable = try {
            Tasks.await(TfLiteGpu.isGpuDelegateAvailable(context))
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to determine LiteRT GPU delegate availability. Falling back to CPU.", t)
            false
        }
        gpuAvailabilityChecked = true
        Log.i(TAG, "LiteRT GPU delegate available=$gpuDelegateAvailable")
        return gpuDelegateAvailable
    }

    private fun createConversation(
        audioMode: Boolean = false,
        systemInstruction: String? = null,
    ): Conversation {
        val currentEngine = engine ?: throw IllegalStateException("Gemma engine is not initialized.")
        val samplerConfig = if (audioMode) {
            SamplerConfig(
                topK = 1,
                topP = 1.0,
                temperature = 0.0,
            )
        } else {
            SamplerConfig(
                topK = 64,
                topP = 0.95,
                temperature = 0.7,
            )
        }
        return currentEngine.createConversation(
            ConversationConfig(
                samplerConfig = samplerConfig,
                systemInstruction = systemInstruction
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Contents.of(Content.Text(it)) },
            )
        )
    }

    private suspend fun runConversation(
        conversation: Conversation,
        contents: List<Content>,
    ): String = suspendCancellableCoroutine { continuation ->
        val response = StringBuilder()

        conversation.sendMessageAsync(
            Contents.of(contents),
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    response.append(message.toString())
                }

                override fun onDone() {
                    if (continuation.isActive) {
                        continuation.resume(response.toString().trim())
                    }
                }

                override fun onError(throwable: Throwable) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(throwable)
                    }
                }
            }
        )

        continuation.invokeOnCancellation {
            conversation.cancelProcess()
        }
    }

    private fun closeEngine() {
        engine?.close()
        engine = null
        loadedModelId = null
        loadedModelPath = null
        loadedContextTokens = null
        loadedAudioEnabled = false
    }

    private fun requiredContextTokens(requestedGenerationTokens: Int): Int {
        return max(MIN_CONTEXT_TOKENS, requestedGenerationTokens + CONTEXT_HEADROOM_TOKENS)
    }

    private fun buildAudioUserPrompt(previousTranscriptTail: String?): String {
        val tail = previousTranscriptTail?.trim().orEmpty()
        return if (tail.isBlank()) {
            AUDIO_USER_PROMPT_NO_CONTEXT
        } else {
            AUDIO_USER_PROMPT_WITH_CONTEXT_TEMPLATE.format(tail)
        }
    }

    private fun buildAudioSystemInstruction(
        language: GemmaTranscriptionLanguage,
    ): String {
        val languageLabel = language.promptLabel
        return if (languageLabel == null) {
            DEFAULT_AUDIO_SYSTEM_INSTRUCTION
        } else {
            "Transcribe only the spoken audio. The spoken language is $languageLabel. " +
                "Return only the spoken words in $languageLabel. Do not translate. " +
                "Do not explain. Do not answer the speaker. Do not repeat instructions or prior context."
        }
    }

    companion object {
        private const val TAG = "GemmaRuntimeManager"
        private const val DEFAULT_GENERATION_TOKENS = 1024
        private const val DEFAULT_AUDIO_GENERATION_TOKENS = 256
        private const val MIN_CONTEXT_TOKENS = 2048
        private const val CONTEXT_HEADROOM_TOKENS = 512
        private const val DEFAULT_AUDIO_SYSTEM_INSTRUCTION =
            "Transcribe only the spoken audio. Preserve the original language. " +
                "Return only the spoken words. Do not translate. Do not explain. Do not answer the speaker. " +
                "Do not repeat instructions or prior context."
        private const val AUDIO_USER_PROMPT_NO_CONTEXT =
            "Return only the transcript for this audio clip."
        private const val AUDIO_USER_PROMPT_WITH_CONTEXT_TEMPLATE =
            "Continue after this confirmed context and do not repeat it:%n%s%nReturn only the new transcript for this audio clip."
    }
}

private fun Backend.label(): String {
    return when (this) {
        is Backend.CPU -> "cpu"
        is Backend.GPU -> "gpu"
        is Backend.NPU -> "npu"
        else -> this::class.simpleName ?: "unknown"
    }
}
