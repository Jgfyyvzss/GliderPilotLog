package io.github.jgfyyvzss.gliderpilotlog.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jgfyyvzss.gliderpilotlog.core.Keys
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every released schema version must migrate to the current one with data intact. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LogbookDatabase::class.java)

    private val aero = Keys.seededLaunchTypeId("Aero")

    /** A version 1 database as the app created it, including the partial indexes, triggers and seeds. */
    private fun createVersion1() {
        helper.createDatabase(dbName, 1).apply {
            SchemaSql.createExtras(::execSQL)
            SchemaSql.seed(::execSQL)
            execSQL("INSERT INTO club (id, name, name_key, updated_at) VALUES ('c1', 'MBGC', 'mbgc', 1)")
            execSQL(
                "INSERT INTO airfield (id, name, name_key, latitude, longitude, incomplete, archived, updated_at) " +
                    "VALUES ('a1', 'YMBT', 'ymbt', -36.73, 147.17, 0, 0, 1)",
            )
            execSQL("INSERT INTO airfield_club (airfield_id, club_id) VALUES ('a1', 'c1')")
            execSQL(
                "INSERT INTO glider (id, registration, registration_key, type, incomplete, archived, updated_at) " +
                    "VALUES ('g1', 'FQM', 'fqm', 'Hornet', 0, 0, 1)",
            )
            execSQL(
                "INSERT INTO flight (id, log_no, date, duration_minutes, glider_id, airfield_id, club_id, launch_type_id, " +
                    "role, pic_name, source, updated_at) VALUES ('f1', 340, '2026-10-17', 271, 'g1', 'a1', 'c1', '$aero', " +
                    "'PIC', 'Pat', 'MANUAL', 1)",
            )
            close()
        }
    }

    @Test fun migrate1To2KeepsDataAndPassesValidation() {
        createVersion1()
        val db = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)

        db.query("SELECT name, default_club_id, default_launch_type_id FROM airfield WHERE id = 'a1'").use {
            it.moveToFirst()
            assertEquals("YMBT", it.getString(0))
            assertNull(it.getString(1))
            assertNull(it.getString(2))
        }
        db.query("SELECT log_no, duration_minutes FROM flight WHERE id = 'f1'").use {
            it.moveToFirst()
            assertEquals(340, it.getInt(0))
            assertEquals(271, it.getInt(1))
        }
        // The triggers survive; the partial indexes are dropped for validation and recreated on open.
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'flight_check_%'").use {
            it.moveToFirst()
            assertEquals(SchemaSql.triggers.size, it.getInt(0))
        }
        db.close()
    }

    @Test fun partialIndexesAreRecreatedWhenRoomOpensTheMigratedDatabase() = runTest {
        createVersion1()
        helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2).close()
        val room = openWithRoom()
        try {
            val db = room.openHelper.writableDatabase
            db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name LIKE 'live_%'").use {
                it.moveToFirst()
                assertEquals(SchemaSql.partialIndexNames.size, it.getInt(0))
            }
            try {
                db.execSQL(
                    "INSERT INTO glider (id, registration, registration_key, type, incomplete, archived, updated_at) " +
                        "VALUES ('g2', 'fqm', 'fqm', '', 1, 0, 1)",
                )
                fail("expected the recreated unique index to reject a duplicate registration")
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
            }
        } finally {
            room.close()
        }
    }

    private fun openWithRoom(): LogbookDatabase =
        Room.databaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, LogbookDatabase::class.java, dbName)
            .addMigrations(*ALL_MIGRATIONS)
            .addCallback(LogbookDatabase.CreateCallback)
            .build()

    @Test fun migratedDatabaseOpensWithRoomAndEnforcesNewForeignKeys() = runTest {
        createVersion1()
        helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2).close()

        val room = openWithRoom()
        try {
            val repo = LogbookRepository(room)
            val saved = repo.saveAirfield(
                "a1", "YMBT", -36.73, 147.17, null, null, listOf("c1"), archived = false,
                defaultClubId = "c1", defaultLaunchTypeId = Keys.seededLaunchTypeId("Winch"),
            )
            assertEquals(SaveResult.Saved("a1"), saved)
            val a = repo.airfield("a1")!!
            assertEquals("c1", a.defaultClubId)
            assertEquals(Keys.seededLaunchTypeId("Winch"), a.defaultLaunchTypeId)
            assertEquals(1, room.flightDao().liveHolderOf(340)?.let { 1 } ?: 0)

            try {
                room.openHelper.writableDatabase.execSQL("UPDATE airfield SET default_launch_type_id = 'missing' WHERE id = 'a1'")
                fail("expected a foreign key failure")
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
            }
        } finally {
            room.close()
        }
    }
}
