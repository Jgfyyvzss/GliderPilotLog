package io.github.jgfyyvzss.soarlog.core.weglide

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AircraftSearchTest {
    private val list = listOf(
        WeGlideAircraft(1, "ASK 21", "GL", "CL"),
        WeGlideAircraft(2, "ASK 21 Mi", "MG", "CL"),
        WeGlideAircraft(3, "ASK 13", "GL", "CL"),
        WeGlideAircraft(4, "Twin Astir", "GL", "CL"),
        WeGlideAircraft(5, "Duo Discus", "GL", "DO"),
        WeGlideAircraft(6, "Grob G 103 Twin II", "GL", "DO"),
        WeGlideAircraft(7, "Schleicher Ka 6 CR", "GL", "CL"),
        WeGlideAircraft(8, "Lünebürg Ölfaß", "GL", null),
    )

    private fun ids(q: String) = AircraftSearch.search(list, q).map { it.id }

    @Test fun normalisesCaseAccentsAndPunctuation() {
        assertEquals("ask21", AircraftSearch.normalise("ASK-21"))
        assertEquals("ask21", AircraftSearch.normalise(" a.s.k 21 "))
        assertEquals("luneburgolfaß", AircraftSearch.normalise("Lünebürg Ölfaß"))
    }

    @Test fun ranksExactThenStartsWithThenContains() {
        assertEquals(listOf(1L, 2L), ids("ASK21"))
        assertEquals(listOf(3L, 1L, 2L), ids("ask"))
        assertEquals(listOf(4L, 6L), ids("twin"))
        assertEquals(listOf(8L), ids("luneburg"))
        assertEquals(emptyList<Long>(), ids("Nimbus"))
    }

    @Test fun blankQueryListsEverythingByName() {
        assertEquals(list.size, ids("").size)
        assertEquals("ASK 13", AircraftSearch.search(list, " ").first().name)
    }

    @Test fun parsesAndSkipsUnusableEntries() {
        val parsed = WeGlideAircraftList.parse(
            """[{"id":1,"name":"ASK 21","kind":"GL","sc_class":"CL","dmst_index":92.0},
               {"id":"2","name":"string id"},{"name":"no id"},{"id":3},{"id":4,"name":42},{"id":5,"name":"  "},
               {"id":6,"name":"Discus","kind":null}]""",
        )!!
        assertEquals(listOf(WeGlideAircraft(1, "ASK 21", "GL", "CL"), WeGlideAircraft(6, "Discus", null, null)), parsed)
        assertNull(WeGlideAircraftList.parse("""{"not":"an array"}"""))
        assertNull(WeGlideAircraftList.parse("<html>"))
        assertEquals("ASK 21 · GL · CL", parsed.first().label)
    }

    /** The list bundled with the app, from testdata/aircraft.json. */
    @Test fun parsesTheBundledList() {
        val file = File("../testdata/aircraft.json").takeIf { it.exists() } ?: File("testdata/aircraft.json")
        val parsed = WeGlideAircraftList.parse(file.readText())!!
        assertTrue("expected hundreds of aircraft, got ${parsed.size}", parsed.size > 500)
        assertEquals(parsed.size, parsed.map { it.id }.toSet().size)
        assertTrue(AircraftSearch.search(parsed, "Stemme S10").isNotEmpty())
    }
}
