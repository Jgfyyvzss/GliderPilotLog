package io.github.jgfyyvzss.gliderpilotlog.core.pdf

import io.github.jgfyyvzss.gliderpilotlog.core.Msg
import io.github.jgfyyvzss.gliderpilotlog.core.MsgKey
import io.github.jgfyyvzss.gliderpilotlog.core.Role
import io.github.jgfyyvzss.gliderpilotlog.core.msg
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A logbook PDF template: page, margins, columns, totals and header, as a JSON file. The built-in templates ship in
 * the app's assets; a pilot can import more from files, and there is no in-app editor. Headings left out use the
 * app's own text in the pilot's language; a template can give its own, as GFA-style does to match the printed book.
 */
@Serializable
data class LogbookTemplate(
    val format: String = FORMAT,
    val version: Int = 1,
    val name: String,
    val page: PageSpec = PageSpec(),
    @SerialName("margins_mm") val marginsMm: Margins = Margins(),
    @SerialName("font_size") val fontSize: Float = 8f,
    @SerialName("rows_per_page") val rowsPerPage: Int = 30,
    val header: HeaderSpec = HeaderSpec(),
    val labels: TotalLabels = TotalLabels(),
    val columns: List<ColumnSpec>,
) {
    companion object {
        const val FORMAT = "soarlogs-pdf-template"
        private val json = Json { ignoreUnknownKeys = true }

        /** Reads and checks a template; the message names the first problem. */
        fun parse(text: String): TemplateRead {
            val t = runCatching { json.decodeFromString(serializer(), text) }.getOrElse {
                return TemplateRead.Refused(msg(MsgKey.PDF_TEMPLATE_UNREADABLE))
            }
            if (t.format != FORMAT) return TemplateRead.Refused(msg(MsgKey.PDF_TEMPLATE_UNREADABLE))
            if (t.version > 1) return TemplateRead.Refused(msg(MsgKey.PDF_TEMPLATE_NEWER))
            if (t.name.isBlank()) return TemplateRead.Refused(msg(MsgKey.PDF_TEMPLATE_PROBLEM, "name"))
            if (t.columns.isEmpty()) return TemplateRead.Refused(msg(MsgKey.PDF_TEMPLATE_PROBLEM, "columns"))
            if (t.rowsPerPage !in 1..200) return TemplateRead.Refused(msg(MsgKey.PDF_TEMPLATE_PROBLEM, "rows_per_page"))
            if (t.fontSize !in 4f..20f) return TemplateRead.Refused(msg(MsgKey.PDF_TEMPLATE_PROBLEM, "font_size"))
            val (w, h) = t.page.sizeMm() ?: return TemplateRead.Refused(msg(MsgKey.PDF_TEMPLATE_PROBLEM, "page"))
            if (t.marginsMm.left + t.marginsMm.right >= w || t.marginsMm.top + t.marginsMm.bottom >= h) {
                return TemplateRead.Refused(msg(MsgKey.PDF_TEMPLATE_PROBLEM, "margins_mm"))
            }
            t.columns.forEach { c ->
                if (c.width <= 0f) return TemplateRead.Refused(msg(MsgKey.PDF_TEMPLATE_PROBLEM, "width"))
                if (c.field == Field.TIME && c.roles.isEmpty()) return TemplateRead.Refused(msg(MsgKey.PDF_TEMPLATE_PROBLEM, "roles"))
            }
            return TemplateRead.Ok(t)
        }
    }
}

sealed interface TemplateRead {
    data class Ok(val template: LogbookTemplate) : TemplateRead
    data class Refused(val message: Msg) : TemplateRead
}

@Serializable
data class PageSpec(
    /** A4, A5, LETTER, or CUSTOM with [widthMm] and [heightMm]. */
    val size: String = "A4",
    val orientation: String = "portrait",
    @SerialName("width_mm") val widthMm: Float? = null,
    @SerialName("height_mm") val heightMm: Float? = null,
) {
    /** Width and height in millimetres, after orientation; null when unreadable. */
    fun sizeMm(): Pair<Float, Float>? {
        val (a, b) = when (size.uppercase()) {
            "A4" -> 210f to 297f
            "A5" -> 148f to 210f
            "LETTER" -> 215.9f to 279.4f
            "CUSTOM" -> (widthMm ?: return null) to (heightMm ?: return null)
            else -> return null
        }
        if (a < 50f || b < 50f || a > 1000f || b > 1000f) return null
        return when (orientation.lowercase()) {
            "portrait" -> minOf(a, b) to maxOf(a, b)
            "landscape" -> maxOf(a, b) to minOf(a, b)
            else -> null
        }
    }
}

@Serializable
data class Margins(val top: Float = 12f, val bottom: Float = 12f, val left: Float = 12f, val right: Float = 12f)

@Serializable
data class HeaderSpec(
    /** Printed at the top of each page; left out, the app's "Pilot logbook" in the pilot's language. */
    val title: String? = null,
    @SerialName("show_pilot") val showPilot: Boolean = true,
)

/** The three totals rows' labels; left out, the app's own text in the pilot's language. */
@Serializable
data class TotalLabels(
    @SerialName("brought_forward") val broughtForward: String? = null,
    val page: String? = null,
    @SerialName("carried_forward") val carriedForward: String? = null,
)

@Serializable
enum class Field {
    @SerialName("log_no") LOG_NO,
    @SerialName("date") DATE,
    /** Registration and type together, for example `FQM Hornet`. */
    @SerialName("glider") GLIDER,
    @SerialName("registration") REGISTRATION,
    @SerialName("glider_type") GLIDER_TYPE,
    @SerialName("airfield") AIRFIELD,
    /** Airfield, then the club when there is one, for example `YMBT MBGC`. */
    @SerialName("place") PLACE,
    @SerialName("club") CLUB,
    @SerialName("launch_type") LAUNCH_TYPE,
    @SerialName("role") ROLE,
    @SerialName("pic") PIC,
    /** Every flight's duration. */
    @SerialName("duration") DURATION,
    /** The duration only for the roles listed in [ColumnSpec.roles]. */
    @SerialName("time") TIME,
    /** Shown only when the Record launch and landing times Setting is on. */
    @SerialName("launch_time") LAUNCH_TIME,
    @SerialName("landing_time") LANDING_TIME,
    /** Shown only when the Record landing place Setting is on. */
    @SerialName("landing_place") LANDING_PLACE,
    @SerialName("notes") NOTES,
    /**
     * The witness's name and number, for a flight whose witness seal still matches and only while the Witness
     * sign-off Setting is on; otherwise empty, so it can be signed by hand like a [BLANK] signature column.
     */
    @SerialName("witness") WITNESS,
    /** An empty column, such as a signature column. */
    @SerialName("blank") BLANK,
    /** Space with no lines, such as the fold between the two pages of a spread. */
    @SerialName("gap") GAP,
}

/** Whether Instructor and Coach time goes in a [Field.TIME] column. */
@Serializable
enum class InstructionRule {
    /** Never: only the listed roles. */
    @SerialName("never") NEVER,
    /** When the Setting counts Instructor and Coach toward PIC. */
    @SerialName("when_pic") WHEN_PIC,
    /** When that Setting is off. */
    @SerialName("when_not_pic") WHEN_NOT_PIC,
    @SerialName("always") ALWAYS,
}

@Serializable
data class ColumnSpec(
    val field: Field,
    /** Relative width; the columns share the space between the margins in proportion. */
    val width: Float = 1f,
    /** Heading lines; left out, the app's own heading for the field. */
    val title: List<String>? = null,
    val align: String = "start",
    /** For [Field.TIME]: the roles whose duration goes here. */
    val roles: Set<Role> = emptySet(),
    /** For [Field.TIME]: whether Instructor and Coach time goes here too. */
    val instruction: InstructionRule = InstructionRule.NEVER,
    /** For [Field.DURATION] and [Field.TIME] with H:MM hours: separate Hrs and Min sub-columns, as in a printed book. */
    @SerialName("split_hm") val splitHm: Boolean = false,
    /** Whether the totals rows add this column up. Defaults to true for time columns. */
    val total: Boolean? = null,
) {
    // "this." is needed: in a getter, a bare `field` means the property's own backing field.
    val isTime: Boolean get() = this.field == Field.DURATION || this.field == Field.TIME
    val totals: Boolean get() = total ?: isTime

    /** The roles this column takes, given the Setting that counts Instructor and Coach toward PIC. */
    fun takes(role: Role, instructionCountsAsPic: Boolean): Boolean = when (field) {
        Field.DURATION -> true
        Field.TIME -> role in roles || (role == Role.INSTRUCTOR || role == Role.COACH) && when (instruction) {
            InstructionRule.NEVER -> false
            InstructionRule.ALWAYS -> true
            InstructionRule.WHEN_PIC -> instructionCountsAsPic
            InstructionRule.WHEN_NOT_PIC -> !instructionCountsAsPic
        }
        else -> false
    }
}
