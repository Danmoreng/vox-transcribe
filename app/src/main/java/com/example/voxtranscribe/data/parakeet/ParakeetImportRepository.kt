package com.example.voxtranscribe.data.parakeet

import android.content.Context
import com.example.voxtranscribe.data.ModelDownloadProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
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

sealed interface ParakeetDownloadResult {
    data class Success(val model: ParakeetModelSpec) : ParakeetDownloadResult
    data class Failure(val message: String) : ParakeetDownloadResult
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

    suspend fun downloadDefaultModel(
        onProgress: (ModelDownloadProgress) -> Unit,
    ): ParakeetDownloadResult {
        return withContext(Dispatchers.IO) {
            val spec = ParakeetModelCatalog.streamingModel
            val targetFile = getModelFile(spec)
            val targetDir = targetFile.parentFile
                ?: return@withContext ParakeetDownloadResult.Failure("Could not prepare model storage.")

            if (!targetDir.exists() && !targetDir.mkdirs()) {
                return@withContext ParakeetDownloadResult.Failure("Could not create the model directory.")
            }

            val tempDir = File(targetDir, "${targetFile.name}.download")
            tempDir.deleteRecursively()
            if (!tempDir.mkdirs()) {
                return@withContext ParakeetDownloadResult.Failure("Could not create a temporary download directory.")
            }

            try {
                ParakeetModelCatalog.streamingModelFiles.forEachIndexed { index, fileName ->
                    val outputFile = File(tempDir, fileName)
                    outputFile.parentFile?.mkdirs()
                    downloadFile(
                        url = ParakeetModelCatalog.streamingModelDownloadUrl(fileName),
                        outputFile = outputFile,
                        title = "Speech model",
                        detail = "${index + 1}/${ParakeetModelCatalog.streamingModelFiles.size}: $fileName",
                        onProgress = onProgress,
                    )
                }

                if (!File(tempDir, "genai_config.json").isFile) {
                    return@withContext ParakeetDownloadResult.Failure("Downloaded model is incomplete.")
                }

                if (targetFile.exists() && !targetFile.deleteRecursively()) {
                    return@withContext ParakeetDownloadResult.Failure("Could not replace the existing speech model.")
                }

                if (!tempDir.renameTo(targetFile)) {
                    return@withContext ParakeetDownloadResult.Failure("Could not finalize the downloaded speech model.")
                }

                refresh()
                ParakeetDownloadResult.Success(spec)
            } catch (e: Exception) {
                tempDir.deleteRecursively()
                ParakeetDownloadResult.Failure(e.message ?: "Speech model download failed.")
            }
        }
    }

    private fun readModelStatuses(): List<ParakeetImportedModelStatus> {
        return ParakeetModelCatalog.supportedModels.map { spec ->
            val file = getModelFile(spec)
            ParakeetImportedModelStatus(
                spec = spec,
                file = file,
                isImported = File(file, "genai_config.json").isFile,
                fileSizeBytes = if (file.exists()) file.totalSizeBytes() else 0L,
            )
        }
    }

    private fun getModelFile(spec: ParakeetModelSpec): File {
        return File(getModelDirectory(), spec.storageFileName)
    }

    private fun getModelDirectory(): File {
        return context.filesDir
    }

    private fun File.totalSizeBytes(): Long {
        if (isFile) return length()
        return walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
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
