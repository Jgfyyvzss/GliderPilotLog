package io.github.jgfyyvzss.gliderpilotlog.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * A migration that first drops the partial unique indexes, which Room's post-migration validation would
 * otherwise reject as unknown. [LogbookDatabase.CreateCallback] recreates them when the database opens.
 */
fun logbookMigration(from: Int, to: Int, migrate: (SupportSQLiteDatabase) -> Unit): Migration =
    object : Migration(from, to) {
        override fun migrate(db: SupportSQLiteDatabase) {
            SchemaSql.dropPartialIndexes(db::execSQL)
            migrate(db)
        }
    }

/** Version 2 adds the airfield's default club and default launch type. */
val MIGRATION_1_2 = logbookMigration(1, 2) { db ->
    db.execSQL(
        "ALTER TABLE airfield ADD COLUMN default_club_id TEXT " +
            "REFERENCES club(id) ON UPDATE NO ACTION ON DELETE RESTRICT",
    )
    db.execSQL(
        "ALTER TABLE airfield ADD COLUMN default_launch_type_id TEXT " +
            "REFERENCES launch_type(id) ON UPDATE NO ACTION ON DELETE RESTRICT",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_airfield_default_club_id ON airfield(default_club_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_airfield_default_launch_type_id ON airfield(default_launch_type_id)")
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
