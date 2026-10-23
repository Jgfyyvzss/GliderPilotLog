package io.github.jgfyyvzss.soarlog.core.weglide

import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One entry in WeGlide's aircraft list. Kind and class are shown exactly as WeGlide returns them. */
data class WeGlideAircraft(val id: Long, val name: String, val kind: String?, val scClass: String?) {
    /** For example `ASK 21 · GL · CL`, so variants with the same name can be told apart. */
    val label: String get() = listOfNotNull(name, kind, scClass).joinToString(" · ")
}

object WeGlideAircraftList {
    /**
     * Reads the `GET /v1/aircraft` response, a JSON array. Entries without a numeric `id` and a string `name` are
     * skipped. Returns null when the text is not a JSON array at all.
     */
    fun parse(text: String): List<WeGlideAircraft>? {
        val array = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return null
        return array.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            WeGlideAircraft(id, name, o.string("kind"), o.string("sc_class"))
        }
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** Search from Appendix E: exact matches first, then starts-with, then contains, ties by name. */
object AircraftSearch {
    private val marks = Regex("\\p{M}+")
    private val notLetterOrDigit = Regex("[^\\p{L}\\p{N}]+")

    /** Lower-cased, accents removed, and everything except letters and digits removed, so `ASK-21` matches `ask21`. */
    fun normalise(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(marks, "").lowercase(Locale.ROOT).replace(notLetterOrDigit, "")

    fun search(list: List<WeGlideAircraft>, query: String): List<WeGlideAircraft> {
        val q = normalise(query)
        val byName = compareBy<WeGlideAircraft>({ it.name.lowercase(Locale.ROOT) }, { it.id })
        if (q.isEmpty()) return list.sortedWith(byName)
        return list.mapNotNull { a ->
            val n = normalise(a.name)
            val rank = when {
                n == q -> 0
                n.startsWith(q) -> 1
                n.contains(q) -> 2
                else -> return@mapNotNull null
            }
            rank to a
        }.sortedWith(compareBy<Pair<Int, WeGlideAircraft>> { it.first }.thenBy(byName) { it.second }).map { it.second }
    }
}
