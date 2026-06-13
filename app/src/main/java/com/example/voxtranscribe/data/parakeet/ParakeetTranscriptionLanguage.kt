package com.example.voxtranscribe.data.parakeet

enum class ParakeetTranscriptionLanguage(
    val displayName: String,
    val targetLang: String,
    val nemotronLanguageId: Int,
) {
    AUTO(
        displayName = "Auto",
        targetLang = "auto",
        nemotronLanguageId = 0,
    ),
    GERMAN_GERMANY(
        displayName = "German",
        targetLang = "de-DE",
        nemotronLanguageId = 9,
    ),
    ENGLISH_US(
        displayName = "English",
        targetLang = "en-US",
        nemotronLanguageId = 0,
    ),
}
