package com.example.voxtranscribe.data.ai

enum class AiOutputLanguage(
    val displayName: String,
    val promptInstruction: String,
) {
    MATCH_TRANSCRIPT(
        displayName = "Match Transcript",
        promptInstruction = "Write the output in the same dominant language as the transcript.",
    ),
    ENGLISH(
        displayName = "English",
        promptInstruction = "Write the output in English.",
    ),
    GERMAN(
        displayName = "German",
        promptInstruction = "Write the output in German.",
    ),
}
