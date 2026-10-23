package io.github.jgfyyvzss.soarlog.core.igc

import io.github.jgfyyvzss.soarlog.core.Keys
import java.util.Locale

/** An airfield with coordinates, as matching needs it. */
data class MatchPlace(val id: String, val latitude: Double, val longitude: Double, val radiusM: Int?)

data class NearbyPlace(val id: String, val distanceM: Double)

/** Where a segment landed, when the Place setting is on. */
sealed interface LandingMatch {
    data object SameAsDeparture : LandingMatch
    data class Airfield(val id: String) : LandingMatch
    /** Coordinates as text, for example `-36.7842, 147.1700`, for the pilot to rename. */
    data class Other(val place: String) : LandingMatch
}

object IgcMatching {
    /** Airfields whose match radius contains the fix, nearest first. */
    fun airfieldsNear(fix: IgcFix, places: List<MatchPlace>): List<NearbyPlace> =
        places.mapNotNull { p ->
            val d = Geo.distanceM(fix.latitude, fix.longitude, p.latitude, p.longitude)
            if (d <= (p.radiusM ?: IgcParameters.DEFAULT_MATCH_RADIUS_M)) NearbyPlace(p.id, d) else null
        }.sortedBy { it.distanceM }

    /** Inside the launch airfield's radius means the same place; otherwise the nearest airfield in range, or coordinates. */
    fun landing(segment: IgcSegment, launchAirfieldId: String?, places: List<MatchPlace>): LandingMatch {
        val near = airfieldsNear(segment.landing, places)
        return when {
            launchAirfieldId != null && near.any { it.id == launchAirfieldId } -> LandingMatch.SameAsDeparture
            near.isNotEmpty() -> LandingMatch.Airfield(near.first().id)
            else -> LandingMatch.Other(String.format(Locale.ROOT, "%.4f, %.4f", segment.landing.latitude, segment.landing.longitude))
        }
    }

    /**
     * The glider whose registration key equals the header's. Failing that, the header without its nationality
     * prefix (the text before the first hyphen), so `VH-FQM` finds a glider entered as `FQM`.
     */
    fun <T> glider(gliderIdHeader: String?, gliders: List<T>, key: (T) -> String): T? {
        val header = gliderIdHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val full = Keys.registrationKey(header)
        gliders.firstOrNull { key(it) == full }?.let { return it }
        val local = header.substringAfter('-', "").let(Keys::registrationKey).takeIf { it.isNotEmpty() } ?: return null
        return gliders.firstOrNull { key(it) == local }
    }
}
