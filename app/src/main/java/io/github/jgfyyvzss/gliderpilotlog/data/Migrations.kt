package io.github.jgfyyvzss.gliderpilotlog.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Version 2 adds the airfield's default club and default launch type. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
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
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
