package com.example.voxtranscribe.domain

data class LogEntry(
    val text: String,
    val timestamp: Long,
    val isFinal: Boolean
)
