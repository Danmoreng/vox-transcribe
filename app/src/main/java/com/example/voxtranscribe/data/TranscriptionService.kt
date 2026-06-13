package com.example.voxtranscribe.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.voxtranscribe.MainActivity
import com.example.voxtranscribe.data.ai.AiRepository
import com.example.voxtranscribe.domain.TranscriptionRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class TranscriptionService : LifecycleService() {

    @Inject
    lateinit var repository: TranscriptionRepository

    @Inject
    lateinit var notesRepository: NotesRepository

    @Inject
    lateinit var aiRepository: AiRepository

    private val TAG = "TranscriptionService"
    private var activeNoteId: Long? = null
    private var segmentCollectorJob: Job? = null
    private val finalizedTranscriptParts = mutableListOf<String>()

    companion object {
        private const val CHANNEL_ID = "transcription_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_NOTE_ID = "NOTE_ID"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
                Log.d(TAG, "Starting service for noteId: $noteId")
                if (noteId != -1L) {
                    startForegroundService(noteId)
                } else {
                    Log.e(TAG, "Invalid noteId received")
                }
            }
            ACTION_STOP -> {
                Log.d(TAG, "Stopping service")
                stopForegroundService()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundService(noteId: Long) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Cannot start transcription without RECORD_AUDIO permission")
            stopSelf()
            return
        }

        activeNoteId = noteId
        finalizedTranscriptParts.clear()
        val notification = createNotification("Vox Transcribe is listening...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        lifecycleScope.launch {
            Log.d(TAG, "Launching collector for noteId: $noteId")
            // Collect and save to DB
            segmentCollectorJob?.cancel()
            segmentCollectorJob = launch {
                repository.transcriptionState.collect { entry ->
                    Log.d(TAG, "Received entry: ${entry.text}, isFinal: ${entry.isFinal}")
                    if (entry.isFinal) {
                        if (finalizedTranscriptParts.any { it.trim() == entry.text.trim() }) {
                            Log.d(TAG, "Skipping duplicate final segment for note $noteId")
                            return@collect
                        }
                        finalizedTranscriptParts += entry.text
                        try {
                            // Prevent cancellation during save
                            withContext(NonCancellable) {
                                notesRepository.insertSegment(noteId, entry.text, true)
                            }
                            Log.d(TAG, "Saved segment to DB for note $noteId")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to insert segment", e)
                        }
                    }
                }
            }
            
            Log.d(TAG, "Starting repository listening")
            repository.startListening()
        }
    }

    private fun stopForegroundService() {
        lifecycleScope.launch {
            Log.d(TAG, "Calling stopListening...")
            updateNotification("Finalizing note...")
            repository.stopListening()
            persistVisibleTranscriptFallback()
            generateTitleForActiveNote()
            segmentCollectorJob?.cancel()
            segmentCollectorJob = null
            Log.d(TAG, "stopListening returned. Stopping service.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun persistVisibleTranscriptFallback() {
        val noteId = activeNoteId ?: return
        if (finalizedTranscriptParts.isNotEmpty()) {
            return
        }

        val text = repository.partialText.value.trim()
        if (text.isBlank()) {
            Log.w(TAG, "No finalized or visible transcript available for note $noteId")
            return
        }
        if (finalizedTranscriptParts.any { it.trim() == text }) {
            return
        }

        try {
            withContext(NonCancellable) {
                notesRepository.insertSegment(noteId, text, true)
            }
            finalizedTranscriptParts += text
            Log.d(TAG, "Saved visible transcript fallback to DB for note $noteId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save visible transcript fallback", e)
        }
    }

    private suspend fun generateTitleForActiveNote() {
        val noteId = activeNoteId ?: return
        val transcript = finalizedTranscriptParts
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n")
            .trim()
        if (transcript.isBlank()) {
            activeNoteId = null
            finalizedTranscriptParts.clear()
            return
        }

        try {
            Log.d(TAG, "Generating automatic title for noteId: $noteId")
            updateNotification("Title generation...")
            val title = aiRepository.generateTitle(transcript)
            withContext(NonCancellable) {
                notesRepository.updateNoteTitle(noteId, title)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Automatic title generation failed for noteId: $noteId", e)
        } finally {
            activeNoteId = null
            finalizedTranscriptParts.clear()
        }
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vox Transcribe Recording")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.presence_audio_busy) // Using system icon for now
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Transcription Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
