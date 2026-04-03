package com.example.voxtranscribe.data.gemma

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class GemmaImportedModelStatus(
    val spec: GemmaModelSpec,
    val file: File,
    val isImported: Boolean,
    val fileSizeBytes: Long,
)

sealed interface GemmaImportResult {
    data class Success(val model: GemmaModelSpec) : GemmaImportResult
    data class UnsupportedFile(val message: String) : GemmaImportResult
    data class Failure(val message: String) : GemmaImportResult
}

@Singleton
class GemmaImportRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _modelStatuses = MutableStateFlow(readModelStatuses())
    val modelStatuses: StateFlow<List<GemmaImportedModelStatus>> = _modelStatuses.asStateFlow()

    fun getImportedModelPath(modelId: GemmaModelId): String? {
        val status = _modelStatuses.value.firstOrNull { it.spec.id == modelId } ?: return null
        return status.file.takeIf { it.exists() }?.absolutePath
    }

    fun refresh() {
        _modelStatuses.value = readModelStatuses()
    }

    suspend fun importModelFromUri(uri: Uri): GemmaImportResult {
        return withContext(Dispatchers.IO) {
            val sourceFileName = getDisplayName(uri)
                ?: return@withContext GemmaImportResult.Failure("Could not determine the selected file name.")

            val spec = GemmaModelCatalog.findSupportedModelForImport(sourceFileName)
                ?: return@withContext GemmaImportResult.UnsupportedFile(
                    "Only ${GemmaModelCatalog.supportedModels.joinToString { it.expectedFileName }} are supported."
                )

            val targetFile = getModelFile(spec)
            val targetDir = targetFile.parentFile
                ?: return@withContext GemmaImportResult.Failure("Could not prepare model storage.")

            if (!targetDir.exists() && !targetDir.mkdirs()) {
                return@withContext GemmaImportResult.Failure("Could not create the model directory.")
            }

            val tempFile = File(targetDir, "${targetFile.name}.tmp")

            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: return@withContext GemmaImportResult.Failure("Could not open the selected file.")

                if (targetFile.exists() && !targetFile.delete()) {
                    return@withContext GemmaImportResult.Failure("Could not replace the existing imported model.")
                }

                if (!tempFile.renameTo(targetFile)) {
                    return@withContext GemmaImportResult.Failure("Could not finalize the imported model file.")
                }

                refresh()
                GemmaImportResult.Success(spec)
            } catch (e: Exception) {
                tempFile.delete()
                GemmaImportResult.Failure(e.message ?: "Import failed.")
            }
        }
    }

    suspend fun deleteImportedModel(modelId: GemmaModelId): Boolean {
        return withContext(Dispatchers.IO) {
            val spec = GemmaModelCatalog.supportedModels.firstOrNull { it.id == modelId } ?: return@withContext false
            val deleted = getModelFile(spec).let { file ->
                !file.exists() || file.delete()
            }
            if (deleted) {
                refresh()
            }
            deleted
        }
    }

    private fun readModelStatuses(): List<GemmaImportedModelStatus> {
        return GemmaModelCatalog.supportedModels.map { spec ->
            val file = getModelFile(spec)
            GemmaImportedModelStatus(
                spec = spec,
                file = file,
                isImported = file.exists(),
                fileSizeBytes = if (file.exists()) file.length() else 0L,
            )
        }
    }

    private fun getModelFile(spec: GemmaModelSpec): File {
        return File(getModelDirectory(), spec.expectedFileName)
    }

    private fun getModelDirectory(): File {
        return File(context.getExternalFilesDir(null), "gemma")
    }

    private fun getDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
