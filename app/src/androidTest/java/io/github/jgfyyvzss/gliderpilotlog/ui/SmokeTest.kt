package io.github.jgfyyvzss.gliderpilotlog.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jgfyyvzss.gliderpilotlog.MainActivity
import io.github.jgfyyvzss.gliderpilotlog.SoarlogsApp
import io.github.jgfyyvzss.gliderpilotlog.core.HoursDisplay
import io.github.jgfyyvzss.gliderpilotlog.core.Keys
import io.github.jgfyyvzss.gliderpilotlog.data.LogbookDatabase
import io.github.jgfyyvzss.gliderpilotlog.data.LogbookRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Appendix F: create a flight, filter, and check the totals. Uses an in-memory database. */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var db: LogbookDatabase
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun setUp() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SoarlogsApp
        db = LogbookDatabase.inMemory(app)
        SoarlogsApp.testDatabase = db
        val repo = LogbookRepository(db)
        repo.saveGlider(null, "FQM", "Hornet", archived = false)
        repo.saveAirfield(
            null, "YMBT", null, null, null, null, emptyList(), archived = false,
            defaultLaunchTypeId = Keys.seededLaunchTypeId("Winch"),
        )
        app.settings.setHoursDisplay(HoursDisplay.HM)
        app.settings.setInstructionCountsAsPic(false)
        app.filter.clear()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun tearDown() {
        scenario.close()
        db.close()
        SoarlogsApp.testDatabase = null
    }

    @Test fun createFlightFilterAndCheckTotals() {
        // Create a 1:30 PIC flight.
        compose.onNodeWithText("New flight", substring = true).performClick()
        compose.onNodeWithText("Glider").performClick()
        compose.onNodeWithText("FQM, Hornet").performClick()
        compose.onNodeWithText("Airfield").performClick()
        compose.onNodeWithText("YMBT").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Winch").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction() and hasText("Pilot in command")).performScrollTo().performTextReplacement("Pat Pilot")
        compose.onNode(hasSetTextAction() and hasText("Duration")).performScrollTo().performTextReplacement("130")
        compose.onNodeWithText("Save").performScrollTo().performClick()

        // The flight is listed.
        compose.onNodeWithText("Flights").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("YMBT · Winch · PIC · 1:30").fetchSemanticsNodes().isNotEmpty() }

        // Totals show it in PIC and Combined.
        compose.onNodeWithText("Totals").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1:30").fetchSemanticsNodes().size == 2 }

        // Filtering to Student flights leaves nothing.
        compose.onNodeWithText("Filter").performClick()
        // The Totals grid behind the sheet also shows "Student", so pick the clickable chip.
        compose.onNode(hasText("Student") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Apply").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1:30").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Filter active: only matching flights count, and opening balances are left out.").assertExists()
    }
}
