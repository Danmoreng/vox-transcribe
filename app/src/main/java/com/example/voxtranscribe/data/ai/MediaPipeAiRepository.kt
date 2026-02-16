package com.example.voxtranscribe.data.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Singleton
class MediaPipeAiRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadManager: ModelDownloadManager
) : AiRepository {

    private val TAG = "MediaPipeAiRepository"
    
    // Path where the model should be located. 
    private val modelName = "gemma3-1b-it-int4.litertlm" 
    private val modelFile = File(context.filesDir, modelName)
    
    private var llmInference: LlmInference? = null

    private fun initLlm(): Boolean {
        if (llmInference != null) return true
        
        if (!downloadManager.isModelAvailable()) {
            Log.e(TAG, "Gemma 3n model file not found at ${modelFile.absolutePath}")
            return false
        }

        return try {
            Log.d(TAG, "Initializing LlmInference with ${modelFile.absolutePath}")
            val options = LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(2048)
                .setPreferredBackend(LlmInference.Backend.GPU)
                .build()
            
            llmInference = LlmInference.createFromOptions(context, options)
            Log.d(TAG, "LlmInference initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPipe LlmInference with Gemma 3n", e)
            false
        }
    }

    override suspend fun summarize(transcript: String): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "summarize() started. Length: ${transcript.length}")
        val startTime = System.currentTimeMillis()
        if (!initLlm()) return@withContext "AI Error: Model not ready."

        try {
            val prompt = """
                <start_of_turn>user
                Provide a short executive summary of this transcript:
                
                $transcript<end_of_turn>
                <start_of_turn>model
                """.trimIndent()
            
            Log.d(TAG, "Running inference...")
            // We use the synchronous version but with a background dispatcher
            val result = llmInference?.generateResponse(prompt)
            
            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Inference finished in ${duration}ms. Result length: ${result?.length}")
            
            result?.trim() ?: "No summary generated"
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            "AI Error: ${e.message}"
        }
    }

    override suspend fun generateMeetingNotes(transcript: String): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "generateMeetingNotes() started")
        val startTime = System.currentTimeMillis()
        if (!initLlm()) return@withContext "AI Error: Model not ready."

        try {
            val prompt = """
                <start_of_turn>user
                Generate bulleted meeting notes:
                
                $transcript<end_of_turn>
                <start_of_turn>model
                """.trimIndent()
                
            val result = llmInference?.generateResponse(prompt)
            
            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Notes inference finished in ${duration}ms")
            
            result?.trim() ?: "No notes generated"
        } catch (e: Exception) {
            Log.e(TAG, "Notes generation failed", e)
            "AI Error: ${e.message}"
        }
    }

    override suspend fun generateTitle(transcript: String): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "generateTitle() started")
        if (!initLlm()) return@withContext "Untitled Note"

        try {
            val prompt = """
                <start_of_turn>user
                Generate a very short, professional title (max 5 words) for the following transcript. Return ONLY the title text without quotes:
                
                $transcript<end_of_turn>
                <start_of_turn>model
                """.trimIndent()
                
            val result = llmInference?.generateResponse(prompt)
            result?.trim()?.removePrefix("\"")?.removeSuffix("\"") ?: "Untitled Note"
        } catch (e: Exception) {
            Log.e(TAG, "Title generation failed", e)
            "Untitled Note"
        }
    }
}
