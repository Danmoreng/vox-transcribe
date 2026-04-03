package com.example.voxtranscribe.data.ai

import com.example.voxtranscribe.data.gemma.GemmaRuntimeManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class GemmaAiRepository @Inject constructor(
    private val runtimeManager: GemmaRuntimeManager,
) : AiRepository {

    override suspend fun summarize(transcript: String): String = withContext(Dispatchers.IO) {
        runtimeManager.generateText(
            prompt = """
                Provide a short executive summary of the following meeting transcript.
                Focus on the key discussion points, decisions, and outcomes.

                Transcript:
                $transcript
            """.trimIndent(),
            maxTokens = 512,
        ).ifBlank { "No summary generated." }
    }

    override suspend fun generateMeetingNotes(transcript: String): String = withContext(Dispatchers.IO) {
        runtimeManager.generateText(
            prompt = """
                Generate concise bulleted meeting notes from the following transcript.
                Include decisions, action items, and important follow-ups.

                Transcript:
                $transcript
            """.trimIndent(),
            maxTokens = 768,
        ).ifBlank { "No meeting notes generated." }
    }

    override suspend fun generateTitle(transcript: String): String = withContext(Dispatchers.IO) {
        runtimeManager.generateText(
            prompt = """
                Generate a short professional title for the following meeting transcript.
                Return only the title text and keep it under 5 words.

                Transcript:
                $transcript
            """.trimIndent(),
            maxTokens = 64,
        ).trim().removePrefix("\"").removeSuffix("\"").ifBlank { "Untitled Note" }
    }
}
