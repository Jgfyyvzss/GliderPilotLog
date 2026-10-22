package io.github.jgfyyvzss.gliderpilotlog.core.lxnav

import io.github.jgfyyvzss.gliderpilotlog.core.MsgKey
import io.github.jgfyyvzss.gliderpilotlog.core.lxnav.FakeLogger.Companion.lines
import io.github.jgfyyvzss.gliderpilotlog.core.lxnav.FakeLogger.Fault
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LxnavTest {
    @get:Rule val folder = TemporaryFolder()

    private fun fixture(name: String): ByteArray =
        listOf(File("../testdata"), File("testdata")).first { it.isDirectory }.resolve(name).readBytes()

    private fun entry(name: String, listedSize: Long? = null): FakeLogger.Entry {
        val bytes = fixture(name)
        return FakeLogger.Entry(name, "13.02.2025", "01:25:44", bytes, listedSize ?: bytes.size.toLong())
    }

    private val names = listOf("52DVPSA1.igc", "52JVPSA1.igc", "52JVPSA2.igc", "52OVPSA1.igc", "53PVPSA1.igc", "55DVPSA1.igc")

    private fun partial() = FilePartialDownload(File(folder.root, "lxnav.part"))

    private fun TestScope.logger(vararg files: FakeLogger.Entry) = FakeLogger(backgroundScope, files.toList())

    private suspend fun FakeLogger.download(file: FakeLogger.Entry, partial: PartialDownload = partial(), progress: MutableList<LxnavProgress> = mutableListOf()): ByteArray =
        LxnavClient(this).session { download(logbook().first { it.name == file.name }, partial, progress::add) }

    @Test fun checksumsMatchTheOnesConfirmedOnTheDevice() {
        assertEquals("\$PLXVC,FILE_CANCEL,R*5C\r\n", String(Nmea.sentence("PLXVC,FILE_CANCEL,R")))
        assertEquals("\$PLXVC,INFO,R*0D\r\n", String(Nmea.sentence("PLXVC,INFO,R")))
        assertEquals("\$PLXVC,BT_INFO,R*44\r\n", String(Nmea.sentence("PLXVC,BT_INFO,R")))
        assertEquals("\$PLXVC,LOGBOOKSIZE,R*4B\r\n", String(Nmea.sentence("PLXVC,LOGBOOKSIZE,R")))
        assertEquals("PLXVC,FILE_CANCEL,R", Nmea.body("\$PLXVC,FILE_CANCEL,R*5C\r"))
        assertNull("bad checksum", Nmea.body("\$PLXVC,FILE_CANCEL,R*5D"))
        assertNull("not a sentence", Nmea.body("PLXVC,FILE_CANCEL,R*5C"))
    }

    @Test fun logbookListsEveryFile() = runTest {
        val files = names.map { entry(it) }.toTypedArray()
        val book = LxnavClient(logger(*files)).session { logbook() }
        assertEquals(names, book.map { it.name })
        assertEquals(files.map { it.bytes.size.toLong() }, book.map { it.sizeBytes })
        assertEquals(LocalDate.of(2025, 2, 13), book.first().date)
        assertEquals(LocalTime.of(1, 25, 44), book.first().startUtc)
    }

    @Test fun everySampleFileDownloadsByteForByte() = runTest {
        names.forEach { name ->
            val e = entry(name)
            val logger = logger(e)
            assertArrayEquals(name, e.bytes, logger.download(e))
            // A transfer is reset at the start and end of every session.
            assertEquals("PLXVC,FILE_CANCEL,R", logger.commands.first())
            assertEquals("PLXVC,FILE_CANCEL,R", logger.commands.last())
        }
    }

    @Test fun linesWithCommasSurviveTheExtraEmptyField() = runTest {
        val e = entry("52DVPSA1.igc")
        val text = String(logger(e).download(e), Charsets.ISO_8859_1)
        assertTrue(text.contains("HFFTYFRTYPE:LXNAV,S100\r\n"))
    }

    @Test fun progressCountsUpToTheTotal() = runTest {
        val e = entry("52DVPSA1.igc")
        val progress = mutableListOf<LxnavProgress>()
        logger(e).download(e, progress = progress)
        val total = e.lines().size
        assertEquals(LxnavProgress(0, null, 0), progress.first())
        assertEquals(LxnavProgress(total, total, 0), progress.last())
    }

    @Test fun aLostLineOrReplyIsAskedForAgain() = runTest {
        val e = entry("52JVPSA1.igc")
        val logger = logger(e)
        logger.faults += listOf(Fault.DropLine(10), Fault.DropReply(50), Fault.DropLogbook)
        assertArrayEquals(e.bytes, logger.download(e))
        assertTrue(logger.faults.isEmpty())
        assertEquals(1, logger.connects)
    }

    @Test fun twoFailuresInARowSwitchToSingleLines() = runTest {
        val e = entry("52JVPSA1.igc")
        val logger = logger(e)
        logger.faults += listOf(Fault.DropReply(8), Fault.DropReply(8))
        assertArrayEquals(e.bytes, logger.download(e))
        val requests = logger.commands.filter { it.startsWith("PLXVC,FLIGHT,R") }.map { it.split(',').let { f -> f[4].toInt() to f[5].toInt() } }
        assertEquals(listOf(1 to 8, 8 to 15, 8 to 15, 8 to 9), requests.take(4))
    }

    @Test fun aSilentStallRecoversByReconnecting() = runTest {
        val e = entry("52OVPSA1.igc")
        val logger = logger(e)
        logger.faults += Fault.Stall(300)
        assertArrayEquals(e.bytes, logger.download(e))
        assertEquals(2, logger.connects)
    }

    @Test fun aDroppedConnectionReconnectsAndContinues() = runTest {
        val e = entry("53PVPSA1.igc")
        val logger = logger(e)
        logger.faults += listOf(Fault.Disconnect(100), Fault.Disconnect(900))
        assertArrayEquals(e.bytes, logger.download(e))
        assertEquals(3, logger.connects)
    }

    @Test fun loggerSwitchedOffFailsCleanlyAndResumesLater() = runTest {
        val e = entry("55DVPSA1.igc")
        val logger = logger(e)
        val partial = partial()
        logger.faults += Fault.SwitchOff(500)
        val failure = runCatching { logger.download(e, partial) }.exceptionOrNull() as LxnavFailure
        assertEquals(MsgKey.LXNAV_GAVE_UP, failure.shown.key)
        val stoppedAt = failure.shown.args.single() as Int
        assertTrue("stopped at $stoppedAt", stoppedAt in 494..500)

        logger.switchOn()
        val progress = mutableListOf<LxnavProgress>()
        assertArrayEquals(e.bytes, logger.download(e, partial, progress))
        assertEquals(stoppedAt - 1, progress.first().resumedFrom)
        // The resumed download asks again for the last saved line, to learn the total and check the partial.
        val firstRequest = logger.commands.last { it.startsWith("PLXVC,FLIGHT,R") && it.split(',')[4].toInt() == stoppedAt - 1 }
        assertTrue(firstRequest, firstRequest.isNotEmpty())
        assertTrue("the partial is deleted once the file is complete", !File(folder.root, "lxnav.part").exists())
    }

    @Test fun aSizeMismatchFailsAndDiscardsTheDownload() = runTest {
        val e = entry("52DVPSA1.igc", listedSize = 47_516)
        val failure = runCatching { logger(e).download(e) }.exceptionOrNull() as LxnavFailure
        assertEquals(MsgKey.LXNAV_SIZE_MISMATCH, failure.shown.key)
        assertEquals(listOf<Any>(47_514, 47_516L), failure.shown.args)
        assertTrue(!File(folder.root, "lxnav.part").exists())
    }

    @Test fun cancellingStillResetsTheTransfer() = runTest {
        val e = entry("53PVPSA1.igc")
        val logger = logger(e)
        val job = launch { logger.download(e) }
        advanceTimeBy(5_000)
        job.cancel()
        job.join()
        assertEquals("PLXVC,FILE_CANCEL,R", logger.commands.last())
    }

    @Test fun anUnreachableLoggerReportsAConnectFailure() = runTest {
        val logger = logger(entry("52DVPSA1.igc"))
        logger.faults += Fault.SwitchOff(1)
        runCatching { logger.download(entry("52DVPSA1.igc")) }
        val failure = runCatching { LxnavClient(logger).session { logbook() } }.exceptionOrNull() as LxnavFailure
        assertEquals(MsgKey.LXNAV_CONNECT_FAILED, failure.shown.key)
    }

    // The resume file

    @Test fun partialResumesOnlyTheSameFileAndDropsACutShortLine() {
        val file = File(folder.root, "p.part")
        val a = LxnavFile("A.igc", null, null, 100)
        val p = FilePartialDownload(file)
        assertEquals(emptyList<String>(), p.resume(a))
        p.append(listOf("one", "two"))
        file.appendText("thr", Charsets.ISO_8859_1) // interrupted mid-line
        assertEquals(listOf("one", "two"), p.resume(a))
        p.append(listOf("three"))
        assertEquals(listOf("one", "two", "three"), FilePartialDownload(file).resume(a))
        // Another file, or the same name with another size, starts afresh.
        assertEquals(emptyList<String>(), p.resume(a.copy(sizeBytes = 101)))
        assertEquals(emptyList<String>(), p.resume(a))
    }

    @Test fun partialExpiresAfterTwoDays() {
        val file = File(folder.root, "p.part")
        val a = LxnavFile("A.igc", null, null, 100)
        var now = System.currentTimeMillis()
        val p = FilePartialDownload(file) { now }
        p.resume(a)
        p.append(listOf("one"))
        now = file.lastModified() + FilePartialDownload.MAX_AGE_MS - 1
        assertEquals(listOf("one"), p.resume(a))
        now = file.lastModified() + FilePartialDownload.MAX_AGE_MS + 1
        p.deleteIfExpired()
        assertTrue(!file.exists())
    }
}
