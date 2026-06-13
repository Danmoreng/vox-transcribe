package com.example.voxtranscribe.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Note::class, TranscriptSegment::class], version = 3, exportSchema = false)
abstract class VoxDatabase : RoomDatabase() {
    abstract fun notesDao(): NotesDao

    companion object {
        @Volatile
        private var INSTANCE: VoxDatabase? = null

        fun getDatabase(context: Context): VoxDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VoxDatabase::class.java,
                    "vox_database"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN cleanedTranscript TEXT")
                db.execSQL("ALTER TABLE notes ADD COLUMN aiStatus TEXT NOT NULL DEFAULT 'idle'")
                db.execSQL("ALTER TABLE notes ADD COLUMN aiProgress REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN aiStatusMessage TEXT")
            }
        }
    }
}
