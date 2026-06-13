package com.example.voxtranscribe.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.voxtranscribe.data.db.Note
import com.example.voxtranscribe.data.db.NoteWithSegments
import com.example.voxtranscribe.ui.DetailViewModel
import com.mikepenz.markdown.m3.Markdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    noteId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToModelSettings: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val noteDetail by viewModel.getNoteDetail(noteId).collectAsStateWithLifecycle()
    val isDeleted by viewModel.isDeleted.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isDeleted) {
        if (isDeleted) {
            onNavigateBack()
        }
    }

    LaunchedEffect(errorMessage) {
        val message = errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearErrorMessage()
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete note?") },
            text = { Text("This removes the recording transcript and AI summary.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        noteDetail?.let { viewModel.deleteNote(it.note) }
                        showDeleteDialog = false
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(noteDetail?.note?.title ?: "Note") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val detail = noteDetail
                    IconButton(
                        onClick = {
                            detail?.let {
                                copyNoteToClipboard(context, it)
                            }
                        },
                        enabled = detail != null,
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                    }
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        enabled = detail != null,
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                },
            )
        },
    ) { padding ->
        noteDetail?.let { detail ->
            NoteContent(
                detail = detail,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } ?: Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun NoteContent(
    detail: NoteWithSegments,
    modifier: Modifier = Modifier,
) {
    val transcript = remember(detail.segments) {
        buildDisplayTranscript(detail)
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SummaryCard(note = detail.note)
        }
        item {
            TranscriptCard(transcript = transcript)
        }
    }
}

@Composable
private fun SummaryCard(note: Note) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val summary = note.summary?.trim()
            if (summary.isNullOrBlank()) {
                Text(
                    text = "Summary will appear here once AI cleanup has run.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Markdown(summary)
            }
        }
    }
}

@Composable
private fun TranscriptCard(transcript: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Transcript", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (transcript.isBlank()) {
                Text(
                    text = "No transcript text saved.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(transcript, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun copyNoteToClipboard(context: Context, detail: NoteWithSegments) {
    val transcript = buildDisplayTranscript(detail)
    val text = buildString {
        appendLine(detail.note.title)
        detail.note.summary?.trim()?.takeIf { it.isNotBlank() }?.let { summary ->
            appendLine()
            appendLine("Summary")
            appendLine(summary)
        }
        if (transcript.isNotBlank()) {
            appendLine()
            appendLine("Transcript")
            appendLine(transcript)
        }
    }.trim()

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Vox Note", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private fun buildDisplayTranscript(detail: NoteWithSegments): String {
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
