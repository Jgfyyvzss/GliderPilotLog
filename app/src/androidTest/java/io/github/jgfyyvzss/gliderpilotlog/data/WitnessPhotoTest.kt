package io.github.jgfyyvzss.gliderpilotlog.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jgfyyvzss.gliderpilotlog.core.witness.WitnessPhotoRules
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The witness photo on a real Android image stack: a camera-style JPEG with a location and a sideways orientation
 * comes out upright, 200 pixels on its longest side, within 25 KB, with no location data, and the original is gone.
 */
@RunWith(AndroidJUnit4::class)
class WitnessPhotoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A landscape test picture, with a red band at the top so its orientation can be checked after turning. */
    private fun cameraFile(file: File, orientation: Int) {
        val bmp = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        for (y in 0 until 800) for (x in 0 until 1200 step 4) {
            val c = if (y < 100) Color.RED else Color.rgb(x * 255 / 1200, y * 255 / 800, 128)
            for (dx in 0 until 4) bmp.setPixel(x + dx, y, c)
        }
        file.parentFile?.mkdirs()
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        ExifInterface(file.path).apply {
            setLatLong(-36.7347, 147.1712)
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        assertNotNull("the test file has a location", ExifInterface(file.path).latLong)
    }

    @Test fun aSidewaysPhotoComesOutUprightSmallAndWithoutLocation() {
        val source = File(context.cacheDir, "witness-test/sideways.jpg")
        cameraFile(source, ExifInterface.ORIENTATION_ROTATE_90)
        val bytes = WitnessPhoto.shrink(source)!!

        assertTrue("${bytes.size} bytes", bytes.size <= WitnessPhotoRules.MAX_BYTES)
        val out = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        // 1200 x 800 shrinks to 200 x 133, and a quarter turn makes it 133 x 200.
        assertEquals(133, out.width)
        assertEquals(200, out.height)
        // Turned clockwise, the red band that was along the top is now down the right-hand side.
        assertEquals(Color.RED, nearest(out.getPixel(out.width - 3, out.height / 2)))
        assertFalse(Color.RED == nearest(out.getPixel(2, out.height / 2)))

        val exif = ExifInterface(ByteArrayInputStream(bytes))
        assertNull("no location", exif.latLong)
        assertEquals(ExifInterface.ORIENTATION_UNDEFINED, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
        source.delete()
    }

    @Test fun processingDeletesTheCameraFile() = runTest {
        val photo = WitnessPhoto(context)
        photo.captureUri() // creates the folder and clears any earlier file
        val capture = File(File(context.cacheDir, "witness"), "capture.jpg")
        cameraFile(capture, ExifInterface.ORIENTATION_NORMAL)
        val bytes = photo.process()
        assertNotNull(bytes)
        assertFalse("the original is deleted", capture.exists())
        assertNull("nothing left to process", photo.process())
    }

    /** Red when the pixel is clearly red, so JPEG noise does not matter. */
    private fun nearest(c: Int): Int =
        if (Color.red(c) > 180 && Color.green(c) < 90 && Color.blue(c) < 90) Color.RED else c
}
