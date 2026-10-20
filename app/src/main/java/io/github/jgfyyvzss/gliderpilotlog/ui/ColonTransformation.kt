package io.github.jgfyyvzss.gliderpilotlog.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Shows digits typed into an hours or time field with a colon before the last two once they overflow into hours,
 * so 324 reads 3:24 and 1305 reads 13:05. Only the display changes: the field's text stays as typed, and text the
 * pilot types with a colon or decimal point is shown unchanged.
 */
object ColonTransformation : VisualTransformation {
    /** What the field shows for [text]. */
    fun display(text: String): String {
        if (text.length < 3 || !text.all(Char::isDigit)) return text
        val split = text.length - 2
        return text.substring(0, split) + ":" + text.substring(split)
    }

    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        if (s.length < 3 || !s.all(Char::isDigit)) return TransformedText(text, OffsetMapping.Identity)
        val split = s.length - 2
        return TransformedText(
            AnnotatedString(display(s)),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = if (offset <= split) offset else offset + 1
                override fun transformedToOriginal(offset: Int) = if (offset <= split) offset else offset - 1
            },
        )
    }
}
