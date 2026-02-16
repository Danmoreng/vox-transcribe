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

@Singleton
class MediaPipeAiRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : AiRepository {

    private val TAG = "MediaPipeAiRepository"
    
    // Path where the model should be located. 
    // Gemma 3n is the recommended high-performance SLM.
    private val modelName = "gemma3n-4b-asia-cpu-int4.bin" 
    private val modelFile = File(context.filesDir, modelName)
    
    private var llmInference: LlmInference? = null

    private fun initLlm(): Boolean {
        if (llmInference != null) return true
        
        if (!modelFile.exists()) {
            Log.e(TAG, "Gemma 3n model file not found at ${modelFile.absolutePath}")
            return false
        }

        return try {
            // Using a simplified builder configuration to resolve compilation issues.
            // The MediaPipe version might have moved sampling parameters to sessions or updated the API.
            val options = LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(2048)
                .build()
            
            llmInference = LlmInference.createFromOptions(context, options)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPipe LlmInference with Gemma 3n", e)
            false
        }
    }

    override suspend fun summarize(transcript: String): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "summarize() called with Gemma 3n")
        if (!initLlm()) return@withContext "AI Error: Gemma 3n model not found. Please ensure $modelName is in app files."

        try {
            val prompt = """
                <start_of_turn>user
                You are a professional assistant. Provide a concise, high-level executive summary of the following meeting transcript. Focus on the main purpose and key takeaway:
                
                $transcript<end_of_turn>
                <start_of_turn>model
                """.trimIndent()
            
            val result = llmInference?.generateResponse(prompt)
            result?.trim() ?: "No summary generated"
        } catch (e: Exception) {
            Log.e(TAG, "Summarization failed", e)
            "AI Error: ${e.message}"
        }
    }

    override suspend fun generateMeetingNotes(transcript: String): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "generateMeetingNotes() called with Gemma 3n")
        if (!initLlm()) return@withContext "AI Error: Gemma 3n model not found."

        try {
            val prompt = """
                <start_of_turn>user
                Extract structured meeting notes from this transcript. 
                Include:
                1. Key Discussion Points
                2. Decisions Made
                3. Action Items (with owners if mentioned)
                
                Transcript:
                $transcript<end_of_turn>
                <start_of_turn>model
                """.trimIndent()
                
            val result = llmInference?.generateResponse(prompt)
            result?.trim() ?: "No notes generated"
        } catch (e: Exception) {
            Log.e(TAG, "Meeting notes generation failed", e)
            "AI Error: ${e.message}"
        }
    }
}
