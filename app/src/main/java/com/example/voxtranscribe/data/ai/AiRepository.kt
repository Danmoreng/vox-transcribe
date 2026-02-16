package com.example.voxtranscribe.data.ai

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.Summarizer
import com.google.mlkit.genai.summarization.SummarizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.guava.await
import javax.inject.Inject
import javax.inject.Singleton

interface AiRepository {
    suspend fun summarize(transcript: String): String
    suspend fun generateMeetingNotes(transcript: String): String
}

@Singleton
class GeminiAiRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : AiRepository {

    private val TAG = "GeminiAiRepository"
    private val mockFallback = MockAiRepository()

    private fun createSummarizer(outputType: Int): Summarizer {
        val options = SummarizerOptions.builder(context)
            .setInputType(SummarizerOptions.InputType.CONVERSATION)
            .setOutputType(outputType)
            .setLanguage(SummarizerOptions.Language.ENGLISH)
            .build()
        return Summarization.getClient(options)
    }

    override suspend fun summarize(transcript: String): String {
        Log.d(TAG, "summarize() called with transcript length: ${transcript.length}")
        return try {
            // Using the Int constant from OutputType
            val summarizer = createSummarizer(SummarizerOptions.OutputType.ONE_BULLET)
            
            Log.d(TAG, "Running inference for summary...")
            val request = SummarizationRequest.builder(transcript).build()
            
            val result = summarizer.runInference(request).await()
            Log.d(TAG, "Summary inference successful")
            result.summary ?: "No summary generated"
        } catch (e: Exception) {
            Log.e(TAG, "Summarization failed", e)
            "AI Error: ${e.message}"
        }
    }

    override suspend fun generateMeetingNotes(transcript: String): String {
        Log.d(TAG, "generateMeetingNotes() called")
        return try {
            val summarizer = createSummarizer(SummarizerOptions.OutputType.THREE_BULLETS)
            
            Log.d(TAG, "Running inference for meeting notes...")
            val request = SummarizationRequest.builder(transcript).build()
            
            val result = summarizer.runInference(request).await()
            Log.d(TAG, "Meeting notes inference successful")
            result.summary ?: "No notes generated"
        } catch (e: Exception) {
            Log.e(TAG, "Meeting notes generation failed", e)
            "AI Error: ${e.message}"
        }
    }
}

/**
 * Fallback Mock implementation for development and unsupported devices.
 */
class MockAiRepository : AiRepository {
    override suspend fun summarize(transcript: String): String {
        return "This is a mock summary. Length: ${transcript.length} characters."
    }

    override suspend fun generateMeetingNotes(transcript: String): String {
        return "• Decision: Keep using Mock AI\n• Action: Buy more coffee\n• Highlight: App development is fast!"
    }
}
