package io.github.jgfyyvzss.gliderpilotlog.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TypeTotalsTest {
    private val names = mapOf(21L to "ASK 21")

    @Test fun groupsByWeGlideTypeOrTypeTextMostHoursFirst() {
        val gliders = listOf(
            GliderTotal("Hornet", null, 300, 10),
            GliderTotal("hornet ", null, 100, 2),   // same text ignoring case and spaces
            GliderTotal("ASK21", 21, 200, 20),        // WeGlide type set: named by WeGlide
            GliderTotal("ASK 21", 21, 150, 15),
            GliderTotal("ASK21", null, 50, 5),        // no WeGlide type: stays apart
        )
        assertEquals(
            listOf(TypeTotal("Hornet", 400, 12), TypeTotal("ASK 21", 350, 35), TypeTotal("ASK21", 50, 5)),
            TypeTotals.group(gliders) { names[it] },
        )
    }

    @Test fun unknownWeGlideTypeFallsBackToTheGliderType() {
        assertEquals(listOf(TypeTotal("Junior", 60, 1)), TypeTotals.group(listOf(GliderTotal("Junior", 999, 60, 1))) { names[it] })
    }

    @Test fun tiesGoByFlightsThenName() {
        val rows = TypeTotals.group(listOf(GliderTotal("B", null, 60, 1), GliderTotal("A", null, 60, 1), GliderTotal("C", null, 60, 3))) { null }
        assertEquals(listOf("C", "A", "B"), rows.map { it.label })
    }
}
