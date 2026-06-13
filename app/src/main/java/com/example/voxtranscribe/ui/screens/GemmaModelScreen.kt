package com.example.voxtranscribe.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.voxtranscribe.ui.GemmaModelCardUiState
import com.example.voxtranscribe.ui.GemmaModelViewModel
import com.example.voxtranscribe.ui.ParakeetModelCardUiState
import com.example.voxtranscribe.data.parakeet.ParakeetTranscriptionLanguage
import java.text.DecimalFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GemmaModelScreen(
    onNavigateBack: () -> Unit,
    viewModel: GemmaModelViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()

    val gemmaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let(viewModel::importModel)
    }

    val parakeetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let(viewModel::importParakeetModel)
    }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Management") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = "Realtime Transcription",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Download the public Nemotron ONNX ASR model from Hugging Face. Manual ZIP import remains available as a fallback.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Transcription Language",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Use Auto for mixed or unknown speech. If you know the spoken language, selecting it can reduce language drift and instruction leakage during transcription.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ParakeetTranscriptionLanguage.entries.forEach { language ->
                    FilterChip(
                        selected = uiState.parakeetTranscriptionLanguage == language,
                        onClick = { viewModel.setParakeetTranscriptionLanguage(language) },
                        label = { Text(language.displayName) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Show recording debug stats",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Shows backend, speed, RTF, queue and dropped chunks on the recording screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = uiState.showDebugStats,
                        onCheckedChange = viewModel::setShowDebugStats,
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Runtime",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "The Nemotron build targets arm64-v8a and currently uses ONNX Runtime GenAI CPU execution. QNN/GPU can be explored later without blocking realtime use.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            ParakeetRuntimeStatusCard(
                activeBackend = uiState.parakeetRuntimeActiveBackend,
                activeLanguage = uiState.parakeetRuntimeActiveLanguage,
                lastError = uiState.parakeetRuntimeLastError,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = viewModel::downloadParakeetModel,
                enabled = !uiState.isImporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.padding(4.dp))
                Text("Download Speech Model")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { parakeetLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                enabled = !uiState.isImporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Manual ZIP Import")
            }

            if (uiState.isImporting) {
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator()
                    uiState.progressMessage?.let { progress ->
                        Text(
                            text = progress,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            uiState.parakeetModels.forEach { model ->
                ParakeetModelCard(
                    model = model,
                    onSelect = { viewModel.selectParakeetModel(model.status.spec.id) },
                    onDelete = { viewModel.deleteParakeetModel(model.status.spec.id) },
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Gemma Import",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Download Gemma 4 E2B for transcript cleanup, automatic titles and summaries. Manual .litertlm import remains available as a fallback.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Gemma Runtime",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            RuntimeStatusCard(
                gpuDelegateAvailable = uiState.runtimeGpuDelegateAvailable,
                activeBackend = uiState.runtimeActiveBackend,
                fallbackReason = uiState.runtimeFallbackReason,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = viewModel::downloadRecommendedTextAiModel,
                enabled = !uiState.isImporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.padding(4.dp))
                Text("Download Text AI Model")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { gemmaLauncher.launch("*/*") },
                enabled = !uiState.isImporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Manual .litertlm Import")
            }

            Spacer(modifier = Modifier.height(24.dp))
            uiState.models.forEach { model ->
                GemmaModelCard(
                    model = model,
                    onSelect = { viewModel.selectModel(model.status.spec.id) },
                    onDelete = { viewModel.deleteModel(model.status.spec.id) },
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun ParakeetRuntimeStatusCard(
    activeBackend: String?,
    activeLanguage: String?,
    lastError: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RuntimeStatusRow(
                label = "Active Backend",
                value = activeBackend?.uppercase() ?: "ORT CPU",
            )
            RuntimeStatusRow(
                label = "Language",
                value = activeLanguage ?: "auto",
            )
            if (!lastError.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RuntimeStatusCard(
    gpuDelegateAvailable: Boolean?,
    activeBackend: String?,
    fallbackReason: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RuntimeStatusRow(
                label = "GPU Delegate",
                value = when (gpuDelegateAvailable) {
                    true -> "Available"
                    false -> "Unavailable"
                    null -> "Not checked yet"
                },
            )
            RuntimeStatusRow(
                label = "Active Backend",
                value = activeBackend?.uppercase() ?: "Not initialized yet",
            )
            if (!fallbackReason.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = fallbackReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RuntimeStatusRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ParakeetModelCard(
    model: ParakeetModelCardUiState,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (model.isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.status.spec.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = model.status.spec.sourceModel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (model.isSelected) {
                        Icons.Default.RadioButtonChecked
                    } else {
                        Icons.Default.RadioButtonUnchecked
                    },
                    contentDescription = null,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Minimum device memory: ${model.status.spec.minDeviceMemoryGb} GB",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (model.status.isImported) {
                    "Imported size: ${formatSize(model.status.fileSizeBytes)}"
                } else {
                    "Not imported"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (model.status.isImported) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onSelect,
                    enabled = model.status.isImported && !model.isSelected,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (model.isSelected) "Selected" else "Use This Model")
                }
                TextButton(
                    onClick = onDelete,
                    enabled = model.status.isImported,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.padding(2.dp))
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun GemmaModelCard(
    model: GemmaModelCardUiState,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (model.isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.status.spec.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = model.status.spec.expectedFileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (model.isSelected) {
                        Icons.Default.RadioButtonChecked
                    } else {
                        Icons.Default.RadioButtonUnchecked
                    },
                    contentDescription = null,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Minimum device memory: ${model.status.spec.minDeviceMemoryGb} GB",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (model.status.isImported) {
                    "Imported size: ${formatSize(model.status.fileSizeBytes)}"
                } else {
                    "Not imported"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (model.status.isImported) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onSelect,
                    enabled = model.status.isImported && !model.isSelected,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (model.isSelected) "Selected" else "Use This Model")
                }
                TextButton(
                    onClick = onDelete,
                    enabled = model.status.isImported,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.padding(2.dp))
                    Text("Delete")
                }
            }
        }
    }
}

private fun formatSize(sizeBytes: Long): String {
    if (sizeBytes <= 0L) return "0 B"
    val mb = sizeBytes / (1024.0 * 1024.0)
    return DecimalFormat("#,##0.0 MB").format(mb)
}
