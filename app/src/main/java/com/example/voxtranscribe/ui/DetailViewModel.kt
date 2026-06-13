package com.example.voxtranscribe.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voxtranscribe.data.NotesRepository
import com.example.voxtranscribe.data.ai.AiRepository
import com.example.voxtranscribe.data.db.NoteWithSegments
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val notesRepository: NotesRepository,
    private val aiRepository: AiRepository,
) : ViewModel() {

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _isDeleted = MutableStateFlow(false)
    val isDeleted: StateFlow<Boolean> = _isDeleted.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun getNoteDetail(noteId: Long): StateFlow<NoteWithSegments?> {
        return notesRepository.getNoteWithSegments(noteId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    }

    fun generateAiInsights(noteId: Long, transcript: String) {
        android.util.Log.d("DetailViewModel", "Generating AI insights for note: $noteId")
        viewModelScope.launch {
            _isProcessing.value = true
            _errorMessage.value = null
            try {
                android.util.Log.d("DetailViewModel", "Requesting title...")
                val title = aiRepository.generateTitle(transcript)
                notesRepository.updateNoteTitle(noteId, title)
                
                android.util.Log.d("DetailViewModel", "Requesting summary...")
                val summary = aiRepository.summarize(transcript)
                android.util.Log.d("DetailViewModel", "Summary received: ${summary.take(50)}...")
                
                android.util.Log.d("DetailViewModel", "Requesting meeting notes...")
                val notes = aiRepository.generateMeetingNotes(transcript)
                android.util.Log.d("DetailViewModel", "Notes received: ${notes.take(50)}...")
                
                notesRepository.updateAiResults(noteId, summary, notes)
                android.util.Log.d("DetailViewModel", "AI results saved to database.")
            } catch (e: Exception) {
                android.util.Log.e("DetailViewModel", "Error generating AI insights", e)
                _errorMessage.value = toUserMessage(e)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    fun deleteNote(note: com.example.voxtranscribe.data.db.Note) {
        viewModelScope.launch {
            _isDeleted.value = true
            // Give navigation time to transition away
            kotlinx.coroutines.delay(300) 
            notesRepository.deleteNote(note)
        }
    }

    private fun toUserMessage(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("Input token ids are too long", ignoreCase = true) ->
                "The transcript is too long for the current on-device AI context."
            message.contains("No Gemma model is selected", ignoreCase = true) ->
                "No Gemma model is selected."
            message.contains("not imported", ignoreCase = true) ->
                "The selected Gemma model is not imported."
            else -> message.ifBlank { "AI processing failed." }
        }
    }
}
