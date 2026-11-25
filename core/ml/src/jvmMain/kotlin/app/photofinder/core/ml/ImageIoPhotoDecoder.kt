package app.photofinder.core.ml

import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.math.max

private const val BYTE_MASK = 0xFF
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8

/**
 * [PhotoDecoder] on javax.imageio (JPEG, PNG). Uses integer subsampling while decoding, so the result is at most
 * about twice [maxSide]; EXIF orientation is not applied here. HEIC is not supported on the JVM.
 */
class ImageIoPhotoDecoder : PhotoDecoder {
    override suspend fun decode(
        bytes: ByteArray,
        maxSide: Int,
    ): RgbImage {
        val input =
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: throw IOException("No image input stream")
        input.use { stream ->
            val reader =
                ImageIO.getImageReaders(stream).asSequence().firstOrNull()
                    ?: throw IOException("Unsupported image format")
            try {
                reader.input = stream
                val factor = max(1, max(reader.getWidth(0), reader.getHeight(0)) / maxSide)
                val param = reader.defaultReadParam.apply { setSourceSubsampling(factor, factor, 0, 0) }
                val image = reader.read(0, param)
                val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
                val pixels = ByteArray(argb.size * RgbImage.CHANNELS)
                for (i in argb.indices) {
                    pixels[i * RgbImage.CHANNELS] = (argb[i] shr RED_SHIFT and BYTE_MASK).toByte()
                    pixels[i * RgbImage.CHANNELS + 1] = (argb[i] shr GREEN_SHIFT and BYTE_MASK).toByte()
                    pixels[i * RgbImage.CHANNELS + 2] = (argb[i] and BYTE_MASK).toByte()
                }
                return RgbImage(image.width, image.height, pixels)
            } finally {
                reader.dispose()
            }
        }
    }
}
