package com.example.voxtranscribe.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.voxtranscribe.data.EngineState
import com.example.voxtranscribe.ui.VoxtralModelViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoxtralModelScreen(
    onNavigateBack: () -> Unit,
    viewModel: VoxtralModelViewModel = hiltViewModel()
) {
    val isModelAvailable by viewModel.isModelAvailable.collectAsStateWithLifecycle()
    val isCopying by viewModel.isCopying.collectAsStateWithLifecycle()
    val copyResult by viewModel.copyResult.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.copyModel(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voxtral Model Management") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            
            Text(
                text = "Voxtral Transcription Engine",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "Status", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Model File:")
                        if (isModelAvailable) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = "Available", tint = Color.Green)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Found")
                            }
                        } else {
                             Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Error, contentDescription = "Missing", tint = Color.Red)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Missing")
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Engine State:")
                        StatusChip(state = engineState)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            if (isCopying) {
                CircularProgressIndicator()
                Text("Copying model file... Do not close.", modifier = Modifier.padding(top = 8.dp))
            } else {
                Button(
                    onClick = { launcher.launch("*/*") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = engineState != EngineState.Loading
                ) {
                    Text(if (isModelAvailable) "Update Model File" else "Import .gguf Model File")
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = { viewModel.loadEngine() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isModelAvailable && engineState != EngineState.Loading && engineState != EngineState.Ready,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    if (engineState == EngineState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onSecondary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Loading (~30s)...")
                    } else {
                        Text("Load Engine")
                    }
                }
                
                if (engineState == EngineState.Ready) {
                    Text(
                        text = "Engine is ready. You can now start recording.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Green,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedButton(
                        onClick = { viewModel.runTest() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Run Offline Test (3s Noise)")
                    }
                }
            }
            
            copyResult?.let { result ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(result, color = if (result.contains("success")) Color.Green else Color.Red)
            }
            
            testResult?.let { result ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(
                        text = result,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
fun StatusChip(state: EngineState) {
    val (color, text, icon) = when (state) {
        EngineState.Uninitialized -> Triple(Color.Gray, "Uninitialized", Icons.Default.Warning)
        EngineState.Loading -> Triple(Color.Blue, "Loading...", Icons.Default.Warning)
        EngineState.Ready -> Triple(Color.Green, "Ready", Icons.Default.CheckCircle)
        EngineState.Error -> Triple(Color.Red, "Error", Icons.Default.Error)
    }
    
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = text, tint = color)
        Spacer(modifier = Modifier.width(4.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}
