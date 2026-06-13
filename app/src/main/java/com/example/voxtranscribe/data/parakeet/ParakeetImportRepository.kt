package com.example.voxtranscribe.data.parakeet

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class ParakeetImportedModelStatus(
    val spec: ParakeetModelSpec,
    val file: File,
    val isImported: Boolean,
    val fileSizeBytes: Long,
)

sealed interface ParakeetImportResult {
    data class Success(val model: ParakeetModelSpec) : ParakeetImportResult
    data class UnsupportedFile(val message: String) : ParakeetImportResult
    data class Failure(val message: String) : ParakeetImportResult
}

@Singleton
class ParakeetImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _modelStatuses = MutableStateFlow(readModelStatuses())
    val modelStatuses: StateFlow<List<ParakeetImportedModelStatus>> = _modelStatuses.asStateFlow()

    fun getImportedModelPath(modelId: ParakeetModelId): String? {
        val status = _modelStatuses.value.firstOrNull { it.spec.id == modelId } ?: return null
        return status.file.takeIf { it.exists() }?.absolutePath
    }

    fun refresh() {
        _modelStatuses.value = readModelStatuses()
    }

    suspend fun importModelFromUri(uri: Uri): ParakeetImportResult {
        return withContext(Dispatchers.IO) {
            val sourceFileName = getDisplayName(uri)
                ?: return@withContext ParakeetImportResult.Failure("Could not determine the selected file name.")

            if (!ParakeetModelCatalog.isSupportedImportFile(sourceFileName)) {
                return@withContext ParakeetImportResult.UnsupportedFile(
                    "Select the GGUF file for ${ParakeetModelCatalog.streamingModel.sourceModel}."
                )
            }

            val spec = ParakeetModelCatalog.streamingModel
            val targetFile = getModelFile(spec)
            val targetDir = targetFile.parentFile
                ?: return@withContext ParakeetImportResult.Failure("Could not prepare model storage.")

            if (!targetDir.exists() && !targetDir.mkdirs()) {
                return@withContext ParakeetImportResult.Failure("Could not create the model directory.")
            }

            val tempFile = File(targetDir, "${targetFile.name}.tmp")

            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: return@withContext ParakeetImportResult.Failure("Could not open the selected file.")

                if (targetFile.exists() && !targetFile.delete()) {
                    return@withContext ParakeetImportResult.Failure("Could not replace the existing imported model.")
                }

                if (!tempFile.renameTo(targetFile)) {
                    return@withContext ParakeetImportResult.Failure("Could not finalize the imported model file.")
                }

                refresh()
                ParakeetImportResult.Success(spec)
            } catch (e: FileNotFoundException) {
                tempFile.delete()
                ParakeetImportResult.Failure(
                    "Android could not read the selected file. Choose it through the system file picker."
                )
            } catch (e: SecurityException) {
                tempFile.delete()
                ParakeetImportResult.Failure(
                    "Android did not grant access to the selected file. Select it again in the system file picker."
                )
            } catch (e: IOException) {
                tempFile.delete()
                ParakeetImportResult.Failure(
                    "Could not copy the model into private app storage: ${e.message ?: "I/O error"}"
                )
            }
        }
    }

    suspend fun deleteImportedModel(modelId: ParakeetModelId): Boolean {
        return withContext(Dispatchers.IO) {
            val spec = ParakeetModelCatalog.supportedModels.firstOrNull { it.id == modelId }
                ?: return@withContext false
            val deleted = getModelFile(spec).let { file ->
                !file.exists() || file.delete()
            }
            if (deleted) {
                refresh()
            }
            deleted
        }
    }

    private fun readModelStatuses(): List<ParakeetImportedModelStatus> {
        return ParakeetModelCatalog.supportedModels.map { spec ->
            val file = getModelFile(spec)
            ParakeetImportedModelStatus(
                spec = spec,
                file = file,
                isImported = file.exists(),
                fileSizeBytes = if (file.exists()) file.length() else 0L,
            )
        }
    }

    private fun getModelFile(spec: ParakeetModelSpec): File {
        return File(getModelDirectory(), spec.storageFileName)
    }

    private fun getModelDirectory(): File {
        return File(context.filesDir, "parakeet")
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
