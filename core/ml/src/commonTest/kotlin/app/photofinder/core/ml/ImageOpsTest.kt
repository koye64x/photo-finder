package app.photofinder.core.ml

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Image whose pixel (x, y) is (x, y, 7) so positions can be checked after transforms. */
internal fun coordImage(
    width: Int,
    height: Int,
): RgbImage {
    val pixels = ByteArray(width * height * 3)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val i = (y * width + x) * 3
            pixels[i] = x.toByte()
            pixels[i + 1] = y.toByte()
            pixels[i + 2] = 7
        }
    }
    return RgbImage(width, height, pixels)
}

internal fun solidImage(
    width: Int,
    height: Int,
    r: Int,
    g: Int,
    b: Int,
): RgbImage {
    val pixels = ByteArray(width * height * 3)
    for (i in 0 until width * height) {
        pixels[i * 3] = r.toByte()
        pixels[i * 3 + 1] = g.toByte()
        pixels[i * 3 + 2] = b.toByte()
    }
    return RgbImage(width, height, pixels)
}

internal fun RgbImage.pixel(
    x: Int,
    y: Int,
): List<Int> = (0 until 3).map { pixels[(y * width + x) * 3 + it].toInt() and 0xFF }

class ImageOpsTest {
    @Test
    fun scaledToFitKeepsAspectAndNeverUpscales() {
        val big = solidImage(4000, 3000, 10, 20, 30).scaledToFit(1024)
        assertEquals(1024, big.width)
        assertEquals(768, big.height)
        assertEquals(listOf(10, 20, 30), big.pixel(500, 400))

        val small = solidImage(100, 50, 1, 2, 3)
        assertTrue(small.scaledToFit(1024) === small)
    }

    @Test
    fun letterboxPlacesImageTopLeftAndPadsBlack() {
        val boxed = solidImage(200, 100, 50, 60, 70).letterbox(64)
        assertEquals(64, boxed.image.width)
        assertEquals(0.32f, boxed.scale)
        assertEquals(listOf(50, 60, 70), boxed.image.pixel(10, 10))
        assertEquals(listOf(0, 0, 0), boxed.image.pixel(10, 40))
    }

    @Test
    fun toChwConvertsChannelOrderAndNormalizes() {
        val image = solidImage(2, 1, 10, 20, 30)

        val rgb = image.toChw(ChannelOrder.RGB, mean = 10f, std = 2f)
        assertContentEquals(floatArrayOf(0f, 0f, 5f, 5f, 10f, 10f), rgb)

        val bgr = image.toChw(ChannelOrder.BGR, mean = 0f, std = 1f)
        assertContentEquals(floatArrayOf(30f, 30f, 20f, 20f, 10f, 10f), bgr)
    }

    @Test
    fun affineInverseUndoesTransform() {
        val t = AffineTransform(0.8f, -0.6f, 5f, 0.6f, 0.8f, -3f)
        val p = Point(12f, 34f)
        val back = t.inverse().apply(t.apply(p))
        assertTrue(abs(back.x - p.x) < 1e-4f && abs(back.y - p.y) < 1e-4f)
    }

    @Test
    fun warpAffineTranslatesPixelsAndFillsOutsideBlack() {
        val image = coordImage(10, 10)
        val shifted = image.warpAffine(AffineTransform(1f, 0f, -2f, 0f, 1f, -3f), 10, 10)
        assertEquals(listOf(2, 3, 7), shifted.pixel(0, 0))
        assertEquals(listOf(9, 9, 7), shifted.pixel(7, 6))
        assertEquals(listOf(0, 0, 0), shifted.pixel(9, 9))
    }
}
