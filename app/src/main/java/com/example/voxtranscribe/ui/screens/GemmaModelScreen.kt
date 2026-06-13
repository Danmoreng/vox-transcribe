package com.example.voxtranscribe.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.voxtranscribe.data.ModelDownloadProgress
import com.example.voxtranscribe.data.parakeet.ParakeetTranscriptionLanguage
import com.example.voxtranscribe.ui.GemmaModelViewModel
import java.text.DecimalFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GemmaModelScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSetupPreview: () -> Unit,
    viewModel: GemmaModelViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Language", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ParakeetTranscriptionLanguage.entries.forEach { language ->
                        FilterChip(
                            selected = uiState.parakeetTranscriptionLanguage == language,
                            onClick = { viewModel.setParakeetTranscriptionLanguage(language) },
                            label = { Text(language.displayName) },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Models", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Both required models are stored locally on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Speech: ${if (uiState.hasSpeechModel) "installed" else "missing"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Text AI: ${if (uiState.hasTextAiModel) "installed" else "missing"}",
                    style = MaterialTheme.typography.bodyMedium,
                )

                uiState.downloadProgress?.let { progress ->
                    DownloadProgressView(progress)
                }

                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = viewModel::downloadParakeetModel,
                    enabled = !uiState.isImporting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Re-download speech model")
                }
                Button(
                    onClick = viewModel::downloadRecommendedTextAiModel,
                    enabled = !uiState.isImporting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Re-download AI model")
                }
                Button(
                    onClick = onNavigateToSetupPreview,
                    enabled = !uiState.isImporting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("View setup screen")
                }
            }
        }
    }
}

@Composable
private fun DownloadProgressView(progress: ModelDownloadProgress) {
    val fraction = progress.fraction
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(progress.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = "${progress.detail} · ${formatBytes(progress.downloadedBytes)} / ${progress.totalBytes?.let(::formatBytes) ?: "unknown"} · ${formatBytes(progress.speedBytesPerSecond.toLong())}/s",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        "${DecimalFormat("#,##0.00").format(mb / 1024.0)} GB"
    } else {
        "${DecimalFormat("#,##0.0").format(mb)} MB"
    }
}
