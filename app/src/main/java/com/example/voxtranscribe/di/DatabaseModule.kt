package com.example.voxtranscribe.di

import android.content.Context
import com.example.voxtranscribe.data.db.NotesDao
import com.example.voxtranscribe.data.db.VoxDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VoxDatabase {
        return VoxDatabase.getDatabase(context)
    }

    @Provides
    fun provideNotesDao(database: VoxDatabase): NotesDao {
        return database.notesDao()
    }
}
