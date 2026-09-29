package com.paysync.gateway.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.paysync.gateway.data.DispatchLog

@Database(
    entities = [PendingVerify::class, DispatchQueueItem::class, CapturedSms::class, DispatchLog::class, ProcessedSms::class],
    version = 4,
    // Schema JSONs are committed under app/schemas/ — the baseline for any
    // future migration (fallbackToDestructiveMigration is NOT used; a future
    // schema change must ship an explicit Migration + MigrationTestHelper test).
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingVerifyDao(): PendingVerifyDao
    abstract fun dispatchQueueDao(): DispatchQueueDao
    abstract fun capturedSmsDao(): CapturedSmsDao
    abstract fun dispatchLogDao(): DispatchLogDao
    abstract fun processedSmsDao(): ProcessedSmsDao

    companion object {
        /** First schema version ever published (v1.0.0). */
        private const val FIRST_PUBLIC_SCHEMA_VERSION = 4
        private const val DB_NAME = "paysync.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }
        }

        private fun build(appContext: Context): AppDatabase {
            dropPreReleaseDatabaseIfAny(appContext)
            return Room.databaseBuilder(appContext, AppDatabase::class.java, DB_NAME)
                // R5: user data (consumed-SMS ledger, outbox) must never be
                // wiped silently — a future schema change REQUIRES an explicit
                // Migration; a missing one must crash loudly in development,
                // not erase payments. Only downgrades fall back.
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
        }

        /**
         * Pre-release development builds (before the public v1.0.0) shipped
         * database versions 1-3 without exported schemas or migrations. No
         * public install can have one — v1.0.0 was the first public release
         * and shipped schema 4 — so a legacy dev database is dropped once,
         * explicitly and version-gated, instead of crashing the app on every
         * launch with "migration 3 to 4 not found". Public v4 databases are
         * untouched, and any FUTURE missing migration still fails loudly.
         */
        private fun dropPreReleaseDatabaseIfAny(appContext: Context) {
            try {
                val file = appContext.getDatabasePath(DB_NAME)
                if (!file.exists()) return
                val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                )
                val onDiskVersion = db.version
                db.close()
                if (onDiskVersion in 1 until FIRST_PUBLIC_SCHEMA_VERSION) {
                    appContext.deleteDatabase(DB_NAME)
                }
            } catch (_: Exception) {
                // Unreadable file: let Room surface the real error instead.
            }
        }
    }
}
