package app.photofinder.core.ml

import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImageIoPhotoDecoderTest {
    private fun encode(
        width: Int,
        height: Int,
        rgb: Int,
        format: String,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, rgb)
        return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
    }

    @Test
    fun decodesPngPixelsAsRgb() =
        runTest {
            val image = ImageIoPhotoDecoder().decode(encode(30, 20, 0x102030, "png"), maxSide = 1024)
            assertEquals(30, image.width)
            assertEquals(20, image.height)
            assertEquals(listOf(0x10, 0x20, 0x30), image.pixel(5, 5))
        }

    @Test
    fun subsamplesLargeJpegWhileDecoding() =
        runTest {
            val image = ImageIoPhotoDecoder().decode(encode(3000, 1500, 0x808080, "jpg"), maxSide = 1024)
            // Integer factor 2: never below maxSide, at most about twice it.
            assertEquals(1500, image.width)
            assertEquals(750, image.height)
            assertTrue(image.pixel(100, 100).all { it in 120..136 })
        }

    @Test
    fun rejectsUnknownFormat() =
        runTest {
            assertFailsWith<IOException> { ImageIoPhotoDecoder().decode(byteArrayOf(1, 2, 3, 4), 1024) }
        }
}
