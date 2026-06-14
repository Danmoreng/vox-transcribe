package com.example.voxtranscribe

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.example.voxtranscribe.data.AppLanguage
import com.example.voxtranscribe.data.AppLanguageRepository
import com.example.voxtranscribe.ui.navigation.VoxNavGraph
import com.example.voxtranscribe.ui.theme.VoxTranscribeTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val language = AppLanguageRepository.readStoredLanguage(newBase)
        super.attachBaseContext(newBase.withAppLanguage(language))
    }

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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VoxNavGraph(navController = navController)
                }
            }
        }
    }
}

private fun Context.withAppLanguage(language: AppLanguage): Context {
    val localeTag = language.localeTag ?: return this
    val configuration = Configuration(resources.configuration)
    configuration.setLocales(LocaleList(Locale.forLanguageTag(localeTag)))
    return createConfigurationContext(configuration)
}
