package com.example.voxtranscribe.data.ai

import android.content.Context
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

interface AiRepository {
    suspend fun summarize(transcript: String): String
    suspend fun generateMeetingNotes(transcript: String): String
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
