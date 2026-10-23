package io.github.jgfyyvzss.soarlog.data.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.res.ResourcesCompat
import io.github.jgfyyvzss.soarlog.R
import io.github.jgfyyvzss.soarlog.core.HoursDisplay
import io.github.jgfyyvzss.soarlog.core.HoursFormat
import io.github.jgfyyvzss.soarlog.core.Role
import io.github.jgfyyvzss.soarlog.core.pdf.ColumnSpec
import io.github.jgfyyvzss.soarlog.core.pdf.Field
import io.github.jgfyyvzss.soarlog.core.pdf.LogbookLayout
import io.github.jgfyyvzss.soarlog.core.pdf.LogbookPage
import io.github.jgfyyvzss.soarlog.core.pdf.PageTotals
import io.github.jgfyyvzss.soarlog.core.pdf.PdfFlight
import java.io.OutputStream
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Every piece of text the PDF prints, already in the pilot's language, so this writer holds no wording of its own. */
class PdfTexts(
    val title: String,
    /** The pilot's name and identifiers, or blank. */
    val pilotLine: String,
    val headings: (Field) -> List<String>,
    val hours: String,
    val minutes: String,
    val broughtForward: String,
    val pageTotal: String,
    val carriedForward: String,
    val flights: (Int) -> String,
    /** The filter in one line, or null for an unfiltered export. */
    val filterSummary: String?,
    /** Shown with the filter summary: the totals cover the exported flights only. */
    val filteredNote: String,
    val pageOf: (Int, Int) -> String,
    val generated: String,
    val role: (Role) -> String,
    val launchType: (id: String, storedName: String) -> String,
    val date: (LocalDate) -> String,
    /** The witness's name and, when given, number, as the Witness column prints them. */
    val witness: (name: String, identifier: String?) -> String,
)

/**
 * Draws a logbook PDF with Android's PdfDocument and the bundled Roboto font. Flights are read in batches and each
 * page is drawn as soon as it is full. PdfDocument itself keeps the finished pages until [write] hands them to the
 * stream; they are text and lines, so even a long logbook stays small.
 */
class LogbookPdfWriter(private val context: Context) {
    private val regular: Typeface = ResourcesCompat.getFont(context, R.font.roboto_regular) ?: Typeface.DEFAULT
    private val bold: Typeface = ResourcesCompat.getFont(context, R.font.roboto_bold) ?: Typeface.DEFAULT_BOLD

    /** Writes every page to [out]; [onProgress] gets pages done and the page count. Cancelling stops between pages. */
    suspend fun write(
        layout: LogbookLayout,
        flights: Flow<PdfFlight>,
        flightCount: Int,
        display: HoursDisplay,
        texts: PdfTexts,
        out: OutputStream,
        onProgress: (Int, Int) -> Unit,
    ) = withContext(Dispatchers.Default) {
        val doc = PdfDocument()
        try {
            val pages = layout.pageCount(flightCount)
            val buffer = ArrayList<PdfFlight>(layout.template.rowsPerPage)
            var forward = layout.opening
            var number = 0
            suspend fun flush() {
                currentCoroutineContext().ensureActive()
                val page = layout.page(++number, buffer.toList(), forward)
                draw(doc, layout, page, pages, display, texts)
                forward = page.carriedForward
                buffer.clear()
                onProgress(number, pages)
            }
            flights.collect { f ->
                buffer += f
                if (buffer.size == layout.template.rowsPerPage) flush()
            }
            if (buffer.isNotEmpty() || number == 0) flush()
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.IO) { doc.writeTo(out) }
        } finally {
            doc.close()
        }
    }

    private fun mm(v: Float) = v * 72f / 25.4f

    private fun draw(doc: PdfDocument, layout: LogbookLayout, page: LogbookPage, pages: Int, display: HoursDisplay, texts: PdfTexts) {
        val t = layout.template
        val (wMm, hMm) = t.page.sizeMm() ?: (210f to 297f)
        val info = PdfDocument.PageInfo.Builder(mm(wMm).toInt(), mm(hMm).toInt(), page.number).create()
        val p = doc.startPage(info)
        val c = p.canvas
        val fs = t.fontSize
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = regular; textSize = fs }
        val strong = Paint(text).apply { typeface = bold }
        val line = Paint().apply { strokeWidth = 0.4f; style = Paint.Style.STROKE }

        val left = mm(t.marginsMm.left)
        val right = mm(wMm - t.marginsMm.right)
        val top = mm(t.marginsMm.top)
        val bottom = mm(hMm - t.marginsMm.bottom)

        // Page header: the title and the pilot.
        var y = top + fs * 1.4f
        val title = t.header.title ?: texts.title
        if (title.isNotBlank()) c.drawText(title, left, y, Paint(strong).apply { textSize = fs * 1.4f })
        if (t.header.showPilot && texts.pilotLine.isNotBlank()) {
            c.drawText(fit(texts.pilotLine, text, (right - left) / 2), right - text.measureText(fit(texts.pilotLine, text, (right - left) / 2)), y, text)
        }
        y += fs

        // Column positions, sharing the width in proportion to each column's weight.
        val cols = layout.columns
        val weight = cols.sumOf { it.width.toDouble() }.toFloat()
        val xs = FloatArray(cols.size + 1).also { a ->
            a[0] = left
            cols.forEachIndexed { i, col -> a[i + 1] = a[i] + (right - left) * col.width / weight }
        }
        val split = cols.map { it.isTime && it.splitHm && display == HoursDisplay.HM }

        // Footer lines, then the table fills what is left.
        val footerLines = listOfNotNull(texts.filterSummary?.let { "$it · ${texts.filteredNote}" }, texts.generated + " · " + texts.pageOf(page.number, pages))
        val tableBottom = bottom - footerLines.size * fs * 1.4f - fs * 0.6f

        val headingLines = cols.maxOf { headingOf(it, texts).size }.coerceAtLeast(1)
        val hasSplit = split.any { it }
        val headTop = y
        val headHeight = headingLines * fs * 1.25f + fs * 0.6f + if (hasSplit) fs * 1.4f else 0f
        val bodyTop = headTop + headHeight
        val rows = t.rowsPerPage + 3 // brought forward, the flights, the page total, carried forward
        val rowH = minOf((tableBottom - bodyTop) / rows, fs * 2.4f)

        // Headings.
        cols.forEachIndexed { i, col ->
            if (col.field == Field.GAP) return@forEachIndexed
            headingOf(col, texts).forEachIndexed { n, h ->
                cell(c, h, xs[i], xs[i + 1], headTop + fs * (1.1f + n * 1.25f), strong, "center")
            }
            if (split[i]) {
                val mid = xs[i] + (xs[i + 1] - xs[i]) * 0.6f
                val sy = bodyTop - fs * 0.45f
                cell(c, texts.hours, xs[i], mid, sy, text, "center")
                cell(c, texts.minutes, mid, xs[i + 1], sy, text, "center")
            }
        }

        // Rows: brought forward, the flights, then the page total and carried forward.
        fun baseline(row: Int) = bodyTop + rowH * row + rowH * 0.68f
        totalsRow(c, cols, xs, split, baseline(0), page.broughtForward, t.labels.broughtForward ?: texts.broughtForward, texts, display, strong)
        page.flights.forEachIndexed { r, f ->
            cols.forEachIndexed { i, col ->
                if (col.field == Field.GAP) return@forEachIndexed
                val minutes = layout.minutes(col, f)
                if (split[i]) {
                    if (minutes != null) {
                        val mid = xs[i] + (xs[i + 1] - xs[i]) * 0.6f
                        cell(c, (minutes / 60).toString(), xs[i], mid, baseline(r + 1), text, "end")
                        cell(c, "%02d".format(minutes % 60), mid, xs[i + 1], baseline(r + 1), text, "end")
                    }
                } else {
                    cell(c, valueOf(col, f, minutes, display, texts), xs[i], xs[i + 1], baseline(r + 1), text, col.align)
                }
            }
        }
        val totalRow = t.rowsPerPage + 1
        totalsRow(c, cols, xs, split, baseline(totalRow), page.pageTotal, t.labels.page ?: texts.pageTotal, texts, display, strong)
        totalsRow(c, cols, xs, split, baseline(totalRow + 1), page.carriedForward, t.labels.carriedForward ?: texts.carriedForward, texts, display, strong)

        // Grid: one box per run of columns between gaps, ruled for every row.
        val tableEnd = bodyTop + rowH * rows
        segments(cols).forEach { (from, to) ->
            c.drawRect(xs[from], headTop, xs[to + 1], tableEnd, line)
            for (r in 0..rows) c.drawLine(xs[from], bodyTop + rowH * r, xs[to + 1], bodyTop + rowH * r, line)
            for (i in from + 1..to) c.drawLine(xs[i], headTop, xs[i], tableEnd, line)
            for (i in from..to) if (split[i]) {
                val mid = xs[i] + (xs[i + 1] - xs[i]) * 0.6f
                c.drawLine(mid, bodyTop - fs * 1.4f, mid, tableEnd, line)
                c.drawLine(xs[i], bodyTop - fs * 1.4f, xs[i + 1], bodyTop - fs * 1.4f, line)
            }
        }

        // Footer.
        footerLines.forEachIndexed { n, s -> c.drawText(fit(s, text, right - left), left, tableBottom + fs * (1.6f + n * 1.4f), text) }
        doc.finishPage(p)
    }

    /** The label sits in the columns before the first total; the totals sit under their columns. */
    private fun totalsRow(
        c: Canvas, cols: List<ColumnSpec>, xs: FloatArray, split: List<Boolean>, y: Float, totals: PageTotals, label: String,
        texts: PdfTexts, display: HoursDisplay, paint: Paint,
    ) {
        val firstTotal = cols.indexOfFirst { it.totals }.let { if (it < 0) cols.size else it }
        val labelEnd = cols.take(firstTotal).indexOfLast { it.field == Field.GAP }.let { if (it < 0) firstTotal else it }
        if (labelEnd > 0) cell(c, "$label · ${texts.flights(totals.flights)}", xs[0], xs[labelEnd], y, paint, "start")
        cols.forEachIndexed { i, col ->
            if (!col.totals) return@forEachIndexed
            val m = totals.minutes[i]
            if (split[i]) {
                val mid = xs[i] + (xs[i + 1] - xs[i]) * 0.6f
                cell(c, (m / 60).toString(), xs[i], mid, y, paint, "end")
                cell(c, "%02d".format(m % 60), mid, xs[i + 1], y, paint, "end")
            } else {
                cell(c, HoursFormat.format(m, display), xs[i], xs[i + 1], y, paint, "end")
            }
        }
    }

    private fun valueOf(col: ColumnSpec, f: PdfFlight, minutes: Int?, display: HoursDisplay, texts: PdfTexts): String = when (col.field) {
        Field.LOG_NO -> f.logNo.toString()
        Field.DATE -> texts.date(f.date)
        Field.GLIDER -> "${f.registration} ${f.gliderType}".trim()
        Field.REGISTRATION -> f.registration
        Field.GLIDER_TYPE -> f.gliderType
        Field.AIRFIELD -> f.airfield
        Field.PLACE -> listOfNotNull(f.airfield, f.club).joinToString(" ")
        Field.CLUB -> f.club.orEmpty()
        Field.LAUNCH_TYPE -> texts.launchType(f.launchTypeId, f.launchTypeName)
        Field.ROLE -> texts.role(f.role)
        Field.PIC -> f.pic
        Field.DURATION, Field.TIME -> minutes?.let { HoursFormat.format(it, display) }.orEmpty()
        Field.LAUNCH_TIME -> f.launchTime.orEmpty()
        Field.LANDING_TIME -> f.landingTime.orEmpty()
        Field.LANDING_PLACE -> f.landingPlace.orEmpty()
        Field.NOTES -> f.notes?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        Field.WITNESS -> f.witnessName?.let { texts.witness(it, f.witnessIdentifier) }.orEmpty()
        Field.BLANK, Field.GAP -> ""
    }

    private fun headingOf(col: ColumnSpec, texts: PdfTexts): List<String> =
        if (col.field == Field.GAP) emptyList() else col.title ?: texts.headings(col.field)

    /** Runs of printed columns between gap columns, as first and last index. */
    private fun segments(cols: List<ColumnSpec>): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var start = -1
        cols.forEachIndexed { i, col ->
            if (col.field == Field.GAP) {
                if (start >= 0) out += start to i - 1
                start = -1
            } else if (start < 0) start = i
        }
        if (start >= 0) out += start to cols.lastIndex
        return out
    }

    /** Text in a cell, cut with an ellipsis to fit, aligned start, centre or end. */
    private fun cell(c: Canvas, s: String, x0: Float, x1: Float, y: Float, paint: Paint, align: String) {
        if (s.isEmpty()) return
        val pad = paint.textSize * 0.3f
        val shown = fit(s, paint, x1 - x0 - 2 * pad)
        val w = paint.measureText(shown)
        val x = when (align) {
            "end" -> x1 - pad - w
            "center" -> x0 + (x1 - x0 - w) / 2
            else -> x0 + pad
        }
        c.drawText(shown, x, y, paint)
    }

    private fun fit(s: String, paint: Paint, width: Float): String {
        if (width <= 0f) return ""
        if (paint.measureText(s) <= width) return s
        var end = s.length
        while (end > 0 && paint.measureText(s, 0, end) + paint.measureText("…") > width) end--
        return if (end == 0) "" else s.substring(0, end).trimEnd() + "…"
    }
}

