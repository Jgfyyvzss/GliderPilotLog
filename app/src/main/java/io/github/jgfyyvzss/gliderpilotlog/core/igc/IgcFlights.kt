package io.github.jgfyyvzss.gliderpilotlog.core.igc

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** The Appendix D defaults, kept in one place so they can be tuned against real files. */
object IgcParameters {
    const val FLYING_SPEED_KMH = 50.0
    const val START_HOLD_SECONDS = 10L
    const val END_HOLD_SECONDS = 30L
    const val MAX_FIX_GAP_SECONDS = 60L
    const val MIN_SEGMENT_SECONDS = 30L
    const val MIN_CLIMB_M = 50
    const val DEFAULT_MATCH_RADIUS_M = 1000
}

/** One flying segment, from launch fix to landing fix. */
data class IgcSegment(val launch: IgcFix, val landing: IgcFix) {
    /** Landing minus launch, rounded to the nearest minute, at least one. */
    val durationMinutes: Int get() = max(1, (Duration.between(launch.time, landing.time).seconds / 60.0).roundToInt())

    fun date(zone: ZoneId): LocalDate = launch.time.atZone(zone).toLocalDate()
    fun launchTime(zone: ZoneId): LocalTime = launch.time.atZone(zone).toLocalTime().withSecond(0).withNano(0)
    fun landingTime(zone: ZoneId): LocalTime = landing.time.atZone(zone).toLocalTime().withSecond(0).withNano(0)
}

object IgcFlights {
    /**
     * Splits a track into flying segments. Flying starts when ground speed stays at or above the threshold for the
     * start hold, and ends when it stays below it for the end hold; each segment begins and ends at the first fix of
     * that run. A segment must last long enough and climb enough above its start, so a logger left in a car is no
     * flight. Speeds come from consecutive fixes; pairs too far apart are skipped.
     */
    fun segments(track: IgcTrack): List<IgcSegment> {
        val fixes = track.fixes
        val found = mutableListOf<IgcSegment>()
        var launchAt = -1     // index of the launch fix while flying
        var runStart = -1     // first fix of the current fast run (on the ground) or slow run (flying)
        for (i in 1 until fixes.size) {
            val speed = speedKmh(fixes[i - 1], fixes[i]) ?: continue
            val fast = speed >= IgcParameters.FLYING_SPEED_KMH
            if (launchAt < 0) {
                if (!fast) { runStart = -1; continue }
                if (runStart < 0) runStart = i
                if (seconds(fixes[runStart], fixes[i]) >= IgcParameters.START_HOLD_SECONDS) {
                    launchAt = runStart
                    runStart = -1
                }
            } else {
                if (fast) { runStart = -1; continue }
                if (runStart < 0) runStart = i
                if (seconds(fixes[runStart], fixes[i]) >= IgcParameters.END_HOLD_SECONDS) {
                    keep(fixes, launchAt, runStart)?.let(found::add)
                    launchAt = -1
                    runStart = -1
                }
            }
        }
        // The logger stopped while flying or soon after landing.
        if (launchAt >= 0) keep(fixes, launchAt, if (runStart >= 0) runStart else fixes.lastIndex)?.let(found::add)
        return found
    }

    private fun keep(fixes: List<IgcFix>, from: Int, to: Int): IgcSegment? {
        val launch = fixes[from]
        val landing = fixes[to]
        if (seconds(launch, landing) < IgcParameters.MIN_SEGMENT_SECONDS) return null
        val highest = (from..to).maxOf { fixes[it].altitudeM }
        if (highest - launch.altitudeM < IgcParameters.MIN_CLIMB_M) return null
        return IgcSegment(launch, landing)
    }

    private fun seconds(a: IgcFix, b: IgcFix) = Duration.between(a.time, b.time).seconds

    private fun speedKmh(a: IgcFix, b: IgcFix): Double? {
        val dt = seconds(a, b)
        if (dt <= 0 || dt > IgcParameters.MAX_FIX_GAP_SECONDS) return null
        return Geo.distanceM(a.latitude, a.longitude, b.latitude, b.longitude) / dt * 3.6
    }
}

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Haversine distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
