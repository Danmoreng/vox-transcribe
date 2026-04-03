package com.example.voxtranscribe.data.gemma

enum class GemmaTranscriptionLanguage(
    val displayName: String,
    val promptLabel: String?,
) {
    AUTO(
        displayName = "Auto",
        promptLabel = null,
    ),
    GERMAN(
        displayName = "German",
        promptLabel = "German",
    ),
    ENGLISH(
        displayName = "English",
        promptLabel = "English",
    ),
}
