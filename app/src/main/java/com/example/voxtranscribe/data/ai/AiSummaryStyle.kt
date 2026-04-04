package com.example.voxtranscribe.data.ai

enum class AiSummaryStyle(
    val displayName: String,
    val summaryInstruction: String,
    val notesInstruction: String,
) {
    EXECUTIVE(
        displayName = "Executive",
        summaryInstruction = "Write a short executive summary in one or two compact paragraphs. Focus on key discussion points, decisions, and outcomes.",
        notesInstruction = "Generate concise bulleted meeting notes with decisions, action items, and important follow-ups.",
    ),
    BULLET(
        displayName = "Bullet",
        summaryInstruction = "Write a concise bulleted summary with the main topics, decisions, and outcomes.",
        notesInstruction = "Generate short bulleted meeting notes. Keep bullets compact and remove repetition.",
    ),
    DETAILED(
        displayName = "Detailed",
        summaryInstruction = "Write a more detailed structured summary using short markdown sections for discussion topics, decisions, and follow-ups.",
        notesInstruction = "Generate structured meeting notes in markdown with sections for Decisions, Action Items, and Follow-ups.",
    ),
    ACTION_FOCUSED(
        displayName = "Action-Focused",
        summaryInstruction = "Write a concise summary that emphasizes decisions, owners if known, action items, and next steps.",
        notesInstruction = "Generate meeting notes that strongly emphasize action items, owners if known, deadlines if mentioned, blockers, and next steps.",
    ),
}
