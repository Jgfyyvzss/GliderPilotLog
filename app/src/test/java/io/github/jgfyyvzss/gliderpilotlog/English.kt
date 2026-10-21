package io.github.jgfyyvzss.gliderpilotlog

import io.github.jgfyyvzss.gliderpilotlog.core.Msg
import io.github.jgfyyvzss.gliderpilotlog.core.MsgKey
import io.github.jgfyyvzss.gliderpilotlog.core.Role
import java.io.File
import java.time.LocalDate
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Renders messages in English from the app's own `strings_messages.xml` and `strings_common.xml`, the way Android's
 * getString would, so JVM tests can check the text pilots see. Dates are shown as ISO text to keep tests independent
 * of the machine's locale.
 */
object English {
    private val strings: Map<String, String> by lazy { load("strings_messages.xml") + load("strings_common.xml") }

    private fun valuesDir(): File =
        listOf(File("src/main/res/values"), File("app/src/main/res/values")).first { it.isDirectory }

    private fun load(name: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(valuesDir(), name))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to e.textContent.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
        }
    }

    fun template(key: MsgKey): String = strings.getValue("msg_" + key.name.lowercase())

    fun of(m: Msg): String {
        val args = m.args.map { a ->
            when (a) {
                is Msg -> of(a)
                is Role -> strings.getValue("role_" + a.name.lowercase())
                is LocalDate -> a.toString()
                null -> ""
                else -> a
            }
        }
        return String.format(Locale.ROOT, template(m.key), *args.toTypedArray())
    }
}

fun Msg.english(): String = English.of(this)
