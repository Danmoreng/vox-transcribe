package com.example.voxtranscribe.data.parakeet

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.zip.ZipInputStream
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
        onProgress: (String) -> Unit,
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
                    onProgress("Downloading speech model ${index + 1}/${ParakeetModelCatalog.streamingModelFiles.size}: $fileName")
                    val outputFile = File(tempDir, fileName)
                    outputFile.parentFile?.mkdirs()
                    downloadFile(
                        url = ParakeetModelCatalog.streamingModelDownloadUrl(fileName),
                        outputFile = outputFile,
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

    suspend fun importModelFromUri(uri: Uri): ParakeetImportResult {
        return withContext(Dispatchers.IO) {
            val sourceFileName = getDisplayName(uri)
                ?: return@withContext ParakeetImportResult.Failure("Could not determine the selected file name.")

            if (!ParakeetModelCatalog.isSupportedImportFile(sourceFileName)) {
                return@withContext ParakeetImportResult.UnsupportedFile(
                    "Select a ZIP export of ${ParakeetModelCatalog.streamingModel.sourceModel}."
                )
            }

            val spec = ParakeetModelCatalog.streamingModel
            val targetFile = getModelFile(spec)
            val targetDir = targetFile.parentFile
                ?: return@withContext ParakeetImportResult.Failure("Could not prepare model storage.")

            if (!targetDir.exists() && !targetDir.mkdirs()) {
                return@withContext ParakeetImportResult.Failure("Could not create the model directory.")
            }

            val tempDir = File(targetDir, "${targetFile.name}.tmp")

            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    unzipModel(input = ZipInputStream(input), targetDir = tempDir)
                } ?: return@withContext ParakeetImportResult.Failure("Could not open the selected file.")

                if (!File(tempDir, "genai_config.json").isFile) {
                    tempDir.deleteRecursively()
                    return@withContext ParakeetImportResult.Failure(
                        "The ZIP must contain genai_config.json at its top level."
                    )
                }

                if (targetFile.exists() && !targetFile.deleteRecursively()) {
                    return@withContext ParakeetImportResult.Failure("Could not replace the existing imported model.")
                }

                if (!tempDir.renameTo(targetFile)) {
                    return@withContext ParakeetImportResult.Failure("Could not finalize the imported model file.")
                }

                refresh()
                ParakeetImportResult.Success(spec)
            } catch (e: FileNotFoundException) {
                tempDir.deleteRecursively()
                ParakeetImportResult.Failure(
                    "Android could not read the selected file. Choose it through the system file picker."
                )
            } catch (e: SecurityException) {
                tempDir.deleteRecursively()
                ParakeetImportResult.Failure(
                    "Android did not grant access to the selected file. Select it again in the system file picker."
                )
            } catch (e: IOException) {
                tempDir.deleteRecursively()
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
                !file.exists() || file.deleteRecursively()
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

    private fun unzipModel(input: ZipInputStream, targetDir: File) {
        targetDir.deleteRecursively()
        if (!targetDir.mkdirs()) {
            throw IOException("Could not create temporary model directory.")
        }

        input.use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val relativeName = entry.name
                    .replace('\\', '/')
                    .trimStart('/')
                    .removePrefix("${ParakeetModelCatalog.streamingModel.storageFileName}/")
                    .removePrefix("nemotron-3.5-asr-streaming-0.6b-onnx-int4/")
                if (relativeName.isNotBlank()) {
                    val output = File(targetDir, relativeName)
                    val canonicalTarget = targetDir.canonicalFile
                    val canonicalOutput = output.canonicalFile
                    if (!canonicalOutput.path.startsWith(canonicalTarget.path)) {
                        throw IOException("ZIP contains an unsafe path: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        output.mkdirs()
                    } else {
                        output.parentFile?.mkdirs()
                        FileOutputStream(output).use { fileOutput ->
                            zip.copyTo(fileOutput)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun File.totalSizeBytes(): Long {
        if (isFile) return length()
        return walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
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

    private fun downloadFile(url: String, outputFile: File) {
        val connection = URL(url).openConnection().apply {
            connectTimeout = DOWNLOAD_TIMEOUT_MS
            readTimeout = DOWNLOAD_TIMEOUT_MS
            setRequestProperty("User-Agent", "VoxTranscribe")
        }
        connection.getInputStream().use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private companion object {
        const val DOWNLOAD_TIMEOUT_MS = 60_000
    }
}
