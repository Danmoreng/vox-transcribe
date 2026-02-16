package com.example.voxtranscribe.data.ai

import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import javax.inject.Inject
import javax.inject.Singleton

interface AiRepository {
    suspend fun summarize(transcript: String): String
    suspend fun generateMeetingNotes(transcript: String): String
}

@Singleton
class GeminiAiRepository @Inject constructor() : AiRepository {

    // Using the ML Kit GenAI Prompt API for Gemini Nano
    private val model: GenerativeModel = Generation.getClient()

    override suspend fun summarize(transcript: String): String {
        return try {
            val response = model.generateContent("Summarize the following meeting transcript concisely:\n\n$transcript")
            
            // Extract text from the first candidate
            response.candidates.firstOrNull()?.text ?: "Could not generate summary."
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    override suspend fun generateMeetingNotes(transcript: String): String {
        val promptText = """
            Extract structured meeting notes from the following transcript. 
            Include:
            - Key Decisions
            - Action Items
            - Main Highlights
            
            Transcript:
            $transcript
        """.trimIndent()
        
        return try {
            val response = model.generateContent(promptText)
            
            response.candidates.firstOrNull()?.text ?: "Could not generate meeting notes."
        } catch (e: Exception) {
            "Error: ${e.message}"
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
