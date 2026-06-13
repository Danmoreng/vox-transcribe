package com.example.voxtranscribe.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.voxtranscribe.ui.components.StatItem
import com.example.voxtranscribe.ui.TranscriptionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    onNavigateBack: () -> Unit,
    viewModel: TranscriptionViewModel = hiltViewModel()
) {
    val transcription by viewModel.transcriptionState.collectAsStateWithLifecycle()
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val isPaused by viewModel.isPaused.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val transcriptScrollState = rememberScrollState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else true

        if (audioGranted && notificationGranted) {
            viewModel.startRecording()
        }
    }
    
    // Show toast when engine becomes ready
    LaunchedEffect(engineState) {
        if (engineState == com.example.voxtranscribe.data.EngineState.Ready) {
            Toast.makeText(context, "Engine Loaded - Ready to Record", Toast.LENGTH_SHORT).show()
        } else if (engineState == com.example.voxtranscribe.data.EngineState.Uninitialized) {
            Toast.makeText(context, "Import and select the Nemotron streaming model before recording", Toast.LENGTH_SHORT).show()
        }
    }

    // Auto-start recording when entering screen
    LaunchedEffect(Unit) {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    LaunchedEffect(transcription, isListening, isPaused) {
        if (transcription.isNotBlank() && (isListening || isPaused)) {
            transcriptScrollState.animateScrollTo(transcriptScrollState.maxValue)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live Recording") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isListening || isPaused) viewModel.stopRecording()
                        onNavigateBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Transcription", transcription)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                    }
                }
            )
        },
        bottomBar = {
            RecordingControlsBar(
                durationSeconds = stats.durationSeconds,
                wordCount = stats.wordCount,
                isListening = isListening,
                isPaused = isPaused,
                isLoading = engineState == com.example.voxtranscribe.data.EngineState.Loading,
                onPause = viewModel::pauseRecording,
                onResume = viewModel::resumeRecording,
                onStop = viewModel::stopRecording,
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            if (stats.showDebugStats) {
                Surface(
                    tonalElevation = 1.dp,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatItem(label = "Status", value = stats.debugStatus)
                        StatItem(label = "RTF", value = stats.realtimeFactorText)
                        StatItem(label = "Queue", value = "${stats.queuedClips}")
                        StatItem(
                            label = "Dropped",
                            value = "${stats.droppedClips}",
                            color = if (stats.droppedClips > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(transcriptScrollState)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = when {
                        transcription.isNotEmpty() -> transcription
                        isPaused -> "Paused"
                        else -> "Listening..."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (transcription.isEmpty()) Color.Gray else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun RecordingControlsBar(
    durationSeconds: Long,
    wordCount: Int,
    isListening: Boolean,
    isPaused: Boolean,
    isLoading: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        tonalElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                StatItem(label = "Time", value = "${durationSeconds}s")
                StatItem(label = "Words", value = "$wordCount")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = if (isPaused) onResume else onPause,
                    enabled = !isLoading && (isListening || isPaused),
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isPaused) "Resume" else "Pause",
                    )
                }
                FilledIconButton(
                    onClick = onStop,
                    enabled = !isLoading && (isListening || isPaused),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            }
        }
    }
}
