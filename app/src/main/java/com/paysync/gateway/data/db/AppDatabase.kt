package com.paysync.gateway.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.paysync.gateway.data.DispatchLog

@Database(
    entities = [PendingVerify::class, DispatchQueueItem::class, CapturedSms::class, DispatchLog::class, ProcessedSms::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingVerifyDao(): PendingVerifyDao
    abstract fun dispatchQueueDao(): DispatchQueueDao
    abstract fun capturedSmsDao(): CapturedSmsDao
    abstract fun dispatchLogDao(): DispatchLogDao
    abstract fun processedSmsDao(): ProcessedSmsDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "paysync.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
        }
    }
}
