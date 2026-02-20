package com.example.voxtranscribe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.example.voxtranscribe.data.VoxtralModelManager
import com.example.voxtranscribe.data.VoxtralTranscriptionRepository
import com.example.voxtranscribe.ui.components.DebugStatusBar
import com.example.voxtranscribe.ui.navigation.Screen
import com.example.voxtranscribe.ui.navigation.VoxNavGraph
import com.example.voxtranscribe.ui.theme.VoxTranscribeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var transcriptionRepo: com.example.voxtranscribe.data.DynamicTranscriptionRepository

    @Inject
    lateinit var voxtralRepo: com.example.voxtranscribe.data.VoxtralTranscriptionRepository

    @Inject
    lateinit var modelManager: VoxtralModelManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT, 
                android.graphics.Color.TRANSPARENT
            )
        )
        
        setContent {
            VoxTranscribeTheme {
                val navController = rememberNavController()
                
                // Auto-load model on app start if Voxtral is selected
                LaunchedEffect(Unit) {
                    if (transcriptionRepo.currentEngineType.value == com.example.voxtranscribe.data.EngineType.Voxtral) {
                        voxtralRepo.loadModel()
                    }
                }
                
                Column(modifier = Modifier.fillMaxSize()) {
                    // Global Debug & Status Bar
                    DebugStatusBar(
                        transcriptionRepo = transcriptionRepo,
                        voxtralRepo = voxtralRepo,
                        modelManager = modelManager,
                        onNavigateToSettings = {
                            navController.navigate(Screen.VoxtralModel.route)
                        }
                    )
                    
                    // Main Content
                    VoxNavGraph(navController = navController)
                }
            }
        }
    }
}
