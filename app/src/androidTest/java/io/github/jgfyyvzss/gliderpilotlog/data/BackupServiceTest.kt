package io.github.jgfyyvzss.gliderpilotlog.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jgfyyvzss.gliderpilotlog.core.FlightDraft
import io.github.jgfyyvzss.gliderpilotlog.core.Keys
import io.github.jgfyyvzss.gliderpilotlog.core.Role
import io.github.jgfyyvzss.gliderpilotlog.core.backup.BackupCodec
import io.github.jgfyyvzss.gliderpilotlog.core.witness.SealStatus
import io.github.jgfyyvzss.gliderpilotlog.ui.text
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Step 3 done criteria: identical round trip, Restore into an empty database, and Merge collisions. */
@RunWith(AndroidJUnit4::class)
class BackupServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var source: LogbookDatabase
    private lateinit var target: LogbookDatabase
    private val settings = SettingsRepository(context)
    private var clock = 1_760_000_000_000L

    @Before fun open() {
        source = LogbookDatabase.inMemory(context)
        target = LogbookDatabase.inMemory(context)
    }

    @After fun close() {
        source.close()
        target.close()
    }

    private fun service(db: LogbookDatabase) = BackupService(db, settings, "test", now = { clock })
    private fun repo(db: LogbookDatabase) = LogbookRepository(db, now = { ++clock })

    private fun SaveResult.id() = (this as SaveResult.Saved).id

    /** A logbook with links, defaults, a deleted flight, an edited balance and an extra launch type. */
    private suspend fun seed(db: LogbookDatabase): LogbookRepository {
        val r = repo(db)
        val c1 = r.saveClub(null, "MBGC").id()
        val c2 = r.saveClub(null, "Bogong").id()
        val aero = r.saveLaunchType(null, "Bungee", archived = false).id()
        val a = r.saveAirfield(null, "YMBT", -36.7314, 147.17, 300, 1500, listOf(c1, c2), archived = false,
            defaultClubId = c2, defaultLaunchTypeId = aero).id()
        val g = r.saveGlider(null, "VH-FQM", "Hornet", archived = false).id()
        r.saveGlider(null, "B4", "", archived = true)
        fun draft(logNo: Int, minutes: String) = FlightDraft(
            logNoText = logNo.toString(), date = LocalDate.of(2026, 10, 17), durationText = minutes, gliderId = g,
            airfieldId = a, clubId = c1, launchTypeId = Keys.seededLaunchTypeId("Winch"), role = Role.PIC, picName = "Pat",
            launchTimeText = "1305", landingTimeText = "1736", notes = "Wave, \"quoted\" and ünïcode",
        )
        r.saveFlight(null, draft(1, "431"))
        val second = r.saveFlight(null, draft(2, "45").copy(launchTimeText = "", landingTimeText = "")).second!!
        r.saveFlight(null, draft(3, "100").copy(launchTimeText = "", landingTimeText = ""))
        r.deleteFlight(second)
        val balances = r.observeOpeningBalances().first().map {
            if (it.role == Role.PIC.name) it.copy(minutes = 6000, flights = 300, asOfDate = "2025-12-31") else it
        }
        r.saveOpeningBalances(balances)
        return r
    }

    @Test fun exportRestoreExportIsIdentical() = runTest {
        seed(source)
        val first = service(source).exportJson()

        val restore = service(target).prepareRestore(first)
        assertTrue("$restore", restore is Prepared.Restore)
        service(target).restore(restore as Prepared.Restore)
        val second = service(target).exportJson()

        assertEquals(BackupCodec.withoutExportTime(first), BackupCodec.withoutExportTime(second))
        assertEquals(2, (BackupCodec.decode(second) as io.github.jgfyyvzss.gliderpilotlog.core.backup.DecodeResult.Ok).file.flights.count { it.deletedAt == null })
    }

    /** Witnesses flights 1 and 3 of [seed] with a small JPEG-like photo. */
    private suspend fun witness(db: LogbookDatabase): ByteArray {
        val photo = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(3_000) { it.toByte() }
        val live = db.backupDao().allFlights().filter { it.deletedAt == null }.map { it.id }
        WitnessRepository(db, now = { ++clock }).sign(live, "Lee Instructor", "GFA 1", photo, consent = true)
        return photo
    }

    @Test fun witnessDataRoundTripsWithAndWithoutPhotos() = runTest {
        seed(source)
        val photo = witness(source)
        val full = service(source).exportJson()
        assertTrue(full.contains("\"contains_photos\": true"))

        val restore = service(target).prepareRestore(full) as Prepared.Restore
        assertEquals(2, restore.witness.verified)
        service(target).restore(restore)
        assertEquals(BackupCodec.withoutExportTime(full), BackupCodec.withoutExportTime(service(target).exportJson()))
        val statuses = WitnessRepository(target).statuses()
        assertEquals(listOf(SealStatus.WITNESSED, SealStatus.WITNESSED), statuses.values.toList())
        assertTrue(photo.contentEquals(target.backupDao().allWitnessSignoffs().single().photo))

        val bare = service(source).exportJson(includePhotos = false)
        assertTrue(bare.contains("\"contains_photos\": false"))
        assertTrue("names and seals stay", bare.contains("Lee Instructor") && bare.contains("\"witness_seal\": \""))
        // Merging the photo-less file into the full logbook keeps the photo.
        service(target).merge(service(target).prepareMerge(bare) as Prepared.Merge)
        assertTrue(photo.contentEquals(target.backupDao().allWitnessSignoffs().single().photo))
    }

    @Test fun restoredLogbookWorksNormally() = runTest {
        seed(source)
        val svc = service(target)
        svc.restore(svc.prepareRestore(service(source).exportJson()) as Prepared.Restore)
        assertEquals(4, repo(target).nextLogNo())
        // The partial unique indexes were recreated after the rows went in.
        target.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name LIKE 'live_%'").use {
            it.moveToFirst()
            assertEquals(SchemaSql.partialIndexNames.size, it.getInt(0))
        }
        assertTrue(svc.isEmpty().not())
    }
    @Test fun restoreNeedsAnEmptyLogbook() = runTest {
        seed(source)
        repo(target).saveGlider(null, "GZY", "Junior", archived = false)
        val p = service(target).prepareRestore(service(source).exportJson())
        assertTrue(p is Prepared.Refused && p.problems.single().text(context).startsWith("Restore needs an empty logbook"))
    }

    @Test fun refusesAFileFromANewerFormat() = runTest {
        val p = service(target).prepareRestore("""{"format":"glider-logbook-backup","format_version":99}""")
        assertTrue(p is Prepared.Refused && p.problems.single().text(context).contains("newer version"))
    }

    @Test fun mergeAddsNewerRecordsAndReportsCollisions() = runTest {
        seed(source)
        val backup = service(source).exportJson()

        // Merging into a copy that already has everything changes nothing.
        service(target).restore(service(target).prepareRestore(backup) as Prepared.Restore)
        val same = service(target).prepareMerge(backup) as Prepared.Merge
        assertEquals(0, same.summary.added)
        assertEquals(0, same.summary.updated)

        // A flight added on the other phone merges in.
        val r = repo(source)
        val g = r.observeGliders().first().first { it.registration == "VH-FQM" }.id
        val a = r.observeAirfields().first().single().id
        val c = r.clubsFor(a).first().id
        val wt = Keys.seededLaunchTypeId("Winch")
        r.saveFlight(null, FlightDraft(logNoText = "4", date = LocalDate.of(2026, 10, 19), durationText = "50",
            gliderId = g, airfieldId = a, clubId = c, launchTypeId = wt, role = Role.STUDENT, picName = "Sam"))
        val merge = service(target).prepareMerge(service(source).exportJson()) as Prepared.Merge
        assertEquals(1, merge.summary.added)
        service(target).merge(merge)
        assertEquals(5, repo(target).nextLogNo())

        // A different flight with log number 4 on this phone collides, and nothing is committed.
        r.saveFlight(null, FlightDraft(logNoText = "5", date = LocalDate.of(2026, 10, 20), durationText = "50",
            gliderId = g, airfieldId = a, clubId = c, launchTypeId = wt, role = Role.PIC, picName = "Pat"))
        val t = repo(target)
        val (_, local5) = t.saveFlight(null, FlightDraft(logNoText = "5", date = LocalDate.of(2026, 10, 21), durationText = "20",
            gliderId = g, airfieldId = a, clubId = c, launchTypeId = wt, role = Role.PIC, picName = "Pat"))
        val clash = service(target).prepareMerge(service(source).exportJson())
        assertTrue("$clash", clash is Prepared.Refused && clash.problems.any { it.text(context).startsWith("Log number 5 is used by two different flights") })
        assertEquals(20, t.flight(local5!!)!!.durationMinutes)
    }
}
