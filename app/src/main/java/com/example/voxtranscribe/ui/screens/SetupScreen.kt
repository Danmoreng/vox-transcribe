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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.voxtranscribe.R
import com.example.voxtranscribe.data.ModelDownloadProgress
import com.example.voxtranscribe.ui.GemmaModelViewModel
import java.text.DecimalFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    previewMode: Boolean = false,
    viewModel: GemmaModelViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.setupComplete) {
        if (uiState.setupComplete && !previewMode) {
            onSetupComplete()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.setup_title)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = stringResource(R.string.setup_headline),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.setup_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SetupModelCard(
                title = stringResource(R.string.speech_recognition),
                description = stringResource(R.string.speech_recognition_description),
                installed = uiState.hasSpeechModel,
                downloadSizeBytes = uiState.speechModelDownloadSizeBytes,
                buttonText = stringResource(R.string.download_speech_model),
                enabled = !uiState.isImporting && !uiState.hasSpeechModel,
                progress = uiState.downloadProgress?.takeIf { it.title == "Speech model" },
                onClick = viewModel::downloadParakeetModel,
            )

            SetupModelCard(
                title = stringResource(R.string.text_ai),
                description = stringResource(R.string.text_ai_description),
                installed = uiState.hasTextAiModel,
                downloadSizeBytes = uiState.textAiModelDownloadSizeBytes,
                buttonText = stringResource(R.string.download_ai_model),
                enabled = !uiState.isImporting && !uiState.hasTextAiModel,
                progress = uiState.downloadProgress?.takeIf { it.title == "Text AI model" },
                onClick = viewModel::downloadRecommendedTextAiModel,
            )

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onSetupComplete,
                enabled = uiState.setupComplete || previewMode,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (previewMode) stringResource(R.string.done) else stringResource(R.string.start_using_vox))
            }
        }
    }
}

@Composable
private fun SetupModelCard(
    title: String,
    description: String,
    installed: Boolean,
    downloadSizeBytes: Long,
    buttonText: String,
    enabled: Boolean,
    progress: ModelDownloadProgress?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (installed) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = stringResource(R.string.download_size, formatBytes(downloadSizeBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (installed) Icons.Default.CheckCircle else Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                installed -> Text(stringResource(R.string.installed), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                progress != null -> DownloadProgressView(progress)
                else -> Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(buttonText)
                }
            }
        }
    }
}

@Composable
private fun DownloadProgressView(progress: ModelDownloadProgress) {
    val fraction = progress.fraction
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(progress.detail, style = MaterialTheme.typography.bodySmall)
                Text(
                    text = "${formatBytes(progress.downloadedBytes)} / ${progress.totalBytes?.let(::formatBytes) ?: stringResource(R.string.unknown)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${formatBytes(progress.speedBytesPerSecond.toLong())}/s",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
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
