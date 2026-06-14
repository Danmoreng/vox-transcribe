package com.example.voxtranscribe.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voxtranscribe.data.NotesRepository
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

    fun deleteNote(note: com.example.voxtranscribe.data.db.Note) {
        viewModelScope.launch {
            _isDeleted.value = true
            // Give navigation time to transition away
            kotlinx.coroutines.delay(300) 
            notesRepository.deleteNote(note)
        }
    }
}
