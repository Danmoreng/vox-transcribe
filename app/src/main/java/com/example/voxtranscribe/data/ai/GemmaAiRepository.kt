package com.example.voxtranscribe.data.ai

import com.example.voxtranscribe.data.gemma.GemmaSettingsRepository
import com.example.voxtranscribe.data.gemma.GemmaRuntimeManager
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class GemmaAiRepository @Inject constructor(
    private val runtimeManager: GemmaRuntimeManager,
    private val settingsRepository: GemmaSettingsRepository,
) : AiRepository {

    override suspend fun summarize(transcript: String): String = withContext(Dispatchers.IO) {
        val preferences = currentPreferences()
        generateTranscriptOutput(
            transcript = transcript,
            requestedGenerationTokens = SUMMARY_GENERATION_TOKENS,
            fallbackValue = "No summary generated.",
            promptBuilder = { text ->
                """
                    Summarize the following meeting transcript.
                    ${preferences.outputLanguage.promptInstruction}
                    ${preferences.summaryStyle.summaryInstruction}
                    Do not invent facts.

                    Transcript:
                    $text
                """.trimIndent()
            },
            reducerPromptBuilder = { partials ->
                """
                    Combine the following partial meeting summaries into one coherent final summary.
                    ${preferences.outputLanguage.promptInstruction}
                    ${preferences.summaryStyle.summaryInstruction}
                    Deduplicate overlap and preserve only information supported by the transcript.

                    Partial summaries:
                    $partials
                """.trimIndent()
            },
        )
    }

    override suspend fun generateMeetingNotes(transcript: String): String = withContext(Dispatchers.IO) {
        val preferences = currentPreferences()
        generateTranscriptOutput(
            transcript = transcript,
            requestedGenerationTokens = NOTES_GENERATION_TOKENS,
            fallbackValue = "No meeting notes generated.",
            promptBuilder = { text ->
                """
                    Generate meeting notes from the following transcript.
                    ${preferences.outputLanguage.promptInstruction}
                    ${preferences.summaryStyle.notesInstruction}
                    Keep the notes faithful to the transcript and avoid repetition.

                    Transcript:
                    $text
                """.trimIndent()
            },
            reducerPromptBuilder = { partials ->
                """
                    Merge the following partial meeting notes into one final note set.
                    ${preferences.outputLanguage.promptInstruction}
                    ${preferences.summaryStyle.notesInstruction}
                    Deduplicate overlap and preserve decisions, action items, and important follow-ups.

                    Partial meeting notes:
                    $partials
                """.trimIndent()
            },
        )
    }

    override suspend fun generateTitle(transcript: String): String = withContext(Dispatchers.IO) {
        val preferences = currentPreferences()
        val cleanedTranscript = sanitizeTranscriptForPrompt(transcript)
        val titleTranscript = truncateTranscriptForPrompt(cleanedTranscript, TITLE_TRANSCRIPT_CHAR_LIMIT)
        runtimeManager.generateText(
            prompt = """
                Generate a short professional title for the following meeting transcript.
                Return only the title text and keep it under 5 words.
                ${preferences.outputLanguage.promptInstruction}

                Transcript:
                $titleTranscript
            """.trimIndent(),
            requestedGenerationTokens = 64,
        ).trim().removePrefix("\"").removeSuffix("\"").ifBlank { "Untitled Note" }
    }

    private suspend fun generateTranscriptOutput(
        transcript: String,
        requestedGenerationTokens: Int,
        fallbackValue: String,
        promptBuilder: (String) -> String,
        reducerPromptBuilder: (String) -> String,
    ): String {
        val cleanedTranscript = sanitizeTranscriptForPrompt(transcript)
        try {
            return runtimeManager.generateText(
                prompt = promptBuilder(cleanedTranscript),
                requestedGenerationTokens = requestedGenerationTokens,
            ).ifBlank { fallbackValue }
        } catch (e: Exception) {
            if (!isContextTooLongError(e)) {
                throw e
            }
        }

        val chunks = splitTranscriptIntoChunks(cleanedTranscript, TRANSCRIPT_CHUNK_CHAR_LIMIT)
        val partials = chunks.mapIndexed { index, chunk ->
            val chunkResult = runtimeManager.generateText(
                prompt = promptBuilder(chunk),
                requestedGenerationTokens = CHUNK_GENERATION_TOKENS,
            ).ifBlank { fallbackValue }
            "Chunk ${index + 1}:\n$chunkResult"
        }

        return reducePartialOutputs(
            partials = partials,
            requestedGenerationTokens = requestedGenerationTokens,
            fallbackValue = fallbackValue,
            reducerPromptBuilder = reducerPromptBuilder,
        )
    }

    private suspend fun reducePartialOutputs(
        partials: List<String>,
        requestedGenerationTokens: Int,
        fallbackValue: String,
        reducerPromptBuilder: (String) -> String,
    ): String {
        if (partials.isEmpty()) {
            return fallbackValue
        }
        if (partials.size == 1) {
            return partials.first().substringAfter('\n').ifBlank { fallbackValue }
        }

        val combined = partials.joinToString(separator = "\n\n")
        return try {
            runtimeManager.generateText(
                prompt = reducerPromptBuilder(combined),
                requestedGenerationTokens = requestedGenerationTokens,
            ).ifBlank { fallbackValue }
        } catch (e: Exception) {
            if (!isContextTooLongError(e) || partials.size <= REDUCE_BATCH_SIZE) {
                throw e
            }

            val reducedBatches = partials.chunked(REDUCE_BATCH_SIZE).map { batch ->
                reducePartialOutputs(
                    partials = batch,
                    requestedGenerationTokens = REDUCED_CHUNK_GENERATION_TOKENS,
                    fallbackValue = fallbackValue,
                    reducerPromptBuilder = reducerPromptBuilder,
                )
            }.mapIndexed { index, output ->
                "Reduced batch ${index + 1}:\n$output"
            }

            reducePartialOutputs(
                partials = reducedBatches,
                requestedGenerationTokens = requestedGenerationTokens,
                fallbackValue = fallbackValue,
                reducerPromptBuilder = reducerPromptBuilder,
            )
        }
    }

    private fun splitTranscriptIntoChunks(transcript: String, maxChars: Int): List<String> {
        if (transcript.length <= maxChars) {
            return listOf(transcript)
        }

        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        fun flushCurrent() {
            val text = current.toString().trim()
            if (text.isNotEmpty()) {
                chunks += text
            }
            current.clear()
        }

        transcript.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) {
                return@forEach
            }

            if (line.length > maxChars) {
                flushCurrent()
                splitLongLine(line, maxChars).forEach { chunks += it }
                return@forEach
            }

            val candidateLength = if (current.isEmpty()) {
                line.length
            } else {
                current.length + 1 + line.length
            }

            if (candidateLength > maxChars) {
                flushCurrent()
            }

            if (current.isNotEmpty()) {
                current.append('\n')
            }
            current.append(line)
        }

        flushCurrent()
        return chunks
    }

    private fun splitLongLine(line: String, maxChars: Int): List<String> {
        val parts = mutableListOf<String>()
        var remaining = line.trim()
        while (remaining.length > maxChars) {
            val clipped = remaining.take(maxChars)
            val wordBoundary = clipped.lastIndexOf(' ')
            val splitAt = if (wordBoundary >= maxChars / 2) wordBoundary else maxChars
            parts += remaining.substring(0, splitAt).trim()
            remaining = remaining.substring(splitAt).trimStart()
        }
        if (remaining.isNotEmpty()) {
            parts += remaining
        }
        return parts
    }

    private fun truncateTranscriptForPrompt(transcript: String, maxChars: Int): String {
        if (transcript.length <= maxChars) {
            return transcript
        }

        val clipped = transcript.take(maxChars)
        val wordBoundary = clipped.lastIndexOf(' ')
        val safeClip = if (wordBoundary >= maxChars / 2) {
            clipped.substring(0, wordBoundary)
        } else {
            clipped
        }

        return safeClip.trimEnd() + "\n..."
    }

    private fun sanitizeTranscriptForPrompt(transcript: String): String {
        return transcript.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { TIMESTAMP_RANGE_LINE_REGEX.matches(it) }
            .joinToString(separator = "\n")
            .trim()
    }

    private fun isContextTooLongError(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("Input token ids are too long", ignoreCase = true) ||
            (message.contains("context", ignoreCase = true) && message.contains("too long", ignoreCase = true))
    }

    private fun currentPreferences(): AiPromptPreferences {
        return AiPromptPreferences(
            outputLanguage = settingsRepository.aiOutputLanguage.value,
            summaryStyle = settingsRepository.aiSummaryStyle.value,
        )
    }

    private data class AiPromptPreferences(
        val outputLanguage: AiOutputLanguage,
        val summaryStyle: AiSummaryStyle,
    )

    private companion object {
        const val SUMMARY_GENERATION_TOKENS = 512
        const val NOTES_GENERATION_TOKENS = 768
        const val CHUNK_GENERATION_TOKENS = 256
        const val REDUCED_CHUNK_GENERATION_TOKENS = 320
        const val TRANSCRIPT_CHUNK_CHAR_LIMIT = 5000
        const val REDUCE_BATCH_SIZE = 3
        const val TITLE_TRANSCRIPT_CHAR_LIMIT = 2000
        val TIMESTAMP_RANGE_LINE_REGEX = Regex("""^\[\s*\d+m\d+s\d+ms\s*-\s*\d+m\d+s\d+ms\s*\]$""", RegexOption.IGNORE_CASE)
    }
}
