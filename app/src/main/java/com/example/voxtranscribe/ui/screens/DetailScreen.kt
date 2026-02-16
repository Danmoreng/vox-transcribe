package com.example.voxtranscribe.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.voxtranscribe.ui.DetailViewModel
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.*

import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Segment
import com.example.voxtranscribe.data.ai.DownloadState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    noteId: Long,
    onNavigateBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel()
) {
    val noteDetail by viewModel.getNoteDetail(noteId).collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val isDeleted by viewModel.isDeleted.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    
    val context = LocalContext.current
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    
    var selectedTab by remember { mutableStateOf(0) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isDeleted) {
        if (isDeleted) {
            onNavigateBack()
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Transcript") },
            text = { Text("Are you sure you want to delete this transcript? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        noteDetail?.let {
                            viewModel.deleteNote(it.note)
                        }
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(noteDetail?.note?.title ?: "Loading...") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Segment, contentDescription = null) },
                    label = { Text("Transcript") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Notes, contentDescription = null) },
                    label = { Text("AI Insights") }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Action Row below the title
            if (noteDetail != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    // Logic for AI Button: Download vs Process
                    if (downloadState != DownloadState.Completed) {
                        Column(modifier = Modifier.weight(1f)) {
                            Button(
                                onClick = { viewModel.downloadModel() },
                                enabled = downloadState != DownloadState.Downloading,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (downloadState is DownloadState.Error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondary
                                )
                            ) {
                                if (downloadState == DownloadState.Downloading) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondary)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Downloading...", style = MaterialTheme.typography.labelLarge)
                                } else {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (downloadState is DownloadState.Error) "Retry Download" else "Download AI Model", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            
                            if (downloadState is DownloadState.Error) {
                                Text(
                                    text = (downloadState as DownloadState.Error).message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                noteDetail?.let { detail ->
                                    val fullText = detail.segments.joinToString("\n") { it.text }
                                    viewModel.generateAiInsights(noteId, fullText)
                                }
                            },
                            enabled = !isProcessing && noteDetail?.segments?.isNotEmpty() == true,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Process AI", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }

                    OutlinedIconButton(onClick = {
                        noteDetail?.let { detail ->
                            val fullText = detail.segments.joinToString("\n") { 
                                "[${timeFormatter.format(Date(it.timestamp))}] ${it.text}"
                            }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Note Transcript", fullText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Transcript copied", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy All")
                    }

                    OutlinedIconButton(
                        onClick = { showDeleteDialog = true },
                        colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }

            noteDetail?.let { detail ->
                if (selectedTab == 0) {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(detail.segments) { segment ->
                            TranscriptItem(segment, timeFormatter)
                        }
                    }
                } else {
                    Box(modifier = Modifier.weight(1f)) {
                        AiInsightsView(detail.note, PaddingValues(0.dp))
                    }
                }
            } ?: Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}


@Composable
fun TranscriptItem(segment: com.example.voxtranscribe.data.db.TranscriptSegment, formatter: SimpleDateFormat) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = formatter.format(Date(segment.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = segment.text,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
fun AiInsightsView(note: com.example.voxtranscribe.data.db.Note, padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        InsightSection(title = "Summary", content = note.summary)
        InsightSection(title = "Key Takeaways & Action Items", content = note.structuredNotes)
        
        if (note.summary == null && note.structuredNotes == null) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("Tap 'Process AI' to generate insights", color = Color.Gray)
            }
        }
    }
}

@Composable
fun InsightSection(title: String, content: String?) {
    Column {
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = content ?: "Not yet generated",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (content == null) Color.Gray else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

