package com.example.voxtranscribe.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.voxtranscribe.R
import com.example.voxtranscribe.data.db.AI_STATUS_FAILED
import com.example.voxtranscribe.data.db.AI_STATUS_PROCESSING
import com.example.voxtranscribe.data.db.Note
import com.example.voxtranscribe.ui.TranscriptionViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToRecord: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToModelSettings: () -> Unit,
    viewModel: TranscriptionViewModel = hiltViewModel()
) {
    val notes by viewModel.allNotes.collectAsStateWithLifecycle()
    var noteToDelete by remember { mutableStateOf<Note?>(null) }

    if (noteToDelete != null) {
        val note = noteToDelete!!
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title = { Text(stringResource(R.string.delete_transcript)) },
            text = { Text(stringResource(R.string.delete_note_message, note.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteNote(note)
                        noteToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { noteToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onNavigateToModelSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToRecord,
                icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                text = { Text(stringResource(R.string.record)) }
            )
        }
    ) { padding ->
        if (notes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.no_recordings_yet), color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(notes) { note ->
                    NoteCard(
                        note = note, 
                        onClick = { onNavigateToDetail(note.noteId) },
                        onDelete = { noteToDelete = note }
                    )
                }
            }
        }
    }
}

@Composable
fun NoteCard(note: Note, onClick: () -> Unit, onDelete: () -> Unit) {
    val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = note.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = sdf.format(Date(note.startTime)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                if (note.aiStatus == AI_STATUS_PROCESSING) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { note.aiProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = aiProgressLabel(note.aiProgress),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (note.aiStatus == AI_STATUS_FAILED) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.ai_cleanup_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Delete, 
                    contentDescription = stringResource(R.string.delete_note_content_description),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun aiProgressLabel(progress: Float): String {
    return when {
        progress < 0.25f -> stringResource(R.string.ai_status_preparing)
        progress < 0.5f -> stringResource(R.string.ai_status_title)
        progress < 0.75f -> stringResource(R.string.ai_status_transcript)
        progress < 1f -> stringResource(R.string.ai_status_summary)
        else -> stringResource(R.string.ai_status_complete)
    }
}
