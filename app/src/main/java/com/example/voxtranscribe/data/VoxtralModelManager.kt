package com.example.voxtranscribe.data

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoxtralModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "VoxtralModelManager"
    private val modelFileName = "voxtral-mini-4b-realtime-q4_0.gguf"
    val modelFile = File(context.filesDir, modelFileName)

    fun isModelAvailable(): Boolean {
        return modelFile.exists() && modelFile.length() > 0
    }

    suspend fun copyModelFromUri(uri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    FileOutputStream(modelFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Log.d(TAG, "Model copied successfully to ${modelFile.absolutePath}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy model file", e)
                false
            }
        }
    }

    fun getModelPath(): String {
        return modelFile.absolutePath
    }
}
