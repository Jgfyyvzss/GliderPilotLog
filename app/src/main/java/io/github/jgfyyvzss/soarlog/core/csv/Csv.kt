package io.github.jgfyyvzss.soarlog.core.csv

import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.msg
import java.util.Locale

/** A minimal RFC 4180 reader and writer: comma delimiter, quoted fields with doubled quotes, CRLF or LF. */
object Csv {
    const val BOM = "\uFEFF"

    /** Parses the whole text into records. A leading byte order mark is dropped. */
    fun parse(text: String): List<List<String>> {
        val s = text.removePrefix(BOM)
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        fun endField() { record.add(field.toString()); field.setLength(0) }
        fun endRecord() { endField(); records.add(record); record = mutableListOf() }
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                when {
                    c == '"' && i + 1 < s.length && s[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> quoted = false
                    else -> field.append(c)
                }
            } else {
                when (c) {
                    '"' -> quoted = true
                    ',' -> endField()
                    '\r' -> { if (i + 1 < s.length && s[i + 1] == '\n') i++; endRecord() }
                    '\n' -> endRecord()
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || record.isNotEmpty()) endRecord()
        return records
    }

    /** One line, quoting fields that contain a comma, quote or line break. */
    fun line(fields: List<String>): String = fields.joinToString(",") { f ->
        if (f.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + f.replace("\"", "\"\"") + "\"" else f
    }

    /** A whole file with a byte order mark, so spreadsheets read accented names correctly, and CRLF line ends. */
    fun file(header: List<String>, rows: List<List<String>>): String =
        BOM + (listOf(header) + rows).joinToString("") { line(it) + "\r\n" }

    fun headerKey(name: String): String = name.trim().lowercase(Locale.ROOT)
}

/** One problem in an imported file, named by row and column. The header is row 1. Column names stay as in the file. */
data class CsvError(val row: Int, val column: String?, val found: String?, val expected: Msg) {
    /** For example "Row 14, date: found 18/10/26, expected YYYY-MM-DD". */
    val message: Msg
        get() = when {
            column == null -> msg(MsgKey.CSV_ERROR_ROW, row, expected)
            found == null -> msg(MsgKey.CSV_ERROR_COLUMN, row, column, expected)
            else -> msg(MsgKey.CSV_ERROR_FOUND, row, column, if (found.isBlank()) msg(MsgKey.CSV_BLANK) else found, expected)
        }
}

/** A parsed file with its header looked up by name, ignoring case and surrounding spaces. Blank rows are skipped. */
class CsvTable private constructor(val columns: Map<String, Int>, val rows: List<CsvRow>, val unknownColumns: List<String>) {
    fun has(column: String) = column in columns

    companion object {
        fun read(text: String, known: Set<String>): CsvTable? {
            val records = Csv.parse(text)
            if (records.isEmpty()) return null
            val header = records.first().map(Csv::headerKey)
            val columns = header.withIndex().filter { it.value.isNotEmpty() }.associate { it.value to it.index }
            val rows = records.drop(1).mapIndexedNotNull { i, cells ->
                if (cells.all { it.isBlank() }) null else CsvRow(i + 2, cells, columns)
            }
            return CsvTable(columns, rows, header.filter { it.isNotEmpty() && it !in known })
        }
    }
}

class CsvRow(val number: Int, private val cells: List<String>, private val columns: Map<String, Int>) {
    /** The trimmed cell, or "" when the column is absent or the row is short. */
    operator fun get(column: String): String = columns[column]?.let { cells.getOrNull(it) }?.trim() ?: ""
}
