package com.example.voxtranscribe.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val noteId: Long = 0,
    val title: String,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val summary: String? = null,
    val structuredNotes: String? = null,
    val cleanedTranscript: String? = null,
    val aiStatus: String = AI_STATUS_IDLE,
    val aiProgress: Float = 0f,
    val aiStatusMessage: String? = null,
)

const val AI_STATUS_IDLE = "idle"
const val AI_STATUS_PROCESSING = "processing"
const val AI_STATUS_DONE = "done"
const val AI_STATUS_FAILED = "failed"

@Entity(
    tableName = "transcript_segments",
    foreignKeys = [
        ForeignKey(
            entity = Note::class,
            parentColumns = ["noteId"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("noteId")]
)
data class TranscriptSegment(
    @PrimaryKey(autoGenerate = true) val segmentId: Long = 0,
    val noteId: Long,
    val text: String,
    val timestamp: Long,
    val isFinal: Boolean
)
