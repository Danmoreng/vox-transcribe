package com.example.voxtranscribe.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voxtranscribe.data.NotesRepository
import com.example.voxtranscribe.data.ai.AiRepository
import com.example.voxtranscribe.data.db.AI_STATUS_DONE
import com.example.voxtranscribe.data.db.AI_STATUS_FAILED
import com.example.voxtranscribe.data.db.AI_STATUS_IDLE
import com.example.voxtranscribe.data.db.AI_STATUS_PROCESSING
import com.example.voxtranscribe.data.db.Note
import com.example.voxtranscribe.data.db.NoteWithSegments
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val notesRepository: NotesRepository,
    private val aiRepository: AiRepository,
) : ViewModel() {

    private val _isDeleted = MutableStateFlow(false)
    val isDeleted: StateFlow<Boolean> = _isDeleted.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun getNoteDetail(noteId: Long): StateFlow<NoteWithSegments?> {
        return notesRepository.getNoteWithSegments(noteId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    fun rerunTextAi(detail: NoteWithSegments) {
        if (detail.note.aiStatus == AI_STATUS_PROCESSING) {
            return
        }

        viewModelScope.launch {
            val noteId = detail.note.noteId
            val transcript = buildRawTranscript(detail)
            if (transcript.isBlank()) {
                notesRepository.updateAiStatus(noteId, AI_STATUS_IDLE, 0f, null)
                _errorMessage.value = "No transcript text available."
                return@launch
            }

            try {
                notesRepository.updateAiStatus(noteId, AI_STATUS_PROCESSING, 0.1f, "Preparing AI cleanup...")
                notesRepository.updateCleanedTranscript(noteId, null)
                notesRepository.updateAiResults(noteId, null, null)

                notesRepository.updateAiStatus(noteId, AI_STATUS_PROCESSING, 0.25f, "Generating title...")
                val title = aiRepository.generateTitle(transcript)
                withContext(NonCancellable) {
                    notesRepository.updateNoteTitle(noteId, title)
                }

                notesRepository.updateAiStatus(noteId, AI_STATUS_PROCESSING, 0.5f, "Cleaning transcript...")
                val cleanedTranscript = aiRepository.cleanTranscript(transcript).ifBlank { transcript }
                withContext(NonCancellable) {
                    notesRepository.updateCleanedTranscript(noteId, cleanedTranscript)
                }

                notesRepository.updateAiStatus(noteId, AI_STATUS_PROCESSING, 0.75f, "Generating summary...")
                val summary = aiRepository.summarize(cleanedTranscript)
                withContext(NonCancellable) {
                    notesRepository.updateAiResults(noteId, summary, null)
                    notesRepository.updateAiStatus(noteId, AI_STATUS_DONE, 1f, "AI cleanup complete")
                }
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    notesRepository.updateAiStatus(noteId, AI_STATUS_FAILED, 1f, "AI cleanup failed")
                }
                _errorMessage.value = e.message ?: "Text AI rerun failed."
            }
        }
    }

    fun deleteNote(note: Note) {
        viewModelScope.launch {
            _isDeleted.value = true
            // Give navigation time to transition away
            kotlinx.coroutines.delay(300) 
            notesRepository.deleteNote(note)
        }
    }

    private fun buildRawTranscript(detail: NoteWithSegments): String {
        return detail.segments
            .asSequence()
            .map { it.text.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { normalizeTranscriptForDisplay(it) }
            .joinToString(separator = "\n\n")
            .trim()
    }

    private fun normalizeTranscriptForDisplay(text: String): String {
        return text.trim().replace(Regex("\\s+"), " ")
    }
}
