package com.example.voxtranscribe.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.voxtranscribe.R
import com.example.voxtranscribe.data.db.AI_STATUS_FAILED
import com.example.voxtranscribe.data.db.AI_STATUS_PROCESSING
import com.example.voxtranscribe.data.db.Note
import com.example.voxtranscribe.data.db.NoteWithSegments
import com.example.voxtranscribe.ui.DetailViewModel
import com.mikepenz.markdown.m3.Markdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    noteId: Long,
    onNavigateBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val noteDetail by viewModel.getNoteDetail(noteId).collectAsStateWithLifecycle()
    val isDeleted by viewModel.isDeleted.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.copied)
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
            title = { Text(stringResource(R.string.delete_note_title)) },
            text = { Text(stringResource(R.string.delete_note_detail_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        noteDetail?.let { viewModel.deleteNote(it.note) }
                        showDeleteDialog = false
                    },
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = noteDetail?.note?.title ?: stringResource(R.string.note),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    val detail = noteDetail
                    IconButton(
                        onClick = {
                            detail?.let {
                                copyNoteToClipboard(context, it, copiedMessage)
                            }
                        },
                        enabled = detail != null,
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy))
                    }
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        enabled = detail != null,
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
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
    val transcript = remember(detail.note.cleanedTranscript, detail.segments) {
        buildDisplayTranscript(detail)
    }
    var selectedTab by remember { mutableStateOf(0) }
    Column(
        modifier = modifier,
    ) {
        TabRow(
            selectedTabIndex = selectedTab,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text(stringResource(R.string.transcript)) },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(stringResource(R.string.summary)) },
            )
        }
        if (selectedTab == 0) {
            TranscriptContent(
                transcript = transcript,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (detail.note.aiStatus == AI_STATUS_PROCESSING || detail.note.aiStatus == AI_STATUS_FAILED) {
                    AiProgressCard(note = detail.note)
                }
                SummaryContent(note = detail.note)
            }
        }
    }
}

@Composable
private fun AiProgressCard(note: Note) {
    val isFailed = note.aiStatus == AI_STATUS_FAILED
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isFailed) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (isFailed) stringResource(R.string.ai_cleanup_failed) else stringResource(R.string.ai_cleanup_running),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (!isFailed) {
                LinearProgressIndicator(
                    progress = { note.aiProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = if (isFailed) {
                    stringResource(R.string.ai_cleanup_failed_description)
                } else {
                    aiProgressLabel(note.aiProgress)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (isFailed) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
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

@Composable
private fun SummaryContent(note: Note) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val summary = note.summary?.trim()
        if (summary.isNullOrBlank()) {
            Text(
                text = if (note.aiStatus == AI_STATUS_PROCESSING) {
                    stringResource(R.string.summary_generating)
                } else {
                    stringResource(R.string.summary_pending)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SelectionContainer {
                Markdown(summary)
            }
        }
    }
}

@Composable
private fun TranscriptContent(
    transcript: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(top = 8.dp),
    ) {
        if (transcript.isBlank()) {
            Text(
                text = stringResource(R.string.no_transcript_text_saved),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SelectionContainer {
                Text(
                    text = transcript,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

private fun copyNoteToClipboard(context: Context, detail: NoteWithSegments, copiedMessage: String) {
    val transcript = buildDisplayTranscript(detail)
    val text = buildString {
        appendLine(detail.note.title)
        detail.note.summary?.trim()?.takeIf { it.isNotBlank() }?.let { summary ->
            appendLine()
            appendLine(context.getString(R.string.summary))
            appendLine(summary)
        }
        if (transcript.isNotBlank()) {
            appendLine()
            appendLine(context.getString(R.string.transcript))
            appendLine(transcript)
        }
    }.trim()

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.note), text))
    Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
}

private fun buildDisplayTranscript(detail: NoteWithSegments): String {
    detail.note.cleanedTranscript?.trim()?.takeIf { it.isNotBlank() }?.let { cleanedTranscript ->
        return cleanedTranscript
    }

    return buildRawTranscript(detail)
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
