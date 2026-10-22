package io.github.jgfyyvzss.gliderpilotlog.core

import java.util.Locale

/** One glider's live flights, all roles, with no filter and no opening balances. */
data class GliderTotal(val type: String, val weglideAircraftId: Long?, val minutes: Int, val flights: Int)

/** Hours and flights for one aircraft type. */
data class TypeTotal(val label: String, val minutes: Int, val flights: Int)

object TypeTotals {
    /**
     * Groups gliders by type, most hours first. Gliders with a WeGlide aircraft are grouped by it and labelled with
     * WeGlide's name, so `ASK21` and `ASK 21` meet once both have their WeGlide type. Otherwise the type text is
     * matched ignoring case and surrounding spaces, and the first spelling seen is shown.
     */
    fun group(gliders: List<GliderTotal>, weglideName: (Long) -> String?): List<TypeTotal> =
        gliders.groupBy { g -> g.weglideAircraftId?.let { "weglide:$it" } ?: "type:" + g.type.trim().lowercase(Locale.ROOT) }
            .map { (_, members) ->
                val first = members.first()
                val label = first.weglideAircraftId?.let(weglideName) ?: members.map { it.type.trim() }.firstOrNull { it.isNotEmpty() } ?: "?"
                TypeTotal(label, members.sumOf { it.minutes }, members.sumOf { it.flights })
            }
            .sortedWith(compareByDescending<TypeTotal> { it.minutes }.thenByDescending { it.flights }.thenBy { it.label.lowercase(Locale.ROOT) })
}
