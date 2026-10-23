package io.github.jgfyyvzss.gliderpilotlog.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Whole-table reads and writes for backup, restore and merge. Blocking, for use inside one transaction. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM launch_type") fun allLaunchTypes(): List<LaunchTypeEntity>
    @Query("SELECT * FROM club") fun allClubs(): List<ClubEntity>
    @Query("SELECT * FROM airfield") fun allAirfields(): List<AirfieldEntity>
    @Query("SELECT * FROM airfield_club") fun allAirfieldClubs(): List<AirfieldClubEntity>
    @Query("SELECT * FROM glider") fun allGliders(): List<GliderEntity>
    @Query("SELECT * FROM flight") fun allFlights(): List<FlightEntity>
    @Query("SELECT * FROM opening_balance") fun allOpeningBalances(): List<OpeningBalanceEntity>
    @Query("SELECT * FROM import_batch") fun allImportBatches(): List<ImportBatchEntity>
    @Query("SELECT * FROM witness_signoff") fun allWitnessSignoffs(): List<WitnessSignoffEntity>

    /** Rows of any kind, live or deleted, that the user created. Seeded launch types and balances do not count. */
    @Query("SELECT (SELECT COUNT(*) FROM flight) + (SELECT COUNT(*) FROM glider) + (SELECT COUNT(*) FROM airfield) + (SELECT COUNT(*) FROM club)")
    fun userRecordCount(): Int

    /** Records changed after [since], for the backup schedule. Link changes bump the airfield's updated_at. */
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM flight WHERE updated_at > :since)
             + (SELECT COUNT(*) FROM glider WHERE updated_at > :since)
             + (SELECT COUNT(*) FROM airfield WHERE updated_at > :since)
             + (SELECT COUNT(*) FROM club WHERE updated_at > :since)
             + (SELECT COUNT(*) FROM launch_type WHERE updated_at > :since)
             + (SELECT COUNT(*) FROM opening_balance WHERE updated_at > :since)
             + (SELECT COUNT(*) FROM witness_signoff WHERE updated_at > :since)
        """,
    )
    fun changesSince(since: Long): Int

    @Query("DELETE FROM flight") fun deleteFlights()
    @Query("DELETE FROM airfield_club") fun deleteAirfieldClubs()
    @Query("DELETE FROM airfield") fun deleteAirfields()
    @Query("DELETE FROM glider") fun deleteGliders()
    @Query("DELETE FROM club") fun deleteClubs()
    @Query("DELETE FROM launch_type") fun deleteLaunchTypes()
    @Query("DELETE FROM opening_balance") fun deleteOpeningBalances()
    @Query("DELETE FROM import_batch") fun deleteImportBatches()
    @Query("DELETE FROM witness_signoff") fun deleteWitnessSignoffs()

    /** Deletes, as tombstones without photos, live sign-offs that no live flight refers to, as WitnessDao does. */
    @Query(
        "UPDATE witness_signoff SET photo = NULL, updated_at = :time, deleted_at = :time WHERE deleted_at IS NULL AND id NOT IN " +
            "(SELECT witness_signoff_id FROM flight WHERE deleted_at IS NULL AND witness_signoff_id IS NOT NULL)",
    )
    fun deleteUnreferencedSignoffs(time: Long): Int

    @Insert fun insertLaunchTypes(rows: List<LaunchTypeEntity>)
    @Insert fun insertClubs(rows: List<ClubEntity>)
    @Insert fun insertAirfields(rows: List<AirfieldEntity>)
    @Insert fun insertAirfieldClubs(rows: List<AirfieldClubEntity>)
    @Insert fun insertGliders(rows: List<GliderEntity>)
    @Insert fun insertFlights(rows: List<FlightEntity>)
    @Insert fun insertOpeningBalances(rows: List<OpeningBalanceEntity>)
    @Insert fun insertImportBatches(rows: List<ImportBatchEntity>)
    @Insert fun insertWitnessSignoffs(rows: List<WitnessSignoffEntity>)
}
