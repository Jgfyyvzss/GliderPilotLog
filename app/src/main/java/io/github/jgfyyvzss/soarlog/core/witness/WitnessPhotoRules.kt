package io.github.jgfyyvzss.soarlog.core.witness

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How a camera photo becomes a stored witness photo (docs/Witness_Sign-Off.md): turned upright, scaled to
 * [MAX_SIDE] pixels on its longest side, and re-encoded as JPEG, which drops location and other metadata.
 */
object WitnessPhotoRules {
    const val MAX_SIDE = 200
    const val QUALITY = 70
    /** The stored photo's size limit from the build-step criteria; lower qualities are tried if it is exceeded. */
    const val MAX_BYTES = 25 * 1024
    val FALLBACK_QUALITIES = listOf(QUALITY, 55, 40)

    /** The scaled size, keeping the shape; a photo already small enough keeps its size. */
    fun scaledSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val longest = max(width, height)
        if (longest <= maxSide) return width to height
        val f = maxSide.toDouble() / longest
        return max(1, (width * f).roundToInt()) to max(1, (height * f).roundToInt())
    }

    /**
     * The largest power-of-two sample size that still decodes at least [maxSide] pixels on the longest side, so a
     * 12-megapixel photo is never held full size in memory.
     */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    /**
     * What to do to the pixels for an EXIF orientation value (1 to 8): rotate clockwise by [Transform.degrees], then
     * mirror left to right when [Transform.mirror]. Unknown values leave the photo as it is.
     */
    data class Transform(val degrees: Int, val mirror: Boolean) {
        /** Whether width and height swap, which is the case for a quarter turn. */
        val swapsSides: Boolean get() = degrees % 180 != 0
    }

    fun transform(exifOrientation: Int): Transform = when (exifOrientation) {
        2 -> Transform(0, true)
        3 -> Transform(180, false)
        4 -> Transform(180, true)
        5 -> Transform(90, true)
        6 -> Transform(90, false)
        7 -> Transform(270, true)
        8 -> Transform(270, false)
        else -> Transform(0, false)
    }
}
