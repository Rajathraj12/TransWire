package com.example.myapplication.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TransferEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transferDao(): TransferDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transfers ADD COLUMN fileUri TEXT")
                db.execSQL("ALTER TABLE transfers ADD COLUMN timestamp INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transfers ADD COLUMN chunkSize INTEGER NOT NULL DEFAULT 65536")
                db.execSQL("ALTER TABLE transfers ADD COLUMN totalChunks INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transfers ADD COLUMN verifiedRanges TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transfers ADD COLUMN tempFilePath TEXT")
                db.execSQL("ALTER TABLE transfers ADD COLUMN isIncoming INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE transfers ADD COLUMN errorMessage TEXT")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "transwire_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
