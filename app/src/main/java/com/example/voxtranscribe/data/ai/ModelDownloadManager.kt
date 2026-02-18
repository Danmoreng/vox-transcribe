package com.example.voxtranscribe.data.ai

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed class DownloadState {
    object Idle : DownloadState()
    object Downloading : DownloadState()
    object Completed : DownloadState()
    data class Error(val message: String) : DownloadState()
}

@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "ModelDownloadManager"
    private val modelName = "gemma3-1b-it-int4.litertlm"
    private val modelUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm"
    private val modelFile = File(context.filesDir, modelName)

    private val _downloadState = MutableStateFlow<DownloadState>(
        if (modelFile.exists()) DownloadState.Completed else DownloadState.Idle
    )
    val downloadState: StateFlow<DownloadState> = _downloadState

    fun isModelAvailable(): Boolean = modelFile.exists()

    fun downloadModel() {
        if (isModelAvailable()) {
            _downloadState.value = DownloadState.Completed
            return
        }

        try {
            Log.d(TAG, "Starting download for $modelName")
            _downloadState.value = DownloadState.Downloading

            val request = DownloadManager.Request(Uri.parse(modelUrl))
                .setTitle("Downloading AI Model")
                .setDescription("Gemma 3n for Vox Transcribe")
                .addRequestHeader("Authorization", "Bearer REPLACE_WITH_YOUR_TOKEN")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, modelName)

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)

            val onComplete = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (id == downloadId) {
                        Log.d(TAG, "Download completed event received for ID: $id")
                        val query = DownloadManager.Query().setFilterById(downloadId)
                        val cursor = downloadManager.query(query)
                        if (cursor != null && cursor.moveToFirst()) {
                            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val status = cursor.getInt(statusIndex)
                            
                            if (DownloadManager.STATUS_SUCCESSFUL == status) {
                                val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                                val localUriString = cursor.getString(uriIndex)
                                Log.d(TAG, "Download successful. Local URI: $localUriString")
                                
                                try {
                                    val sourceUri = Uri.parse(localUriString)
                                    context.contentResolver.openInputStream(sourceUri)?.use { input ->
                                        modelFile.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    Log.d(TAG, "File copied to internal storage: ${modelFile.absolutePath}")
                                    _downloadState.value = DownloadState.Completed
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to copy model file", e)
                                    _downloadState.value = DownloadState.Error("Copy failed: ${e.message}")
                                }
                            } else {
                                val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                val reason = cursor.getInt(reasonIndex)
                                Log.e(TAG, "Download failed with status $status and reason $reason")
                                _downloadState.value = DownloadState.Error("Download failed (code $reason)")
                            }
                            cursor.close()
                        } else {
                            Log.e(TAG, "Cursor is null or empty")
                            _downloadState.value = DownloadState.Error("Could not query download status")
                        }
                        try {
                            context.unregisterReceiver(this)
                        } catch (e: Exception) {
                            // Already unregistered or other issue
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    onComplete, 
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(
                    onComplete, 
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start download", e)
            _downloadState.value = DownloadState.Error("Download start failed: ${e.message}")
        }
    }
}
