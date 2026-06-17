package com.example.voxtranscribe.data.ai

import com.example.voxtranscribe.data.gemma.GemmaRuntimeManager
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class GemmaAiRepository @Inject constructor(
    private val runtimeManager: GemmaRuntimeManager,
) : AiRepository {

    override suspend fun cleanTranscript(transcript: String): String = withContext(Dispatchers.IO) {
        val cleanedTranscript = sanitizeTranscriptForPrompt(transcript)
        val languageInstruction = buildLanguageInstruction(cleanedTranscript)
        if (cleanedTranscript.isBlank()) {
            return@withContext ""
        }

        transformTranscriptChunks(
            transcript = cleanedTranscript,
            promptBuilder = { chunk ->
                """
                    Clean up the following speech-to-text transcript for readability.
                    $languageInstruction
                    Correct clear speech-to-text mistakes when the nearby context strongly supports the correction.
                    Fix spacing, punctuation, casing, paragraph breaks, repeated filler artifacts, and obvious grammar artifacts.
                    Keep the speaker's wording and level of detail. Preserve the meaning and all concrete information.
                    You may normalize obvious common terms, abbreviations, and named entities when context makes the intended wording clear.
                    If a name, product name, abbreviation, number, date, currency, or rollout status is genuinely uncertain, keep it close to the source wording instead of guessing.
                    Do not summarize. Do not add facts. Do not add currencies or dates that are not explicit.
                    Do not make the text more polished by changing the speaker's intent.
                    Return only the cleaned excerpt.

                    Previous context for disambiguation only. Do not include this context in your output:
                    ${chunk.previousContext.ifBlank { "(none)" }}

                    Excerpt to clean:
                    ${chunk.text}
                """.trimIndent()
            },
        )
    }

    override suspend fun summarize(transcript: String): String = withContext(Dispatchers.IO) {
        val cleanedTranscript = sanitizeTranscriptForPrompt(transcript)
        val languageInstruction = buildLanguageInstruction(cleanedTranscript)
        generateTranscriptOutput(
            transcript = cleanedTranscript,
            requestedGenerationTokens = SUMMARY_GENERATION_TOKENS,
            fallbackValue = "No summary generated.",
            promptBuilder = { text ->
                """
                    Summarize the following meeting transcript.
                    $languageInstruction
                    $SUMMARY_INSTRUCTION
                    Be conservative with names, numbers, dates, currencies, and rollout status.
                    Do not invent facts. Do not add a currency unless it is explicit in the transcript.
                    Distinguish pilots, planned launches, and live products only when the transcript explicitly supports it.
                    Return the summary only in the required output language.

                    Transcript:
                    $text
                """.trimIndent()
            },
            reducerPromptBuilder = { partials ->
                """
                    Combine the following partial meeting summaries into one coherent final summary.
                    $languageInstruction
                    $SUMMARY_INSTRUCTION
                    Deduplicate overlap and preserve only information supported by the transcript.
                    Be conservative with names, numbers, dates, currencies, and rollout status.
                    Do not add a currency unless it is explicit in the transcript.
                    Return the final summary only in the required output language.

                    Partial summaries:
                    $partials
                """.trimIndent()
            },
        )
    }

    override suspend fun generateTitle(transcript: String): String = withContext(Dispatchers.IO) {
        val cleanedTranscript = sanitizeTranscriptForPrompt(transcript)
        val languageInstruction = buildLanguageInstruction(cleanedTranscript)
        val titleTranscript = truncateTranscriptForPrompt(cleanedTranscript, TITLE_TRANSCRIPT_CHAR_LIMIT)
        runtimeManager.generateText(
            prompt = """
                Generate a short professional title for the following meeting transcript.
                Return only the title text and keep it under 5 words.
                Be conservative. Do not invent a specific company, product, or event name unless it is explicit.
                $languageInstruction

                Transcript:
                $titleTranscript
            """.trimIndent(),
            requestedGenerationTokens = 64,
            temperature = TITLE_TEMPERATURE,
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
                temperature = SUMMARY_TEMPERATURE,
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
                temperature = SUMMARY_TEMPERATURE,
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

    private suspend fun transformTranscriptChunks(
        transcript: String,
        promptBuilder: (TranscriptChunk) -> String,
    ): String {
        val chunks = splitTranscriptIntoChunksWithContext(
            transcript = transcript,
            maxChars = CLEANUP_CHUNK_CHAR_LIMIT,
            contextSentenceCount = CLEANUP_CONTEXT_SENTENCES,
        )
        return chunks
            .map { chunk ->
                runtimeManager.generateText(
                    prompt = promptBuilder(chunk),
                    requestedGenerationTokens = CLEANUP_GENERATION_TOKENS,
                    temperature = CLEANUP_TEMPERATURE,
                ).trim().ifBlank { chunk.text }
            }
            .joinToString(separator = "\n\n")
            .trim()
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
                temperature = SUMMARY_TEMPERATURE,
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

    private data class TranscriptChunk(
        val text: String,
        val previousContext: String,
    )

    private fun splitTranscriptIntoChunksWithContext(
        transcript: String,
        maxChars: Int,
        contextSentenceCount: Int,
    ): List<TranscriptChunk> {
        val units = splitTranscriptIntoUnits(transcript)
        val chunks = splitUnitsIntoChunks(units, maxChars)
        var consumedUnits = 0
        return chunks.map { chunk ->
            val chunkUnits = splitTranscriptIntoUnits(chunk)
            val previousContext = units
                .drop((consumedUnits - contextSentenceCount).coerceAtLeast(0))
                .take(consumedUnits - (consumedUnits - contextSentenceCount).coerceAtLeast(0))
                .joinToString(separator = " ")
            consumedUnits += chunkUnits.size
            TranscriptChunk(text = chunk, previousContext = previousContext)
        }
    }

    private fun splitTranscriptIntoChunks(transcript: String, maxChars: Int): List<String> {
        return splitUnitsIntoChunks(splitTranscriptIntoUnits(transcript), maxChars)
    }

    private fun splitUnitsIntoChunks(units: List<String>, maxChars: Int): List<String> {
        if (units.isEmpty()) {
            return emptyList()
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

        units.forEach { unit ->
            if (unit.length > maxChars) {
                flushCurrent()
                splitLongLine(unit, maxChars).forEach { chunks += it }
                return@forEach
            }

            val separator = if (current.isEmpty()) "" else "\n"
            val candidateLength = current.length + separator.length + unit.length
            if (candidateLength > maxChars) {
                flushCurrent()
            }

            if (current.isNotEmpty()) {
                current.append('\n')
            }
            current.append(unit)
        }

        flushCurrent()
        return chunks
    }

    private fun splitTranscriptIntoUnits(transcript: String): List<String> {
        return transcript.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { line ->
                SENTENCE_BOUNDARY_REGEX.split(line)
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
            .toList()
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

    private fun buildLanguageInstruction(transcript: String): String {
        return if (looksGerman(transcript)) {
            "Required output language: German, because the transcript is German. Do not answer in English."
        } else {
            "Required output language: the same dominant language as the transcript. Do not translate unless the transcript itself changes language."
        }
    }

    private fun looksGerman(transcript: String): Boolean {
        val lower = transcript.lowercase(Locale.ROOT)
        val germanSignals = listOf(
            " der ",
            " die ",
            " das ",
            " und ",
            " ich ",
            " ist ",
            " nicht ",
            " wir ",
            " mit ",
            " auf ",
            " für ",
            " dass ",
            " ein ",
            " eine ",
            " habe ",
            " bitte ",
            " jetzt ",
            " funktioniert ",
            "ä",
            "ö",
            "ü",
            "ß",
        )
        return germanSignals.count { lower.contains(it) } >= 3
    }

    private companion object {
        const val SUMMARY_INSTRUCTION =
            "Write a short, clean summary in one or two compact paragraphs. Focus on key discussion points, decisions, outcomes, and next steps."
        const val SUMMARY_GENERATION_TOKENS = 512
        const val CLEANUP_GENERATION_TOKENS = 1024
        const val CHUNK_GENERATION_TOKENS = 256
        const val REDUCED_CHUNK_GENERATION_TOKENS = 320
        const val TRANSCRIPT_CHUNK_CHAR_LIMIT = 5000
        const val CLEANUP_CHUNK_CHAR_LIMIT = 2500
        const val CLEANUP_CONTEXT_SENTENCES = 2
        const val REDUCE_BATCH_SIZE = 3
        const val TITLE_TRANSCRIPT_CHAR_LIMIT = 2000
        const val CLEANUP_TEMPERATURE = 0.15
        const val SUMMARY_TEMPERATURE = 0.25
        const val TITLE_TEMPERATURE = 0.35
        val SENTENCE_BOUNDARY_REGEX = Regex("""(?<=[.!?])\s+""")
        val TIMESTAMP_RANGE_LINE_REGEX = Regex("""^\[\s*\d+m\d+s\d+ms\s*-\s*\d+m\d+s\d+ms\s*\]$""", RegexOption.IGNORE_CASE)
    }
}
