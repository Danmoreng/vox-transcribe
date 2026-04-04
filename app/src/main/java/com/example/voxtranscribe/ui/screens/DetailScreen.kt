package com.example.voxtranscribe.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.voxtranscribe.data.ai.AiOutputLanguage
import com.example.voxtranscribe.data.ai.AiSummaryStyle
import com.example.voxtranscribe.ui.DetailViewModel
import java.text.SimpleDateFormat
import java.util.*

import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Segment
import com.mikepenz.markdown.m3.Markdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    noteId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToModelSettings: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel()
) {
    val noteDetail by viewModel.getNoteDetail(noteId).collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val isDeleted by viewModel.isDeleted.collectAsStateWithLifecycle()
    val isAiModelReady by viewModel.isAiModelReady.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val aiPreferences by viewModel.aiPreferences.collectAsStateWithLifecycle()
    
    val context = LocalContext.current
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val snackbarHostState = remember { SnackbarHostState() }
    
    var selectedTab by remember { mutableStateOf(0) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAiSettings by rememberSaveable { mutableStateOf(false) }

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
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
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
                    if (!isAiModelReady) {
                        Button(
                            onClick = onNavigateToModelSettings,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Import AI Model", style = MaterialTheme.typography.labelLarge)
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

                if (isAiModelReady) {
                    AiPreferencesCard(
                        outputLanguage = aiPreferences.outputLanguage,
                        summaryStyle = aiPreferences.summaryStyle,
                        expanded = showAiSettings,
                        onToggleExpanded = { showAiSettings = !showAiSettings },
                        onOutputLanguageSelected = viewModel::setAiOutputLanguage,
                        onSummaryStyleSelected = viewModel::setAiSummaryStyle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
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
private fun AiPreferencesCard(
    outputLanguage: AiOutputLanguage,
    summaryStyle: AiSummaryStyle,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOutputLanguageSelected: (AiOutputLanguage) -> Unit,
    onSummaryStyleSelected: (AiSummaryStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onToggleExpanded,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "AI Output Settings",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Language: ${outputLanguage.displayName}  |  Style: ${summaryStyle.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse AI output settings" else "Expand AI output settings",
                )
            }
            if (expanded) {
                Text(
                    text = "Choose the output language and summary style before running AI processing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PreferenceChipRow(
                    title = "Output Language",
                    entries = AiOutputLanguage.entries.toList(),
                    selected = outputLanguage,
                    label = { it.displayName },
                    onSelected = onOutputLanguageSelected,
                )
                PreferenceChipRow(
                    title = "Summary Style",
                    entries = AiSummaryStyle.entries.toList(),
                    selected = summaryStyle,
                    label = { it.displayName },
                    onSelected = onSummaryStyleSelected,
                )
            }
        }
    }
}

@Composable
private fun <T> PreferenceChipRow(
    title: String,
    entries: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            entries.forEach { entry ->
                FilterChip(
                    selected = selected == entry,
                    onClick = { onSelected(entry) },
                    label = { Text(label(entry)) },
                )
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
            if (content == null) {
                Text(
                    text = "Not yet generated",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            } else {
                Markdown(
                    content,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

