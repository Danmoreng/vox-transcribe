package com.example.voxtranscribe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.example.voxtranscribe.ui.navigation.VoxNavGraph
import com.example.voxtranscribe.ui.theme.VoxTranscribeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

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
                Box(modifier = Modifier.fillMaxSize()) {
                    VoxNavGraph(navController = navController)
                }
            }
        }
    }
}
