package io.github.jgfyyvzss.gliderpilotlog.core.witness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WitnessPhotoRulesTest {
    @Test fun scalesTheLongestSideTo200AndKeepsTheShape() {
        assertEquals(200 to 150, WitnessPhotoRules.scaledSize(4000, 3000))
        assertEquals(150 to 200, WitnessPhotoRules.scaledSize(3000, 4000))
        assertEquals(200 to 200, WitnessPhotoRules.scaledSize(1080, 1080))
        assertEquals("a small photo keeps its size", 120 to 80, WitnessPhotoRules.scaledSize(120, 80))
        assertEquals("never zero", 200 to 1, WitnessPhotoRules.scaledSize(4000, 5))
    }

    @Test fun decodesAtTheLargestSampleThatStillCoversTheTarget() {
        assertEquals(16, WitnessPhotoRules.sampleSize(4000, 3000)) // 4000 / 16 = 250, 4000 / 32 = 125
        assertEquals(1, WitnessPhotoRules.sampleSize(300, 200))
        assertEquals(1, WitnessPhotoRules.sampleSize(120, 80))
    }

    @Test fun exifOrientationsTurnThePhotoUpright() {
        assertEquals(WitnessPhotoRules.Transform(0, false), WitnessPhotoRules.transform(1))
        assertEquals(WitnessPhotoRules.Transform(90, false), WitnessPhotoRules.transform(6))
        assertEquals(WitnessPhotoRules.Transform(180, false), WitnessPhotoRules.transform(3))
        assertEquals(WitnessPhotoRules.Transform(270, false), WitnessPhotoRules.transform(8))
        assertEquals(WitnessPhotoRules.Transform(0, true), WitnessPhotoRules.transform(2))
        assertEquals(WitnessPhotoRules.Transform(180, true), WitnessPhotoRules.transform(4))
        assertEquals(WitnessPhotoRules.Transform(90, true), WitnessPhotoRules.transform(5))
        assertEquals(WitnessPhotoRules.Transform(270, true), WitnessPhotoRules.transform(7))
        assertEquals("unknown values change nothing", WitnessPhotoRules.Transform(0, false), WitnessPhotoRules.transform(0))
        assertTrue(WitnessPhotoRules.transform(6).swapsSides)
        assertFalse(WitnessPhotoRules.transform(3).swapsSides)
    }
}
