package com.example.voxtranscribe.ui.screens

import android.app.Activity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.voxtranscribe.R
import com.example.voxtranscribe.data.ModelDownloadProgress
import com.example.voxtranscribe.data.AppLanguage
import com.example.voxtranscribe.data.gemma.GemmaImportedModelStatus
import com.example.voxtranscribe.data.gemma.GemmaModelId
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
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            SettingsDropdown(
                label = stringResource(R.string.app_language),
                selectedText = uiState.appLanguage.displayName(),
                options = AppLanguage.entries,
                optionText = { it.displayName() },
                onSelected = { language ->
                    viewModel.setAppLanguage(language) {
                        (context as? Activity)?.recreate()
                    }
                },
            )

            SettingsDropdown(
                label = stringResource(R.string.speech_language),
                selectedText = uiState.parakeetTranscriptionLanguage.displayName(),
                options = ParakeetTranscriptionLanguage.entries,
                optionText = { it.displayName() },
                onSelected = viewModel::setParakeetTranscriptionLanguage,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.debug_stats), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = stringResource(R.string.debug_stats_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = uiState.showDebugStats,
                    onCheckedChange = viewModel::setShowDebugStats,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.models), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = stringResource(R.string.models_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.speech_model_status,
                        if (uiState.hasSpeechModel) stringResource(R.string.installed) else stringResource(R.string.missing),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.text_ai_model_status,
                        if (uiState.hasTextAiModel) stringResource(R.string.installed) else stringResource(R.string.missing),
                    ),
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
                    Text(stringResource(R.string.re_download_speech_model))
                }

                uiState.textAiModelStatuses.forEach { status ->
                    TextAiModelRow(
                        status = status,
                        selectedModelId = uiState.selectedTextAiModelId,
                        isImporting = uiState.isImporting,
                        onDownload = { viewModel.downloadTextAiModel(status.spec) },
                        onSelect = { viewModel.selectTextAiModel(status.spec) },
                    )
                }

                Button(
                    onClick = onNavigateToSetupPreview,
                    enabled = !uiState.isImporting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.view_setup_screen))
                }
            }
        }
    }
}

@Composable
private fun TextAiModelRow(
    status: GemmaImportedModelStatus,
    selectedModelId: GemmaModelId?,
    isImporting: Boolean,
    onDownload: () -> Unit,
    onSelect: () -> Unit,
) {
    val isSelected = status.spec.id == selectedModelId
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onSelect,
                enabled = status.isImported && !isImporting,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(status.spec.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    text = stringResource(
                        R.string.ai_model_detail,
                        formatBytes(status.spec.downloadSizeBytes),
                        status.spec.minDeviceMemoryGb,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (status.isImported) {
                        if (isSelected) stringResource(R.string.installed_selected) else stringResource(R.string.installed)
                    } else {
                        stringResource(R.string.missing)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onDownload,
                enabled = !isImporting,
            ) {
                Text(if (status.isImported) stringResource(R.string.re_download) else stringResource(R.string.download))
            }
        }
    }
}

@Composable
private fun DownloadProgressView(progress: ModelDownloadProgress) {
    val fraction = progress.fraction
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(progress.localizedTitle(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = "${progress.detail} · ${formatBytes(progress.downloadedBytes)} / ${progress.totalBytes?.let(::formatBytes) ?: stringResource(R.string.unknown)} · ${formatBytes(progress.speedBytesPerSecond.toLong())}/s",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModelDownloadProgress.localizedTitle(): String {
    return when (title) {
        "Speech model" -> stringResource(R.string.speech_recognition)
        "Text AI model" -> stringResource(R.string.text_ai)
        else -> title
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SettingsDropdown(
    label: String,
    selectedText: String,
    options: List<T>,
    optionText: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionText(option)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun AppLanguage.displayName(): String {
    return when (this) {
        AppLanguage.SYSTEM -> stringResource(R.string.system_default)
        AppLanguage.ENGLISH -> stringResource(R.string.english)
        AppLanguage.GERMAN -> stringResource(R.string.german)
    }
}

@Composable
private fun ParakeetTranscriptionLanguage.displayName(): String {
    return when (this) {
        ParakeetTranscriptionLanguage.AUTO -> stringResource(R.string.auto)
        ParakeetTranscriptionLanguage.GERMAN_GERMANY -> stringResource(R.string.german)
        ParakeetTranscriptionLanguage.ENGLISH_US -> stringResource(R.string.english)
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
