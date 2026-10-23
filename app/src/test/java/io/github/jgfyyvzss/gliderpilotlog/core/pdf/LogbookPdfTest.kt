package io.github.jgfyyvzss.gliderpilotlog.core.pdf

import io.github.jgfyyvzss.gliderpilotlog.core.MsgKey
import io.github.jgfyyvzss.gliderpilotlog.core.Role
import io.github.jgfyyvzss.gliderpilotlog.core.RoleTotal
import io.github.jgfyyvzss.gliderpilotlog.core.Totals
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogbookPdfTest {
    private fun asset(name: String): String =
        listOf(File("src/main/assets/pdf-templates"), File("app/src/main/assets/pdf-templates")).first { it.isDirectory }.resolve(name).readText()

    private fun template(name: String) = (LogbookTemplate.parse(asset(name)) as TemplateRead.Ok).template

    private fun flight(n: Int, role: Role, minutes: Int) = PdfFlight(
        n, LocalDate.of(2026, 1, 1).plusDays(n.toLong()), "FQM", "Hornet", "YMBT", "MBGC", "winch", "Winch", role, "Pat",
        minutes, null, null, null, null,
    )

    private fun options(instructionPic: Boolean = false, balances: Map<Role, RoleTotal>? = null) =
        PdfOptions(instructionPic, recordTimes = false, recordLandingPlace = false, openingBalances = balances)

    @Test fun builtInTemplatesParse() {
        listOf("simple.json", "compact.json", "gfa.json").forEach { name ->
            assertTrue(name, LogbookTemplate.parse(asset(name)) is TemplateRead.Ok)
        }
        assertEquals(20, template("gfa.json").rowsPerPage)
        assertEquals(297f to 210f, template("gfa.json").page.sizeMm())
    }

    @Test fun gfaSignatureColumnPrintsTheWitness() {
        val col = template("gfa.json").columns.last()
        assertEquals(Field.WITNESS, col.field)
        assertEquals(listOf("Trainers", "Signature"), col.title)
        assertFalse("not a time column, so never totalled", col.totals)
    }

    @Test fun badTemplatesAreRefusedWithTheirProblem() {
        assertEquals(MsgKey.PDF_TEMPLATE_UNREADABLE, (LogbookTemplate.parse("{}") as TemplateRead.Refused).message.key)
        assertEquals(MsgKey.PDF_TEMPLATE_UNREADABLE, (LogbookTemplate.parse("""{"format":"other","name":"x","columns":[{"field":"date"}]}""") as TemplateRead.Refused).message.key)
        assertEquals(MsgKey.PDF_TEMPLATE_NEWER, (LogbookTemplate.parse("""{"format":"soarlogs-pdf-template","version":2,"name":"x","columns":[{"field":"date"}]}""") as TemplateRead.Refused).message.key)
        val noRoles = LogbookTemplate.parse("""{"format":"soarlogs-pdf-template","name":"x","columns":[{"field":"time"}]}""") as TemplateRead.Refused
        assertEquals(listOf<Any?>("roles"), noRoles.message.args)
        val badPage = LogbookTemplate.parse("""{"format":"soarlogs-pdf-template","name":"x","page":{"size":"B9"},"columns":[{"field":"date"}]}""") as TemplateRead.Refused
        assertEquals(listOf<Any?>("page"), badPage.message.args)
    }

    @Test fun optionalColumnsFollowTheirSettings() {
        val t = template("simple.json")
        val off = LogbookLayout(t, options())
        assertTrue(off.columns.none { it.field == Field.LAUNCH_TIME || it.field == Field.LANDING_PLACE })
        val on = LogbookLayout(t, PdfOptions(false, recordTimes = true, recordLandingPlace = true, openingBalances = null))
        assertTrue(on.columns.any { it.field == Field.LAUNCH_TIME } && on.columns.any { it.field == Field.LANDING_PLACE })
    }

    @Test fun pagesCarryTotalsForwardAndMatchTheOnScreenTotals() {
        val t = template("compact.json").copy(rowsPerPage = 3)
        val flights = (1..7).map { flight(it, if (it % 2 == 0) Role.STUDENT else Role.PIC, it * 10) }
        val layout = LogbookLayout(t, options())
        val pages = layout.pages(flights.asSequence()).toList()
        val dur = layout.columns.indexOfFirst { it.field == Field.DURATION }

        assertEquals(3, pages.size)
        assertEquals(3, layout.pageCount(7))
        assertEquals(listOf(3, 3, 1), pages.map { it.flights.size })
        assertEquals(0, pages[0].broughtForward.minutes[dur])
        assertEquals(10 + 20 + 30, pages[0].pageTotal.minutes[dur])
        pages.zipWithNext().forEach { (a, b) -> assertEquals(a.carriedForward, b.broughtForward) }
        // The last page's grand total equals the Combined figure the Totals screen shows for the same flights.
        val byRole = flights.groupBy { it.role }.mapValues { (_, fs) -> RoleTotal(fs.sumOf { it.durationMinutes }, fs.size) }
        val combined = Totals.table(byRole, emptyMap(), filterActive = true, instructionCountsAsPic = false).combined
        assertEquals(combined.minutes, pages.last().carriedForward.minutes[dur])
        assertEquals(combined.flights, pages.last().carriedForward.flights)
    }

    @Test fun unfilteredExportsBringForwardTheOpeningBalances() {
        val balances = mapOf(Role.PIC to RoleTotal(6000, 300), Role.STUDENT to RoleTotal(1200, 150))
        val layout = LogbookLayout(template("gfa.json"), options(balances = balances))
        val dual = layout.columns.indexOfFirst { it.title == listOf("Dual/Inst.") }
        val inCom = layout.columns.indexOfFirst { it.title == listOf("In Com.") }
        assertEquals(1200, layout.opening.minutes[dual])
        assertEquals(6000, layout.opening.minutes[inCom])
        assertEquals(450, layout.opening.flights)
        assertEquals(0, LogbookLayout(template("gfa.json"), options(balances = null)).opening.flights)
    }

    @Test fun gfaRolesFollowTheInstructionSetting() {
        val t = template("gfa.json")
        val flights = listOf(flight(1, Role.PIC, 60), flight(2, Role.STUDENT, 30), flight(3, Role.INSTRUCTOR, 20), flight(4, Role.COACH, 10), flight(5, Role.MUTUAL, 5))
        fun totals(instructionPic: Boolean): Pair<Int, Int> {
            val layout = LogbookLayout(t, options(instructionPic))
            val page = layout.pages(flights.asSequence()).single()
            val dual = layout.columns.indexOfFirst { it.title == listOf("Dual/Inst.") }
            val inCom = layout.columns.indexOfFirst { it.title == listOf("In Com.") }
            return page.pageTotal.minutes[dual] to page.pageTotal.minutes[inCom]
        }
        // Setting off: Instructor and Coach with Student in Dual/Inst.; PIC and Mutual in In Com.
        assertEquals(30 + 20 + 10 to 60 + 5, totals(false))
        // Setting on: Instructor and Coach move to In Com.
        assertEquals(30 to 60 + 5 + 20 + 10, totals(true))
    }

    @Test fun anEmptyExportStillHasOnePage() {
        assertEquals(1, LogbookLayout(template("compact.json"), options()).pageCount(0))
    }
}
