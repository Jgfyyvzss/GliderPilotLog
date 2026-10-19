package io.github.jgfyyvzss.gliderpilotlog.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        GliderEntity::class,
        AirfieldEntity::class,
        ClubEntity::class,
        AirfieldClubEntity::class,
        LaunchTypeEntity::class,
        OpeningBalanceEntity::class,
        ImportBatchEntity::class,
        FlightEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LogbookDatabase : RoomDatabase() {
    abstract fun gliderDao(): GliderDao
    abstract fun airfieldDao(): AirfieldDao
    abstract fun clubDao(): ClubDao
    abstract fun launchTypeDao(): LaunchTypeDao
    abstract fun flightDao(): FlightDao

    /** Creates the partial indexes and triggers, and seeds launch types and opening balances. */
    object CreateCallback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            SchemaSql.createExtras(db::execSQL)
            SchemaSql.seed(db::execSQL)
        }
    }

    companion object {
        const val FILE_NAME = "logbook.db"

        fun build(context: Context): LogbookDatabase =
            Room.databaseBuilder(context, LogbookDatabase::class.java, FILE_NAME)
                .addCallback(CreateCallback)
                .build()

        fun inMemory(context: Context): LogbookDatabase =
            Room.inMemoryDatabaseBuilder(context, LogbookDatabase::class.java)
                .addCallback(CreateCallback)
                .build()
    }
}
