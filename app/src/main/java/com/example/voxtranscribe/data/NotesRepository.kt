package com.example.voxtranscribe.data

import com.example.voxtranscribe.data.db.Note
import com.example.voxtranscribe.data.db.NotesDao
import com.example.voxtranscribe.data.db.TranscriptSegment
import kotlinx.coroutines.flow.Flow

class NotesRepository(private val notesDao: NotesDao) {
    fun getAllNotes(): Flow<List<Note>> = notesDao.getAllNotes()

    suspend fun createNote(title: String): Long {
        val note = Note(title = title)
        return notesDao.insertNote(note)
    }

    suspend fun updateNote(note: Note) {
        notesDao.updateNote(note)
    }

    suspend fun insertSegment(noteId: Long, text: String, isFinal: Boolean) {
        val segment = TranscriptSegment(
            noteId = noteId,
            text = text,
            timestamp = System.currentTimeMillis(),
            isFinal = isFinal
        )
        notesDao.insertSegment(segment)
    }

    suspend fun updateAiResults(noteId: Long, summary: String?, structuredNotes: String?) {
        notesDao.updateAiResults(noteId, summary, structuredNotes)
    }

    suspend fun updateNoteTitle(noteId: Long, title: String) {
        notesDao.updateNoteTitle(noteId, title)
    }

    suspend fun deleteNote(note: Note) {
        notesDao.deleteNote(note)
    }

    fun getNoteWithSegments(noteId: Long) = notesDao.getNoteWithSegments(noteId)
}
