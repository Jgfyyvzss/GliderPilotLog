package io.github.jgfyyvzss.gliderpilotlog.core.igc

import io.github.jgfyyvzss.gliderpilotlog.core.weglide.UploadAircraft
import java.io.File
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IgcLibraryTest {
    @get:Rule val folder = TemporaryFolder()

    private fun fixture(name: String): ByteArray =
        listOf(File("../testdata"), File("testdata")).first { it.isDirectory }.resolve(name).readBytes()

    private var now = 1_760_000_000_000L
    private fun library() = IgcLibrary(File(folder.root, "igc")) { now }

    @Test fun storesTheFileByteForByteWithItsDetails() {
        val bytes = fixture("52DVPSA1.igc")
        val e = library().add("52DVPSA1.igc", bytes)
        assertArrayEquals(bytes, library().bytes(e.id))
        assertEquals(1, e.flights)
        assertEquals("VH-FQM", e.gliderIdHeader)
        assertEquals(Instant.parse("2025-02-13T01:28:33Z"), e.firstLaunch)
        assertFalse(e.uploaded)
        assertTrue(e.logged.isEmpty())
    }

    @Test fun theSameFileAgainFindsTheSameEntryAndItsStatuses() {
        val lib = library()
        val bytes = fixture("52JVPSA1.igc")
        val first = lib.add("a.igc", bytes)
        lib.update(first.id) { it.copy(uploaded = true, weglideFlightId = 7, logged = setOf(0), loggedGliderId = "g1") }
        val again = library().add("renamed.igc", bytes) // a new instance, as after restarting the app
        assertEquals(first.id, again.id)
        assertTrue(again.uploaded)
        assertTrue(again.fullyLogged)
        assertEquals("g1", again.loggedGliderId)
        assertEquals(1, library().list().size)
    }

    @Test fun filesExpireAfterSevenDays() {
        val lib = library()
        val old = lib.add("old.igc", fixture("52DVPSA1.igc"))
        now += IgcLibrary.MAX_AGE_MS - 1
        val young = lib.add("young.igc", fixture("52JVPSA1.igc"))
        assertEquals(listOf(young.id, old.id), lib.list().map { it.id })
        now += 2
        assertEquals(listOf(young.id), lib.list().map { it.id })
        assertNull(lib.bytes(old.id))
    }

    @Test fun onlyTheNewestFiftyAreKept() {
        val lib = library()
        val ids = (1..IgcLibrary.MAX_FILES + 2).map { i ->
            now += 1000
            lib.add("f$i.igc", fixture("52DVPSA1.igc") + "LTEST$i\r\n".toByteArray()).id
        }
        val kept = lib.list().map { it.id }
        assertEquals(IgcLibrary.MAX_FILES, kept.size)
        assertEquals(ids.takeLast(IgcLibrary.MAX_FILES).reversed(), kept)
    }

    @Test fun removeDeletesTheFileAndItsDetails() {
        val lib = library()
        val e = lib.add("a.igc", fixture("52DVPSA1.igc"))
        lib.remove(e.id)
        assertNull(lib.get(e.id))
        assertTrue(File(folder.root, "igc").listFiles().orEmpty().isEmpty())
    }

    @Test fun aFileWithoutAFlightIsStoredWithNothingToLog() {
        val e = library().add("notes.txt", "hello".toByteArray())
        assertEquals(0, e.flights)
        assertFalse(e.fullyLogged)
        assertNull(e.firstLaunch)
    }

    // The WeGlide aircraft for an upload

    private data class G(val name: String, val aircraftId: Long?)

    @Test fun savedFlightsGliderComesFirstWhenItHasAType() {
        val saved = G("saved", 1)
        val matched = G("matched", 2)
        assertEquals(saved, UploadAircraft.choose(saved, matched) { it.aircraftId })
        assertEquals(matched, UploadAircraft.choose(G("saved", null), matched) { it.aircraftId })
        assertEquals(G("saved", null), UploadAircraft.choose(G("saved", null), G("matched", null)) { it.aircraftId })
        assertEquals(matched, UploadAircraft.choose(null, matched) { it.aircraftId })
        assertNull(UploadAircraft.choose<G>(null, null) { it.aircraftId })
    }
}
