package app.photofinder.core.ml

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ExifOrientationTest {
    /** Minimal JPEG: SOI, APP0, APP1 with a one-entry IFD0 holding the orientation, SOS. */
    private fun jpegWithOrientation(
        orientation: Int,
        bigEndian: Boolean,
    ): ByteArray {
        fun u16(v: Int) = if (bigEndian) listOf(v shr 8, v and 0xFF) else listOf(v and 0xFF, v shr 8)

        fun u32(v: Int) = if (bigEndian) u16(v shr 16) + u16(v and 0xFFFF) else u16(v and 0xFFFF) + u16(v shr 16)

        val tiff =
            (if (bigEndian) listOf(0x4D, 0x4D) else listOf(0x49, 0x49)) + u16(42) + u32(8) +
                u16(1) + u16(0x0112) + u16(3) + u32(1) + u16(orientation) + u16(0) + u32(0)
        val exif = "Exif".encodeToByteArray().map { it.toInt() } + listOf(0, 0) + tiff
        val app0 = listOf(0xFF, 0xE0, 0, 4, 0x4A, 0x46)
        val app1 = listOf(0xFF, 0xE1, (exif.size + 2) shr 8, (exif.size + 2) and 0xFF) + exif
        return (listOf(0xFF, 0xD8) + app0 + app1 + listOf(0xFF, 0xDA, 0, 2)).map { it.toByte() }.toByteArray()
    }

    @Test
    fun readsOrientationInBothByteOrders() {
        assertEquals(6, ExifOrientation.read(jpegWithOrientation(6, bigEndian = true)))
        assertEquals(8, ExifOrientation.read(jpegWithOrientation(8, bigEndian = false)))
    }

    @Test
    fun fallsBackToNormalForNonJpegAndGarbage() {
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.read(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        assertEquals(
            ExifOrientation.NORMAL,
            ExifOrientation.read(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())),
        )
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.read(ByteArray(0)))
    }

    @Test
    fun orientation6RotatesClockwise() {
        // 3x2 image; after rotating 90 degrees clockwise the bottom-left source pixel is the top-left one.
        val rotated = coordImage(3, 2).applyExifOrientation(6)
        assertEquals(2, rotated.width)
        assertEquals(3, rotated.height)
        assertEquals(listOf(0, 1, 7), rotated.pixel(0, 0))
        assertEquals(listOf(0, 0, 7), rotated.pixel(1, 0))
        assertEquals(listOf(2, 0, 7), rotated.pixel(1, 2))
    }

    @Test
    fun inverseOrientationsRestoreTheImage() {
        val image = coordImage(4, 3)
        val inverses = mapOf(2 to 2, 3 to 3, 4 to 4, 5 to 5, 6 to 8, 7 to 7, 8 to 6)
        for ((orientation, inverse) in inverses) {
            val restored = image.applyExifOrientation(orientation).applyExifOrientation(inverse)
            assertContentEquals(image.pixels, restored.pixels, "orientation $orientation")
        }
    }
}
