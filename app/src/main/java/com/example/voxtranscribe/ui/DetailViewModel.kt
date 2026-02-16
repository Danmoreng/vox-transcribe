package com.example.voxtranscribe.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voxtranscribe.data.NotesRepository
import com.example.voxtranscribe.data.ai.AiRepository
import com.example.voxtranscribe.data.db.NoteWithSegments
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val notesRepository: NotesRepository,
    private val aiRepository: AiRepository
) : ViewModel() {

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _isDeleted = MutableStateFlow(false)
    val isDeleted: StateFlow<Boolean> = _isDeleted.asStateFlow()

    fun getNoteDetail(noteId: Long): StateFlow<NoteWithSegments?> {
        return notesRepository.getNoteWithSegments(noteId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    }

    fun generateAiInsights(noteId: Long, transcript: String) {
        android.util.Log.d("DetailViewModel", "Generating AI insights for note: $noteId")
        viewModelScope.launch {
            _isProcessing.value = true
            try {
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
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun deleteNote(note: com.example.voxtranscribe.data.db.Note) {
        viewModelScope.launch {
            _isDeleted.value = true
            // Give navigation time to transition away
            kotlinx.coroutines.delay(300) 
            notesRepository.deleteNote(note)
        }
    }
}
