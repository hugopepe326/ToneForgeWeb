package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TonePatchEntity::class], version = 2, exportSchema = false)
abstract class ToneDatabase : RoomDatabase() {
    abstract fun tonePatchDao(): TonePatchDao

    companion object {
        @Volatile
        private var INSTANCE: ToneDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tone_patches ADD COLUMN instrumentType TEXT NOT NULL DEFAULT 'GUITAR'"
                )
            }
        }

        fun getInstance(context: Context): ToneDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ToneDatabase::class.java,
                    "toneforge_patches.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
