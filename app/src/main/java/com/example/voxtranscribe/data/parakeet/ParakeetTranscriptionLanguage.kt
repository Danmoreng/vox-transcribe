package com.example.voxtranscribe.data.parakeet

enum class ParakeetTranscriptionLanguage(
    val displayName: String,
    val targetLang: String,
) {
    AUTO(
        displayName = "Auto",
        targetLang = "auto",
    ),
    GERMAN_GERMANY(
        displayName = "German",
        targetLang = "de-DE",
    ),
    ENGLISH_US(
        displayName = "English",
        targetLang = "en-US",
    ),
}
