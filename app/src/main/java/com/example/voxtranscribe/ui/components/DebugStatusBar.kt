package com.example.voxtranscribe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.voxtranscribe.data.EngineState
import com.example.voxtranscribe.data.VoxtralModelManager
import com.example.voxtranscribe.data.VoxtralTranscriptionRepository
import java.io.File

import androidx.compose.foundation.layout.statusBarsPadding

import com.example.voxtranscribe.data.DynamicTranscriptionRepository
import com.example.voxtranscribe.data.EngineType

@Composable
fun DebugStatusBar(
    transcriptionRepo: DynamicTranscriptionRepository,
    voxtralRepo: VoxtralTranscriptionRepository,
    modelManager: VoxtralModelManager,
    onNavigateToSettings: () -> Unit
) {
    val engineType by transcriptionRepo.currentEngineType.collectAsState()
    val engineState by transcriptionRepo.engineState.collectAsState()
    val gpuBackend by voxtralRepo.gpuBackend.collectAsState()
    val currentModel = modelManager.getSelectedModel()
    val availableModels = modelManager.getAvailableModels()
    
    var showEngineMenu by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    var showBackendMenu by remember { mutableStateOf(false) }

    val statusColor = when (engineState) {
        EngineState.Ready -> Color(0xFF4CAF50)
        EngineState.Loading -> Color(0xFFFFC107)
        EngineState.Error -> Color(0xFFF44336)
        EngineState.Uninitialized -> Color.Gray
    }

    Surface(
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Engine selection
            Box {
                Text(
                    text = engineType.name,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .clickable { showEngineMenu = true }
                        .padding(8.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                DropdownMenu(
                    expanded = showEngineMenu,
                    onDismissRequest = { showEngineMenu = false }
                ) {
                    EngineType.values().forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.name) },
                            onClick = {
                                transcriptionRepo.setEngineType(type)
                                showEngineMenu = false
                            }
                        )
                    }
                }
            }

            // Model Info & Selection (only for Voxtral)
            if (engineType == EngineType.Voxtral) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .clickable { showModelMenu = true }
                ) {
                    Text(
                        text = "Model: ${currentModel?.name ?: "None"}",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(statusColor, shape = MaterialTheme.shapes.extraSmall)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = engineState.name,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            ),
                            color = statusColor
                        )
                    }
                    
                    DropdownMenu(
                        expanded = showModelMenu,
                        onDismissRequest = { showModelMenu = false }
                    ) {
                        if (availableModels.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No models found") },
                                onClick = { showModelMenu = false }
                            )
                        }
                        availableModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.name) },
                                onClick = {
                                    modelManager.setSelectedModel(model.name)
                                    voxtralRepo.loadModel()
                                    showModelMenu = false
                                }
                            )
                        }
                    }
                }

                // Backend Selection
                Box {
                    Text(
                        text = getBackendName(gpuBackend),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .clickable { showBackendMenu = true }
                            .padding(8.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    
                    DropdownMenu(
                        expanded = showBackendMenu,
                        onDismissRequest = { showBackendMenu = false }
                    ) {
                        val backends = listOf(
                            0 to "CPU",
                            1 to "Auto",
                            4 to "Vulkan",
                            5 to "OpenCL"
                        )
                        backends.forEach { (id, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    voxtralRepo.setGpuBackend(id)
                                    showBackendMenu = false
                                }
                            )
                        }
                    }
                }
            } else {
                // Spacer for Google Engine
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (engineState == EngineState.Ready) "Ready" else "Unknown",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
            }

            IconButton(onClick = onNavigateToSettings) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun getBackendName(id: Int): String = when (id) {
    0 -> "CPU"
    1 -> "Auto"
    4 -> "Vulkan"
    5 -> "OpenCL"
    else -> "Unknown"
}
