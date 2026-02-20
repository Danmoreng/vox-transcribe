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
    private var selectedModelName: String? = null

    fun isModelAvailable(): Boolean {
        return getAvailableModels().isNotEmpty()
    }

    fun getAvailableModels(): List<File> {
        val files = context.filesDir.listFiles { _, name -> name.endsWith(".gguf") }
        return files?.toList() ?: emptyList()
    }

    fun getSelectedModel(): File? {
        val models = getAvailableModels()
        if (models.isEmpty()) return null
        
        val selected = selectedModelName?.let { name -> models.find { it.name == name } }
        return selected ?: models.first()
    }

    fun setSelectedModel(name: String) {
        selectedModelName = name
    }

    suspend fun copyModelFromUri(uri: Uri, fileName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val targetFile = File(context.filesDir, fileName)
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    FileOutputStream(targetFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Log.d(TAG, "Model copied successfully to ${targetFile.absolutePath}")
                selectedModelName = fileName
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy model file", e)
                false
            }
        }
    }

    fun getModelPath(): String? {
        return getSelectedModel()?.absolutePath
    }
}
