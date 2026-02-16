package com.example.voxtranscribe.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class NoteWithSegments(
    @Embedded val note: Note,
    @Relation(
        parentColumn = "noteId",
        entityColumn = "noteId"
    )
    val segments: List<TranscriptSegment>
)

@Dao
interface NotesDao {
    @Insert
    suspend fun insertNote(note: Note): Long

    @Update
    suspend fun updateNote(note: Note)

    @Insert
    suspend fun insertSegment(segment: TranscriptSegment)

    @Transaction
    @Query("SELECT * FROM notes WHERE noteId = :noteId")
    fun getNoteWithSegments(noteId: Long): Flow<NoteWithSegments>

    @Query("SELECT * FROM notes ORDER BY startTime DESC")
    fun getAllNotes(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE noteId = :noteId")
    suspend fun getNoteById(noteId: Long): Note?
}
