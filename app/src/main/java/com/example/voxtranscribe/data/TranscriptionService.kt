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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val segmentSaveMutex = Mutex()
    private val finalizedTranscriptParts = mutableListOf<String>()

    companion object {
        private const val CHANNEL_ID = "transcription_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
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
            ACTION_PAUSE -> {
                Log.d(TAG, "Pausing service")
                pauseForegroundService()
            }
            ACTION_RESUME -> {
                Log.d(TAG, "Resuming service")
                resumeForegroundService()
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
                        saveFinalSegment(noteId, entry.text, "collector")
                    }
                }
            }
            
            Log.d(TAG, "Starting repository listening")
            repository.startListening()
        }
    }

    private fun pauseForegroundService() {
        lifecycleScope.launch {
            updateNotification("Paused")
            val finalizedCountBeforeStop = finalizedPartCount()
            repository.stopListening()
            persistVisibleTranscriptFallback(finalizedCountBeforeStop)
        }
    }

    private fun resumeForegroundService() {
        val noteId = activeNoteId ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Cannot resume transcription without RECORD_AUDIO permission")
            return
        }

        updateNotification("Vox Transcribe is listening...")
        lifecycleScope.launch {
            Log.d(TAG, "Resuming repository listening for noteId: $noteId")
            repository.startListening()
        }
    }

    private fun stopForegroundService() {
        lifecycleScope.launch {
            Log.d(TAG, "Calling stopListening...")
            updateNotification("Finalizing note...")
            val finalizedCountBeforeStop = finalizedPartCount()
            repository.stopListening()
            persistVisibleTranscriptFallback(finalizedCountBeforeStop)
            generateTitleForActiveNote()
            segmentCollectorJob?.cancel()
            segmentCollectorJob = null
            Log.d(TAG, "stopListening returned. Stopping service.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun persistVisibleTranscriptFallback(finalizedCountBeforeStop: Int) {
        val noteId = activeNoteId ?: return
        if (finalizedPartCount() != finalizedCountBeforeStop) {
            Log.d(TAG, "Skipping visible transcript fallback because final collector saved text")
            return
        }

        val text = repository.partialText.value.trim()
        if (text.isBlank()) {
            Log.w(TAG, "No finalized or visible transcript available for note $noteId")
            return
        }

        saveFinalSegment(noteId, text, "visible fallback")
    }

    private suspend fun finalizedPartCount(): Int = segmentSaveMutex.withLock {
        finalizedTranscriptParts.size
    }

    private suspend fun finalizedTranscriptSnapshot(): List<String> = segmentSaveMutex.withLock {
        finalizedTranscriptParts.toList()
    }

    private suspend fun saveFinalSegment(noteId: Long, text: String, source: String): Boolean {
        val trimmedText = text.trim()
        val normalizedText = normalizeTranscriptText(trimmedText)
        if (normalizedText.isBlank()) return false

        return segmentSaveMutex.withLock {
            val savedCombined = normalizeTranscriptText(finalizedTranscriptParts.joinToString(" "))
            val isDuplicate = finalizedTranscriptParts.any {
                normalizeTranscriptText(it) == normalizedText
            } || savedCombined == normalizedText

            if (isDuplicate) {
                Log.d(TAG, "Skipping duplicate final segment from $source for note $noteId")
                return@withLock false
            }

            try {
                withContext(NonCancellable) {
                    notesRepository.insertSegment(noteId, trimmedText, true)
                }
                finalizedTranscriptParts += trimmedText
                Log.d(TAG, "Saved segment from $source to DB for note $noteId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert segment from $source", e)
                false
            }
        }
    }

    private fun normalizeTranscriptText(text: String): String {
        return text.trim().replace(Regex("\\s+"), " ")
    }

    private suspend fun generateTitleForActiveNote() {
        val noteId = activeNoteId ?: return
        val transcript = finalizedTranscriptSnapshot()
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
