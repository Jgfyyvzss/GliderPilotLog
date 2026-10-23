package io.github.jgfyyvzss.soarlog.ui

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

class ColonTransformationTest {
    @Test fun colonAppearsOnceDigitsOverflowIntoHours() {
        assertEquals("5", ColonTransformation.display("5"))
        assertEquals("51", ColonTransformation.display("51"))
        assertEquals("3:24", ColonTransformation.display("324"))
        assertEquals("13:05", ColonTransformation.display("1305"))
        assertEquals("1234:56", ColonTransformation.display("123456"))
    }

    @Test fun typedColonsAndDecimalsAreLeftAlone() {
        assertEquals("1:75", ColonTransformation.display("1:75"))
        assertEquals("1234.9", ColonTransformation.display("1234.9"))
    }

    @Test fun cursorPositionsMapAcrossTheColon() {
        val t = ColonTransformation.filter(AnnotatedString("1305"))
        assertEquals("13:05", t.text.text)
        assertEquals(2, t.offsetMapping.originalToTransformed(2))
        assertEquals(4, t.offsetMapping.originalToTransformed(3))
        assertEquals(5, t.offsetMapping.originalToTransformed(4))
        assertEquals(2, t.offsetMapping.transformedToOriginal(3))
        assertEquals(4, t.offsetMapping.transformedToOriginal(5))
    }
}
