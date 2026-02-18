package com.example.voxtranscribe.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.voxtranscribe.MainActivity
import com.example.voxtranscribe.domain.TranscriptionRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TranscriptionService : LifecycleService() {

    @Inject
    lateinit var repository: TranscriptionRepository

    @Inject
    lateinit var notesRepository: NotesRepository

    private val TAG = "TranscriptionService"

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
        val notification = createNotification("Vox Transcribe is listening...")
        startForeground(NOTIFICATION_ID, notification)
        
        lifecycleScope.launch {
            Log.d(TAG, "Launching collector for noteId: $noteId")
            // Collect and save to DB
            launch {
                repository.transcriptionState.collect { entry ->
                    Log.d(TAG, "Received entry: ${entry.text}, isFinal: ${entry.isFinal}")
                    if (entry.isFinal) {
                        try {
                            notesRepository.insertSegment(noteId, entry.text, true)
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
            repository.stopListening()
            Log.d(TAG, "stopListening returned. Stopping service.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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
