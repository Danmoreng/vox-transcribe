package com.example.voxtranscribe.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voxtranscribe.data.NotesRepository
import com.example.voxtranscribe.data.db.NoteWithSegments
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val notesRepository: NotesRepository
) : ViewModel() {

    fun getNoteDetail(noteId: Long): StateFlow<NoteWithSegments?> {
        return notesRepository.getNoteWithSegments(noteId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    }
}
