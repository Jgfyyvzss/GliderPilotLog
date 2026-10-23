package io.github.jgfyyvzss.soarlog.core.weglide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The 201 reply, read as XCSoar reads it: the first element of an array. The values here are made up. */
class WeGlideUploadResponseTest {
    @Test fun readsTheFirstFlight() {
        val body = """[{"id":98765,"scoring_date":"2025-02-13","registration":"VH-FQM","competition_id":"FQM",
            "user":{"id":1,"name":"Test Pilot"},"aircraft":{"id":1234,"name":"Hornet","kind":"GL","sc_class":"CL"}},
            {"id":1}]"""
        assertEquals(UploadedFlight(98765, "2025-02-13", "VH-FQM", "FQM", "Test Pilot", "Hornet"), WeGlideUploadResponse.parse(body))
    }

    @Test fun missingFieldsAreNull() {
        assertEquals(UploadedFlight(5, null, null, null, null, null), WeGlideUploadResponse.parse("""[{"id":5,"competition_id":""}]"""))
    }

    @Test fun anythingElseIsUnreadable() {
        assertNull(WeGlideUploadResponse.parse("[]"))
        assertNull(WeGlideUploadResponse.parse("""{"id":5}"""))
        assertNull(WeGlideUploadResponse.parse("<html>"))
    }
}
