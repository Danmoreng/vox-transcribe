package com.example.voxtranscribe

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.voxtranscribe.ui.TranscriptionViewModel
import com.example.voxtranscribe.ui.theme.VoxTranscribeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            VoxTranscribeTheme {
                val viewModel: TranscriptionViewModel = hiltViewModel()
                TranscriptionScreen(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptionScreen(viewModel: TranscriptionViewModel) {
    val transcription by viewModel.transcriptionState.collectAsStateWithLifecycle()
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.toggleListening()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vox Transcribe") },
                actions = {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Transcription", transcription)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                    }
                    IconButton(onClick = { viewModel.clearText() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (isListening) "Stop" else "Start"
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Stats Bar
            Surface(
                tonalElevation = 2.dp,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatItem(
                        label = "Engine",
                        value = if (stats.isOffline) "Offline" else "Online",
                        color = if (stats.isOffline) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                    StatItem(
                        label = "Duration",
                        value = "${stats.durationSeconds}s"
                    )
                    StatItem(
                        label = "Words",
                        value = "${stats.wordCount}"
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = if (transcription.isEmpty()) "Start speaking to see transcription..." else transcription,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (transcription.isEmpty()) Color.Gray else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
            color = color
        )
    }
}
