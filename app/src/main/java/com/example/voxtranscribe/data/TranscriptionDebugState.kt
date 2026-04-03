package com.example.voxtranscribe.data

data class TranscriptionDebugState(
    val status: String = "Idle",
    val queuedClips: Int = 0,
    val droppedClips: Int = 0,
    val lastClipSeconds: Double = 0.0,
    val lastProcessingMillis: Long = 0L,
    val averageRealtimeFactor: Double? = null,
    val averageSpeedMultiplier: Double? = null,
)
