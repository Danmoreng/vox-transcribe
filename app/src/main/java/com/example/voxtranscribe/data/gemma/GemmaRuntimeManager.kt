package com.example.voxtranscribe.data.gemma

import android.content.Context
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
    private var engine: Engine? = null

    suspend fun generateText(prompt: String, maxTokens: Int = 1024): String {
        return mutex.withLock {
            val selectedModelId = settingsRepository.selectedModelId.value
                ?: throw IllegalStateException("No Gemma model is selected.")
            val modelPath = importRepository.getImportedModelPath(selectedModelId)
                ?: throw IllegalStateException("The selected Gemma model is not imported.")

            ensureEngineLoaded(
                modelId = selectedModelId,
                modelPath = modelPath,
                maxTokens = maxTokens,
            )

            val conversation = createConversation()
            try {
                runConversation(conversation, prompt)
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
        maxTokens: Int,
    ) {
        if (engine != null && loadedModelId == modelId && loadedModelPath == modelPath) {
            return
        }

        closeEngine()

        val backendsToTry = listOf(Backend.GPU(), Backend.CPU())
        var lastError: Exception? = null

        for (backend in backendsToTry) {
            try {
                val createdEngine = Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        maxNumTokens = maxTokens,
                    )
                )
                createdEngine.initialize()
                engine = createdEngine
                loadedModelId = modelId
                loadedModelPath = modelPath
                return
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw IllegalStateException(
            "Failed to initialize the selected Gemma model.",
            lastError,
        )
    }

    private fun createConversation(): Conversation {
        val currentEngine = engine ?: throw IllegalStateException("Gemma engine is not initialized.")
        return currentEngine.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(
                    topK = 64,
                    topP = 0.95,
                    temperature = 0.7,
                )
            )
        )
    }

    private suspend fun runConversation(
        conversation: Conversation,
        prompt: String,
    ): String = suspendCancellableCoroutine { continuation ->
        val response = StringBuilder()

        conversation.sendMessageAsync(
            Contents.of(Content.Text(prompt)),
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
    }
}
