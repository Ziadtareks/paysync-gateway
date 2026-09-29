package com.paysync.gateway

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import com.paysync.gateway.data.db.AppDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase-6d: Room schema integrity against the committed schema JSON
 * (app/schemas/.../4.json).
 *
 * This release ships NO schema change (still version 4 — the consumed-SMS
 * fingerprint table existed since v4), so there is no migration to run.
 * What IS proven here:
 *  1. the committed 4.json matches the runtime schema exactly (Room validates
 *     the identity when opening via the helper), and
 *  2. rows written into a v4-schema database are readable afterwards —
 *     i.e. the destructive-fallback removal did not change durability.
 * The FIRST future schema change must add a migration step to this test
 * (createDatabase(v4) -> runMigrationsAndValidate(v5, MIGRATION_4_5)).
 */
@RunWith(AndroidJUnit4::class)
class RoomSchemaIntegrityTest {

    private val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun schema4_matchesRuntimeSchema_andDataSurvivesReopen() {
        val dbName = "paysync-schema-integrity-test.db"

        // Create a database straight from the committed v4 schema JSON…
        helper.createDatabase(dbName, 4).use { db ->
            db.execSQL(
                """INSERT INTO pending_verify
                   (verifyId, expectedAmount, provider, referenceIdHint, createdAt, timeoutMs, status)
                   VALUES ('v-schema-1', 150.0, 'VF-Cash', '023650505952', 1000, 120000, 'PENDING')"""
            )
            db.execSQL(
                """INSERT INTO processed_sms (hash, receivedAt) VALUES ('deadbeef', 1000)"""
            )
        }

        // …reopen it under the RUNTIME schema (same version, no migrations)
        // — Room validates the schema identity; a mismatch fails loudly.
        val reopened = helper.runMigrationsAndValidate(dbName, 4, true)

        reopened.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM pending_verify")).use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        reopened.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM processed_sms")).use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        reopened.close()
    }

    @Test
    fun runtimeDatabaseBuilder_opensWithoutDestructiveFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "paysync-runtime-builder-test.db"
        context.deleteDatabase(dbName)
        var db: AppDatabase? = null
        try {
            db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
                .fallbackToDestructiveMigrationOnDowngrade() // same policy as production
                .build()
            kotlinx.coroutines.runBlocking {
                db!!.processedSmsDao().insertIfAbsent(
                    com.paysync.gateway.data.db.ProcessedSms(hash = "cafebabe")
                )
                assertTrue(db!!.processedSmsDao().exists("cafebabe"))
            }
        } finally {
            db?.close()
            context.deleteDatabase(dbName)
        }
    }
}
