package io.github.jgfyyvzss.gliderpilotlog.data

import io.github.jgfyyvzss.gliderpilotlog.core.Msg
import io.github.jgfyyvzss.gliderpilotlog.core.MsgKey
import io.github.jgfyyvzss.gliderpilotlog.core.msg
import io.github.jgfyyvzss.gliderpilotlog.core.backup.BackupAirfield
import io.github.jgfyyvzss.gliderpilotlog.core.backup.BackupClub
import io.github.jgfyyvzss.gliderpilotlog.core.backup.BackupCodec
import io.github.jgfyyvzss.gliderpilotlog.core.backup.BackupFile
import io.github.jgfyyvzss.gliderpilotlog.core.backup.BackupFlight
import io.github.jgfyyvzss.gliderpilotlog.core.backup.BackupGlider
import io.github.jgfyyvzss.gliderpilotlog.core.backup.BackupImportBatch
import io.github.jgfyyvzss.gliderpilotlog.core.backup.BackupLaunchType
import io.github.jgfyyvzss.gliderpilotlog.core.backup.BackupMerge
import io.github.jgfyyvzss.gliderpilotlog.core.backup.BackupOpeningBalance
import io.github.jgfyyvzss.gliderpilotlog.core.backup.BackupSchedule
import io.github.jgfyyvzss.gliderpilotlog.core.backup.BackupTime
import io.github.jgfyyvzss.gliderpilotlog.core.backup.BackupValidation
import io.github.jgfyyvzss.gliderpilotlog.core.backup.DecodeResult
import io.github.jgfyyvzss.gliderpilotlog.core.backup.MergeSummary
import java.util.concurrent.Callable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** What a backup file holds, shown before Restore or Merge commits. */
data class BackupContents(val flights: Int, val gliders: Int, val airfields: Int, val clubs: Int, val exportedAt: String)

/** A checked file, ready to commit. */
sealed interface Prepared {
    data class Restore(val file: BackupFile, val contents: BackupContents) : Prepared
    data class Merge(val merged: BackupFile, val contents: BackupContents, val summary: MergeSummary) : Prepared
    /** Nothing is written. [problems] lists every reason, such as a log number collision. */
    data class Refused(val problems: List<Msg>) : Prepared
}

/** Export, Restore and Merge of the whole logbook as one JSON backup file. */
class BackupService(
    private val db: LogbookDatabase,
    private val settings: SettingsRepository,
    private val appVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.backupDao()

    suspend fun isEmpty(): Boolean = withContext(Dispatchers.IO) { dao.userRecordCount() == 0 }

    suspend fun changesSinceLastBackup(): Int {
        val since = settings.settings.first().lastBackupAt ?: -1L
        return withContext(Dispatchers.IO) { dao.changesSince(since) }
    }

    /** Whether the automatic schedule calls for a backup now. Never true without a backup folder. */
    suspend fun isAutoBackupDue(): Boolean {
        val s = settings.settings.first()
        if (s.backupFolderUri == null) return false
        return BackupSchedule.isDue(changesSinceLastBackup(), s.lastBackupAt, now(), s.backupAfterChanges, s.backupAfterDays)
    }

    /** The whole logbook as backup text, including archived records, tombstones and the backed-up settings. */
    suspend fun exportJson(): String = BackupCodec.encode(snapshot())

    suspend fun snapshot(): BackupFile {
        val s = settings.settings.first()
        return withContext(Dispatchers.IO) {
            db.runInTransaction(Callable {
                val links = dao.allAirfieldClubs().groupBy({ it.airfieldId }, { it.clubId })
                BackupFile(
                    exportedAt = BackupTime.toIso(now()),
                    appVersion = appVersion,
                    launchTypes = dao.allLaunchTypes().map { it.toBackup() },
                    clubs = dao.allClubs().map { it.toBackup() },
                    airfields = dao.allAirfields().map { it.toBackup(links[it.id].orEmpty()) },
                    gliders = dao.allGliders().map { it.toBackup() },
                    flights = dao.allFlights().map { it.toBackup() },
                    openingBalances = dao.allOpeningBalances().map { it.toBackup() },
                    importBatches = dao.allImportBatches().map { it.toBackup() },
                    settings = s.toBackup(),
                )
            })
        }
    }

    private fun contents(f: BackupFile) = BackupContents(
        flights = f.flights.count { it.deletedAt == null },
        gliders = f.gliders.count { it.deletedAt == null },
        airfields = f.airfields.count { it.deletedAt == null },
        clubs = f.clubs.count { it.deletedAt == null },
        exportedAt = f.exportedAt,
    )

    /** Reads and checks a file for Restore. Restore needs an empty logbook. */
    suspend fun prepareRestore(text: String): Prepared {
        val file = when (val d = BackupCodec.decode(text)) {
            is DecodeResult.Refused -> return Prepared.Refused(listOf(d.message))
            is DecodeResult.Ok -> d.file
        }
        if (!isEmpty()) return Prepared.Refused(listOf(msg(MsgKey.BACKUP_NEEDS_EMPTY)))
        val problems = BackupValidation.validate(file)
        return if (problems.isEmpty()) Prepared.Restore(file, contents(file)) else Prepared.Refused(problems)
    }

    /** Reads a file, merges it with this phone's logbook in memory, and checks the result. */
    suspend fun prepareMerge(text: String): Prepared {
        val incoming = when (val d = BackupCodec.decode(text)) {
            is DecodeResult.Refused -> return Prepared.Refused(listOf(d.message))
            is DecodeResult.Ok -> d.file
        }
        BackupValidation.validate(incoming).takeIf { it.isNotEmpty() }?.let { return Prepared.Refused(it) }
        val result = BackupMerge.merge(snapshot(), incoming)
        val problems = BackupValidation.validate(result.merged)
        return if (problems.isEmpty()) Prepared.Merge(result.merged, contents(incoming), result.summary) else Prepared.Refused(problems)
    }

    /** Commits a prepared Restore: the logbook and every backed-up setting, including the pilot profile. */
    suspend fun restore(prepared: Prepared.Restore) {
        check(isEmpty()) { "Restore needs an empty logbook" }
        replaceAll(prepared.file)
        settings.applyBackup(prepared.file.settings)
    }

    /** Commits a prepared Merge. Settings and the pilot profile stay as they are. */
    suspend fun merge(prepared: Prepared.Merge) = replaceAll(prepared.merged)

    /**
     * Replaces every table with [file] in one transaction, in dependency order. The partial unique indexes are
     * dropped while rows go in and recreated at the end, which fails and rolls back if the result breaks them.
     */
    private suspend fun replaceAll(file: BackupFile): Unit = withContext(Dispatchers.IO) {
        db.runInTransaction(Runnable {
            val sql = db.openHelper.writableDatabase
            SchemaSql.dropPartialIndexes(sql::execSQL)
            dao.deleteFlights()
            dao.deleteAirfieldClubs()
            dao.deleteAirfields()
            dao.deleteGliders()
            dao.deleteClubs()
            dao.deleteLaunchTypes()
            dao.deleteOpeningBalances()
            dao.deleteImportBatches()

            dao.insertLaunchTypes(file.launchTypes.map { it.toEntity() })
            dao.insertClubs(file.clubs.map { it.toEntity() })
            dao.insertAirfields(file.airfields.map { it.toEntity() })
            dao.insertAirfieldClubs(file.airfields.flatMap { a -> a.clubIds.distinct().map { AirfieldClubEntity(a.id, it) } })
            dao.insertGliders(file.gliders.map { it.toEntity() })
            dao.insertImportBatches(file.importBatches.map { it.toEntity() })
            dao.insertFlights(file.flights.map { it.toEntity() })
            dao.insertOpeningBalances(file.openingBalances.map { it.toEntity() })
            SchemaSql.partialUniqueIndexes.forEach(sql::execSQL)
        })
    }
}

// Mapping between rows and the file. Times are epoch milliseconds in rows and ISO strings in the file.

private fun Long.iso() = BackupTime.toIso(this)
private fun String.ms() = BackupTime.fromIso(this) ?: error("unreadable time $this")

private fun LaunchTypeEntity.toBackup() = BackupLaunchType(id, name, nameKey, sortOrder, archived, updatedAt.iso(), deletedAt?.iso())
private fun BackupLaunchType.toEntity() = LaunchTypeEntity(id, name, nameKey, sortOrder, archived, updatedAt.ms(), deletedAt?.ms())

private fun ClubEntity.toBackup() = BackupClub(id, name, nameKey, updatedAt.iso(), deletedAt?.iso())
private fun BackupClub.toEntity() = ClubEntity(id, name, nameKey, updatedAt.ms(), deletedAt?.ms())

private fun AirfieldEntity.toBackup(clubIds: List<String>) = BackupAirfield(
    id = id, name = name, nameKey = nameKey, clubIds = clubIds, latitude = latitude, longitude = longitude,
    elevationM = elevationM, matchRadiusM = matchRadiusM, defaultClubId = defaultClubId, defaultLaunchTypeId = defaultLaunchTypeId,
    incomplete = incomplete, archived = archived, importBatchId = importBatchId, updatedAt = updatedAt.iso(), deletedAt = deletedAt?.iso(),
)
private fun BackupAirfield.toEntity() = AirfieldEntity(
    id = id, name = name, nameKey = nameKey, latitude = latitude, longitude = longitude, elevationM = elevationM,
    matchRadiusM = matchRadiusM, defaultClubId = defaultClubId, defaultLaunchTypeId = defaultLaunchTypeId,
    incomplete = incomplete, archived = archived, importBatchId = importBatchId, updatedAt = updatedAt.ms(), deletedAt = deletedAt?.ms(),
)

private fun GliderEntity.toBackup() = BackupGlider(
    id, registration, registrationKey, type, weglideAircraftId, logbookTotalMinutes, logbookTotalFlights, logbookTotalDate,
    incomplete, archived, importBatchId, updatedAt.iso(), deletedAt?.iso(),
)
private fun BackupGlider.toEntity() = GliderEntity(
    id, registration, registrationKey, type, weglideAircraftId, logbookTotalMinutes, logbookTotalFlights, logbookTotalDate,
    incomplete, archived, importBatchId, updatedAt.ms(), deletedAt?.ms(),
)

private fun FlightEntity.toBackup() = BackupFlight(
    id, logNo, date, durationMinutes, gliderId, airfieldId, clubId, launchTypeId, role, picName, launchTime, landingTime,
    landingAirfieldId, landingPlace, notes, source, importBatchId, updatedAt.iso(), deletedAt?.iso(),
)
private fun BackupFlight.toEntity() = FlightEntity(
    id, logNo, date, durationMinutes, gliderId, airfieldId, clubId, launchTypeId, role, picName, launchTime, landingTime,
    landingAirfieldId, landingPlace, notes, source, importBatchId, updatedAt.ms(), deletedAt?.ms(),
)

private fun OpeningBalanceEntity.toBackup() = BackupOpeningBalance(role, asOfDate, minutes, flights, updatedAt.iso())
private fun BackupOpeningBalance.toEntity() = OpeningBalanceEntity(role, asOfDate, minutes, flights, updatedAt.ms())

private fun ImportBatchEntity.toBackup() = BackupImportBatch(id, createdAt.iso(), fileName, flightCount)
private fun BackupImportBatch.toEntity() = ImportBatchEntity(id, createdAt.ms(), fileName, flightCount)
