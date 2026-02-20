package com.example.voxtranscribe.di

import android.content.Context
import com.example.voxtranscribe.data.AndroidSpeechRecognizerImpl
import com.example.voxtranscribe.data.NotesRepository
import com.example.voxtranscribe.data.VoxtralTranscriptionRepository
import com.example.voxtranscribe.data.ai.AiRepository
import com.example.voxtranscribe.data.ai.MockAiRepository
import com.example.voxtranscribe.data.ai.MediaPipeAiRepository
import com.example.voxtranscribe.data.db.NotesDao
import com.example.voxtranscribe.domain.TranscriptionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideTranscriptionRepository(
        dynamicRepo: com.example.voxtranscribe.data.DynamicTranscriptionRepository
    ): TranscriptionRepository {
        return dynamicRepo
    }

    @Provides
    @Singleton
    fun provideNotesRepository(notesDao: NotesDao): NotesRepository {
        return NotesRepository(notesDao)
    }

    @Provides
    @Singleton
    fun provideAiRepository(impl: MediaPipeAiRepository): AiRepository {
        return impl
    }
}
