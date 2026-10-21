package io.github.jgfyyvzss.gliderpilotlog.core.igc

import io.github.jgfyyvzss.gliderpilotlog.core.Msg
import io.github.jgfyyvzss.gliderpilotlog.core.MsgKey
import io.github.jgfyyvzss.gliderpilotlog.core.msg
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** One valid (3D) fix. Times are UTC instants; altitude is GNSS altitude in metres. */
data class IgcFix(val time: Instant, val latitude: Double, val longitude: Double, val altitudeM: Int)

/** The parts of an IGC file the logbook uses. Nothing else is kept. */
data class IgcTrack(val gliderId: String?, val fixes: List<IgcFix>)

sealed interface IgcRead {
    data class Ok(val track: IgcTrack) : IgcRead
    data class Refused(val message: Msg) : IgcRead
}

/**
 * Reads the date header, the glider ID header and the B records of an IGC file, following Appendix A of the FAI
 * IGC technical specification (January 2026, AL10): A2.5.4 and the H record list for HFDTE and HFGID, A4.1 for
 * the B record layout `B HHMMSS DDMMmmmN DDDMMmmmE V PPPPP GGGGG`, where V is A for a 3D fix.
 */
object IgcParser {
    private val dateHeader = Regex("""^HFDTE(?:DATE:)?(\d{2})(\d{2})(\d{2})""")
    private val bRecord = Regex("""^B(\d{2})(\d{2})(\d{2})(\d{2})(\d{5})([NS])(\d{3})(\d{5})([EW])([AV])([-\d]{5})([-\d]{5})""")

    fun parse(text: String): IgcRead {
        var date: LocalDate? = null
        var gliderId: String? = null
        val fixes = mutableListOf<IgcFix>()
        var dayOffset = 0L
        var lastSecond = -1
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("HFDTE") && date == null -> dateHeader.find(line)?.let { m ->
                    val (dd, mm, yy) = m.destructured
                    date = runCatching { LocalDate.of(2000 + yy.toInt(), mm.toInt(), dd.toInt()) }.getOrNull()
                }
                line.startsWith("HFGID") && gliderId == null ->
                    gliderId = line.substringAfter(':', line.drop(5)).trim().ifEmpty { null }
                line.startsWith("B") -> {
                    val day = date ?: continue
                    val m = bRecord.find(line) ?: continue
                    val g = m.groupValues
                    val second = g[1].toInt() * 3600 + g[2].toInt() * 60 + g[3].toInt()
                    if (second >= 86_400 || g[4].toInt() > 90 || g[7].toInt() > 180) continue
                    // Times are UTC and only go forward; an earlier time means the flight crossed midnight UTC.
                    if (lastSecond >= 0 && second < lastSecond) dayOffset++
                    lastSecond = second
                    if (g[10] != "A") continue
                    val altitude = g[12].toIntOrNull() ?: continue
                    val lat = (g[4].toInt() + g[5].toInt() / 60_000.0) * (if (g[6] == "S") -1 else 1)
                    val lon = (g[7].toInt() + g[8].toInt() / 60_000.0) * (if (g[9] == "W") -1 else 1)
                    val time = day.plusDays(dayOffset).atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(second.toLong())
                    fixes += IgcFix(time, lat, lon, altitude)
                }
            }
        }
        return when {
            date == null -> IgcRead.Refused(msg(MsgKey.IGC_NO_DATE))
            fixes.isEmpty() -> IgcRead.Refused(msg(MsgKey.IGC_NO_FIXES))
            else -> IgcRead.Ok(IgcTrack(gliderId, fixes))
        }
    }
}
