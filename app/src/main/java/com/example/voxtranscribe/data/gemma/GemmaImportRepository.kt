package com.example.voxtranscribe.data.gemma

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.voxtranscribe.data.ModelDownloadProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
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

sealed interface GemmaDownloadResult {
    data class Success(val model: GemmaModelSpec) : GemmaDownloadResult
    data class Failure(val message: String) : GemmaDownloadResult
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

    suspend fun downloadRecommendedTextModel(
        onProgress: (ModelDownloadProgress) -> Unit,
    ): GemmaDownloadResult {
        return withContext(Dispatchers.IO) {
            val spec = GemmaModelCatalog.recommendedTextModel
            val targetFile = getModelFile(spec)
            val targetDir = targetFile.parentFile
                ?: return@withContext GemmaDownloadResult.Failure("Could not prepare model storage.")

            if (!targetDir.exists() && !targetDir.mkdirs()) {
                return@withContext GemmaDownloadResult.Failure("Could not create the model directory.")
            }

            val tempFile = File(targetDir, "${targetFile.name}.download")
            try {
                downloadFile(
                    url = GemmaModelCatalog.downloadUrl(spec),
                    outputFile = tempFile,
                    title = "Text AI model",
                    detail = spec.displayName,
                    onProgress = onProgress,
                )

                if (targetFile.exists() && !targetFile.delete()) {
                    return@withContext GemmaDownloadResult.Failure("Could not replace the existing text AI model.")
                }

                if (!tempFile.renameTo(targetFile)) {
                    return@withContext GemmaDownloadResult.Failure("Could not finalize the downloaded text AI model.")
                }

                refresh()
                GemmaDownloadResult.Success(spec)
            } catch (e: Exception) {
                tempFile.delete()
                GemmaDownloadResult.Failure(e.message ?: "Text AI model download failed.")
            }
        }
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

    private fun downloadFile(
        url: String,
        outputFile: File,
        title: String,
        detail: String,
        onProgress: (ModelDownloadProgress) -> Unit,
    ) {
        val connection = URL(url).openConnection().apply {
            connectTimeout = DOWNLOAD_TIMEOUT_MS
            readTimeout = DOWNLOAD_TIMEOUT_MS
            setRequestProperty("User-Agent", "VoxTranscribe")
        }
        val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
        val startedAtNanos = System.nanoTime()
        var lastUpdateNanos = startedAtNanos
        var downloadedBytes = 0L

        connection.getInputStream().use { input ->
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloadedBytes += read

                    val now = System.nanoTime()
                    if (now - lastUpdateNanos >= PROGRESS_UPDATE_NANOS) {
                        onProgress(
                            ModelDownloadProgress(
                                title = title,
                                detail = detail,
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                                speedBytesPerSecond = bytesPerSecond(downloadedBytes, startedAtNanos, now),
                            )
                        )
                        lastUpdateNanos = now
                    }
                }
            }
        }
        val endedAtNanos = System.nanoTime()
        onProgress(
            ModelDownloadProgress(
                title = title,
                detail = detail,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                speedBytesPerSecond = bytesPerSecond(downloadedBytes, startedAtNanos, endedAtNanos),
            )
        )
    }

    private fun bytesPerSecond(bytes: Long, startedAtNanos: Long, nowNanos: Long): Double {
        val seconds = (nowNanos - startedAtNanos).coerceAtLeast(1L) / 1_000_000_000.0
        return bytes / seconds
    }

    private companion object {
        const val DOWNLOAD_TIMEOUT_MS = 60_000
        const val PROGRESS_UPDATE_NANOS = 250_000_000L
    }
}
