package app.photofinder.core.ml

private const val BYTE_MASK = 0xFF
private const val JPEG_MARKER = 0xFF
private const val SOI = 0xD8
private const val APP1 = 0xE1
private const val SOS = 0xDA
private const val EOI = 0xD9
private const val TIFF_MAGIC = 42
private const val ORIENTATION_TAG = 0x0112
private const val TYPE_SHORT = 3
private const val EXIF_HEADER_SIZE = 6
private const val EXIF_SIGNATURE = "Exif"
private const val IFD_ENTRY_SIZE = 12
private const val ENTRY_TYPE_OFFSET = 2
private const val ENTRY_VALUE_OFFSET = 8
private const val IFD_OFFSET_POSITION = 4
private const val MARKER_SIZE = 2
private const val SEGMENT_HEADER_SIZE = 4
private const val U16_SIZE = 2
private const val BITS_IN_BYTE = 8
private const val BITS_IN_U16 = 16
private const val MAX_ORIENTATION = 8

/** EXIF orientation values (TIFF tag 0x0112); see [applyExifOrientation]. */
object ExifOrientation {
    const val NORMAL = 1

    /**
     * Reads the orientation from a JPEG's EXIF block. Returns [NORMAL] for other formats, missing or malformed
     * EXIF: a wrong rotation only costs recall, a crash would stop indexing.
     */
    fun read(bytes: ByteArray): Int =
        runCatching { findTiffHeader(bytes)?.let { readTiff(bytes, it) } }.getOrNull() ?: NORMAL

    /** Walks JPEG segments up to the image data and returns the start of the EXIF TIFF header, if any. */
    private fun findTiffHeader(bytes: ByteArray): Int? {
        var tiff: Int? = null
        var pos = MARKER_SIZE
        var scanning = bytes.u8(0) == JPEG_MARKER && bytes.u8(1) == SOI
        while (scanning && pos + SEGMENT_HEADER_SIZE <= bytes.size) {
            val marker = bytes.u8(pos + 1)
            when {
                bytes.u8(pos) != JPEG_MARKER || marker == SOS || marker == EOI -> {
                    scanning = false
                }

                marker == APP1 && isExif(bytes, pos + SEGMENT_HEADER_SIZE) -> {
                    tiff = pos + SEGMENT_HEADER_SIZE + EXIF_HEADER_SIZE
                    scanning = false
                }

                else -> {
                    pos += MARKER_SIZE + bytes.u16(pos + MARKER_SIZE, bigEndian = true)
                }
            }
        }
        return tiff
    }

    private fun isExif(
        bytes: ByteArray,
        pos: Int,
    ): Boolean =
        bytes.decodeToString(pos, pos + EXIF_SIGNATURE.length) == EXIF_SIGNATURE &&
            bytes[pos + EXIF_SIGNATURE.length].toInt() == 0

    private fun readTiff(
        bytes: ByteArray,
        tiff: Int,
    ): Int? {
        val byteOrder = bytes.decodeToString(tiff, tiff + U16_SIZE)
        val bigEndian = byteOrder == "MM"
        val valid = (bigEndian || byteOrder == "II") && bytes.u16(tiff + U16_SIZE, bigEndian) == TIFF_MAGIC
        if (!valid) return null
        val ifd = tiff + bytes.u32(tiff + IFD_OFFSET_POSITION, bigEndian)
        return (0 until bytes.u16(ifd, bigEndian))
            .map { ifd + U16_SIZE + it * IFD_ENTRY_SIZE }
            .firstOrNull {
                bytes.u16(it, bigEndian) == ORIENTATION_TAG &&
                    bytes.u16(it + ENTRY_TYPE_OFFSET, bigEndian) == TYPE_SHORT
            }?.let { bytes.u16(it + ENTRY_VALUE_OFFSET, bigEndian) }
            ?.takeIf { it in NORMAL..MAX_ORIENTATION }
    }

    private fun ByteArray.u8(pos: Int): Int = this[pos].toInt() and BYTE_MASK

    private fun ByteArray.u16(
        pos: Int,
        bigEndian: Boolean,
    ): Int =
        if (bigEndian) {
            (u8(pos) shl BITS_IN_BYTE) or u8(pos + 1)
        } else {
            (u8(pos + 1) shl BITS_IN_BYTE) or u8(pos)
        }

    private fun ByteArray.u32(
        pos: Int,
        bigEndian: Boolean,
    ): Int =
        if (bigEndian) {
            (u16(pos, true) shl BITS_IN_U16) or u16(pos + U16_SIZE, true)
        } else {
            (u16(pos + U16_SIZE, false) shl BITS_IN_U16) or u16(pos, false)
        }
}

/** Rotates/mirrors raw decoded pixels so the image is upright, according to an EXIF [orientation] (1..8). */
@Suppress("MagicNumber") // EXIF orientation codes 1..8 are defined by the TIFF specification.
fun RgbImage.applyExifOrientation(orientation: Int): RgbImage {
    if (orientation !in 2..MAX_ORIENTATION) return this
    val transposed = orientation >= 5
    val outW = if (transposed) height else width
    val outH = if (transposed) width else height
    val out = ByteArray(pixels.size)
    for (y in 0 until outH) {
        for (x in 0 until outW) {
            val (sx, sy) =
                when (orientation) {
                    2 -> width - 1 - x to y
                    3 -> width - 1 - x to height - 1 - y
                    4 -> x to height - 1 - y
                    5 -> y to x
                    6 -> y to height - 1 - x
                    7 -> width - 1 - y to height - 1 - x
                    else -> width - 1 - y to x
                }
            pixels.copyInto(
                out,
                (y * outW + x) * RgbImage.CHANNELS,
                (sy * width + sx) * RgbImage.CHANNELS,
                (sy * width + sx + 1) * RgbImage.CHANNELS,
            )
        }
    }
    return RgbImage(outW, outH, out)
}
