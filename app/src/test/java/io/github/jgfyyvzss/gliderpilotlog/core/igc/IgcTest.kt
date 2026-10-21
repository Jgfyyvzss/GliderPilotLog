package io.github.jgfyyvzss.gliderpilotlog.core.igc

import io.github.jgfyyvzss.gliderpilotlog.core.Keys
import io.github.jgfyyvzss.gliderpilotlog.core.MsgKey
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IgcTest {
    private val melbourne = ZoneId.of("Australia/Melbourne")

    private fun fixture(name: String): String =
        listOf(File("../testdata"), File("testdata")).first { it.isDirectory }.resolve(name).readText(Charsets.ISO_8859_1)

    private fun track(text: String) = (IgcParser.parse(text) as IgcRead.Ok).track
    private fun refusal(text: String) = (IgcParser.parse(text) as IgcRead.Refused).message.key

    /** Takeoff (UTC) and duration from another IGC reader, as recorded in testdata/igc_data.csv. */
    private data class Expected(val file: String, val takeoffUtc: String, val durationSeconds: Long)

    private val expected = listOf(
        Expected("52DVPSA1.igc", "2025-02-13T01:28:39Z", 5 * 60 + 55L),
        Expected("52JVPSA1.igc", "2025-02-19T00:59:24Z", 5 * 60 + 27L),
        Expected("52JVPSA2.igc", "2025-02-19T01:22:38Z", 10 * 60 + 36L),
        Expected("52OVPSA1.igc", "2025-02-24T02:11:08Z", 13 * 60 + 8L),
        Expected("53PVPSA1.igc", "2025-03-25T02:45:27Z", 14 * 60 + 42L),
        Expected("55DVPSA1.igc", "2025-05-13T02:22:25Z", 12 * 60 + 4L),
    )

    @Test fun sampleFilesGiveTheDateAndDurationToWithinAMinute() {
        expected.forEach { e ->
            val segments = IgcFlights.segments(track(fixture(e.file)))
            assertEquals("${e.file}: one flight", 1, segments.size)
            val s = segments.single()
            val takeoff = Instant.parse(e.takeoffUtc)
            val launchError = Duration.between(takeoff, s.launch.time).abs().seconds
            val seconds = Duration.between(s.launch.time, s.landing.time).seconds
            assertTrue("${e.file}: launch ${s.launch.time} vs $takeoff", launchError <= 60)
            assertTrue("${e.file}: $seconds s vs ${e.durationSeconds} s", kotlin.math.abs(seconds - e.durationSeconds) <= 60)
            assertTrue("${e.file}: ${s.durationMinutes} min", kotlin.math.abs(s.durationMinutes * 60 - e.durationSeconds) <= 60)
            assertEquals(e.file, takeoff.atZone(melbourne).toLocalDate(), s.date(melbourne))
        }
    }

    @Test fun readsTheHeaders() {
        val t = track(fixture("52DVPSA1.igc"))
        assertEquals("VH-FQM", t.gliderId)
        val first = t.fixes.first()
        assertEquals(Instant.parse("2025-02-13T01:25:44Z"), first.time)
        // B0125443644081S14710277EA0036800355: 36° 44.081' S, 147° 10.277' E, GNSS altitude 355 m.
        assertEquals(-(36 + 44.081 / 60), first.latitude, 1e-9)
        assertEquals(147 + 10.277 / 60, first.longitude, 1e-9)
        assertEquals(355, first.altitudeM)
    }

    @Test fun newerDateHeaderFormIsRead() {
        val text = fixture("52DVPSA1.igc").replace("HFDTE130225", "HFDTEDATE:130225,01")
        assertEquals(LocalDate.of(2025, 2, 13), IgcFlights.segments(track(text)).single().date(melbourne))
    }

    @Test fun refusesFilesWithoutADateOrValidFixes() {
        assertEquals(MsgKey.IGC_NO_DATE, refusal("hello\nworld"))
        val text = fixture("52DVPSA1.igc")
        assertEquals(MsgKey.IGC_NO_FIXES, refusal(text.lines().filterNot { it.startsWith("B") }.joinToString("\n")))
        // V marks a 2D fix or no GNSS data; only A fixes are used.
        val allV = text.lines().joinToString("\n") { if (it.startsWith("B") && it.length > 24) it.substring(0, 24) + "V" + it.substring(25) else it }
        assertEquals(MsgKey.IGC_NO_FIXES, refusal(allV))
    }

    @Test fun twoFlightsInOneFileAreListedSeparately() {
        val a = fixture("52JVPSA1.igc").lines()
        val b = fixture("52JVPSA2.igc").lines()
        val combined = (a + b.filter { it.startsWith("B") }).joinToString("\n")
        val both = IgcFlights.segments(track(combined))
        val first = IgcFlights.segments(track(a.joinToString("\n"))).single()
        val second = IgcFlights.segments(track(b.joinToString("\n"))).single()
        assertEquals(listOf(first, second), both)
    }

    @Test fun noClimbMeansNoFlight() {
        // The same track with every GNSS altitude flattened, like a logger driven along a road.
        val flat = fixture("52DVPSA1.igc").lines().joinToString("\n") {
            if (it.startsWith("B") && it.length >= 35) it.substring(0, 30) + "00355" + it.substring(35) else it
        }
        assertTrue(IgcFlights.segments(track(flat)).isEmpty())
    }

    @Test fun flightCrossingMidnightUtcGetsTheNextLocalDate() {
        // Shift every fix by 22:30, so the flight starts at 23:59 UTC on the 13th and lands on the 14th.
        val shifted = fixture("52DVPSA1.igc").lines().joinToString("\n") { line ->
            if (!line.startsWith("B") || line.length < 7) return@joinToString line
            val s = (line.substring(1, 3).toInt() * 3600 + line.substring(3, 5).toInt() * 60 + line.substring(5, 7).toInt() + 22 * 3600 + 30 * 60) % 86_400
            "B%02d%02d%02d".format(s / 3600, s / 60 % 60, s % 60) + line.substring(7)
        }
        val s = IgcFlights.segments(track(shifted)).single()
        val original = IgcFlights.segments(track(fixture("52DVPSA1.igc"))).single()
        assertEquals(original.durationMinutes, s.durationMinutes)
        assertEquals(LocalDate.of(2025, 2, 13), s.launch.time.atZone(ZoneId.of("UTC")).toLocalDate())
        assertEquals(LocalDate.of(2025, 2, 14), s.landing.time.atZone(ZoneId.of("UTC")).toLocalDate())
        assertEquals(LocalDate.of(2025, 2, 14), s.date(melbourne))
    }

    @Test fun durationRoundsToTheNearestMinuteWithAMinimumOfOne() {
        val t0 = Instant.parse("2025-02-13T01:00:00Z")
        fun seg(seconds: Long) = IgcSegment(IgcFix(t0, 0.0, 0.0, 0), IgcFix(t0.plusSeconds(seconds), 0.0, 0.0, 0))
        assertEquals(1, seg(20).durationMinutes)
        assertEquals(5, seg(5 * 60 + 29).durationMinutes)
        assertEquals(6, seg(5 * 60 + 30).durationMinutes)
    }

    // Matching

    private val launch = IgcFix(Instant.EPOCH, -36.7347, 147.1712, 368)
    private fun north(metres: Double) = launch.latitude + metres / 111_195.0

    @Test fun airfieldsInRangeAreListedNearestFirst() {
        val places = listOf(
            MatchPlace("far-default", north(2000.0), launch.longitude, null),
            MatchPlace("far-wide", north(2000.0), launch.longitude, 3000),
            MatchPlace("near", north(800.0), launch.longitude, null),
            MatchPlace("here", launch.latitude, launch.longitude, null),
        )
        val near = IgcMatching.airfieldsNear(launch, places)
        assertEquals(listOf("here", "near", "far-wide"), near.map { it.id })
        assertEquals(800.0, near[1].distanceM, 5.0)
    }

    @Test fun landingPlaceMatchesTheLaunchAirfieldAnotherOrCoordinates() {
        val home = MatchPlace("home", launch.latitude, launch.longitude, null)
        val other = MatchPlace("other", north(10_000.0), launch.longitude, null)
        fun landingAt(lat: Double) = IgcSegment(launch, launch.copy(latitude = lat))
        assertEquals(LandingMatch.SameAsDeparture, IgcMatching.landing(landingAt(north(300.0)), "home", listOf(home, other)))
        assertEquals(LandingMatch.Airfield("other"), IgcMatching.landing(landingAt(north(10_100.0)), "home", listOf(home, other)))
        assertEquals(LandingMatch.Other("-36.7842, 147.1712"), IgcMatching.landing(landingAt(-36.78421), "home", listOf(home, other)))
    }

    @Test fun gliderMatchesWithOrWithoutTheNationalityPrefix() {
        val keys = listOf("FQM", "B4", "?").map(Keys::registrationKey)
        assertEquals("fqm", IgcMatching.glider("VH-FQM", keys) { it })
        assertEquals("fqm", IgcMatching.glider("fqm", keys) { it })
        assertEquals("vhfqm", IgcMatching.glider("VH-FQM", keys + "vhfqm") { it })
        assertEquals("1234", IgcMatching.glider("D-1234", listOf("1234")) { it })
        assertNull(IgcMatching.glider("VH-GZY", keys) { it })
        assertNull(IgcMatching.glider(null, keys) { it })
        assertNull(IgcMatching.glider("  ", keys) { it })
    }

    @Test fun haversineDistance() {
        assertEquals(111_195.0, Geo.distanceM(0.0, 0.0, 1.0, 0.0), 1.0)
        assertEquals(0.0, Geo.distanceM(-36.7, 147.1, -36.7, 147.1), 1e-6)
    }
}
