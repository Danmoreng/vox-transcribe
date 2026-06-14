package com.example.voxtranscribe.data.ai

import android.content.Context
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

interface AiRepository {
    suspend fun cleanTranscript(transcript: String): String
    suspend fun summarize(transcript: String): String
    suspend fun generateTitle(transcript: String): String
}

/**
 * Fallback Mock implementation for development and unsupported devices.
 */
class MockAiRepository : AiRepository {
    override suspend fun cleanTranscript(transcript: String): String {
        return transcript.trim()
    }

    override suspend fun summarize(transcript: String): String {
        return "This is a mock summary. Length: ${transcript.length} characters."
    }

    override suspend fun generateTitle(transcript: String): String {
        return "Mock Meeting Title"
    }
}
