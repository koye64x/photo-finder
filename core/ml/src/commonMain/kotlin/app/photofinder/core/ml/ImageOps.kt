package app.photofinder.core.ml

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_CHANNEL = 255
private const val MIN_DOWNSCALE_FOR_BOX_FILTER = 2

/** Affine map `x' = a*x + b*y + c`, `y' = d*x + e*y + f`. */
data class AffineTransform(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val e: Float,
    val f: Float,
) {
    fun apply(p: Point): Point = Point(a * p.x + b * p.y + c, d * p.x + e * p.y + f)

    fun inverse(): AffineTransform {
        val det = a * e - b * d
        require(det != 0f) { "Transform is not invertible" }
        val ia = e / det
        val ib = -b / det
        val id = -d / det
        val ie = a / det
        return AffineTransform(ia, ib, -(ia * c + ib * f), id, ie, -(id * c + ie * f))
    }
}

/**
 * Downscales so that the longest side is at most [maxSide]; never upscales. Large reductions use a box filter
 * first to avoid aliasing, then bilinear interpolation to the exact size.
 */
fun RgbImage.scaledToFit(maxSide: Int): RgbImage {
    require(maxSide > 0) { "maxSide must be positive" }
    val longest = max(width, height)
    if (longest <= maxSide) return this
    val scale = maxSide.toFloat() / longest
    return resize(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
}

/** Resizes to exactly [newWidth] x [newHeight]. */
fun RgbImage.resize(
    newWidth: Int,
    newHeight: Int,
): RgbImage {
    require(newWidth > 0 && newHeight > 0) { "Size must be positive" }
    if (newWidth == width && newHeight == height) return this
    val factor = min(width / newWidth, height / newHeight)
    val source = if (factor >= MIN_DOWNSCALE_FOR_BOX_FILTER) boxShrink(factor) else this
    return source.resizeBilinear(newWidth, newHeight)
}

/** Averages [factor] x [factor] blocks; trailing pixels that do not fill a block are dropped. */
private fun RgbImage.boxShrink(factor: Int): RgbImage {
    val w = width / factor
    val h = height / factor
    val out = ByteArray(w * h * RgbImage.CHANNELS)
    for (y in 0 until h) {
        for (x in 0 until w) {
            for (ch in 0 until RgbImage.CHANNELS) {
                out[(y * w + x) * RgbImage.CHANNELS + ch] = blockAverage(x * factor, y * factor, factor, ch).toByte()
            }
        }
    }
    return RgbImage(w, h, out)
}

private fun RgbImage.blockAverage(
    left: Int,
    top: Int,
    size: Int,
    ch: Int,
): Int {
    var sum = 0
    for (y in top until top + size) {
        for (x in left until left + size) {
            sum += pixels[(y * width + x) * RgbImage.CHANNELS + ch].toInt() and MAX_CHANNEL
        }
    }
    return sum / (size * size)
}

private fun RgbImage.resizeBilinear(
    newWidth: Int,
    newHeight: Int,
): RgbImage {
    val out = ByteArray(newWidth * newHeight * RgbImage.CHANNELS)
    val sx = width.toFloat() / newWidth
    val sy = height.toFloat() / newHeight
    for (y in 0 until newHeight) {
        // Pixel-center alignment, as in OpenCV INTER_LINEAR.
        val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (height - 1).toFloat())
        for (x in 0 until newWidth) {
            val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (width - 1).toFloat())
            writeBilinear(fx, fy, out, (y * newWidth + x) * RgbImage.CHANNELS, clampToEdge = true)
        }
    }
    return RgbImage(newWidth, newHeight, out)
}

/**
 * Samples the pixel at fractional source coordinates into [out]. Outside the image the result is black
 * (OpenCV BORDER_CONSTANT) unless [clampToEdge] is set.
 */
private fun RgbImage.writeBilinear(
    fx: Float,
    fy: Float,
    out: ByteArray,
    offset: Int,
    clampToEdge: Boolean,
) {
    val x0 = floor(fx).toInt()
    val y0 = floor(fy).toInt()
    val wx = fx - x0
    val wy = fy - y0
    for (ch in 0 until RgbImage.CHANNELS) {
        val v00 = channelAt(x0, y0, ch, clampToEdge)
        val v10 = channelAt(x0 + 1, y0, ch, clampToEdge)
        val v01 = channelAt(x0, y0 + 1, ch, clampToEdge)
        val v11 = channelAt(x0 + 1, y0 + 1, ch, clampToEdge)
        val top = v00 + (v10 - v00) * wx
        val bottom = v01 + (v11 - v01) * wx
        out[offset + ch] = (top + (bottom - top) * wy).roundToInt().coerceIn(0, MAX_CHANNEL).toByte()
    }
}

private fun RgbImage.channelAt(
    x: Int,
    y: Int,
    ch: Int,
    clampToEdge: Boolean,
): Float {
    if (!clampToEdge && (x !in 0 until width || y !in 0 until height)) return 0f
    val cx = x.coerceIn(0, width - 1)
    val cy = y.coerceIn(0, height - 1)
    return (pixels[(cy * width + cx) * RgbImage.CHANNELS + ch].toInt() and MAX_CHANNEL).toFloat()
}

/**
 * Warps the image into an [outWidth] x [outHeight] output; [transform] maps source coordinates to output
 * coordinates (as in OpenCV warpAffine). Pixels that fall outside the source are black.
 */
fun RgbImage.warpAffine(
    transform: AffineTransform,
    outWidth: Int,
    outHeight: Int,
): RgbImage {
    val inverse = transform.inverse()
    val out = ByteArray(outWidth * outHeight * RgbImage.CHANNELS)
    for (y in 0 until outHeight) {
        for (x in 0 until outWidth) {
            val src = inverse.apply(Point(x.toFloat(), y.toFloat()))
            writeBilinear(src.x, src.y, out, (y * outWidth + x) * RgbImage.CHANNELS, clampToEdge = false)
        }
    }
    return RgbImage(outWidth, outHeight, out)
}

/** Result of [letterbox]: the square model input and the factor from original to input coordinates. */
class Letterboxed(
    val image: RgbImage,
    val scale: Float,
)

/**
 * Fits the image into a [size] x [size] square, keeping the aspect ratio. The image sits in the top-left corner
 * and the rest is black, so input coordinates map back to the original by dividing by [Letterboxed.scale].
 */
fun RgbImage.letterbox(size: Int): Letterboxed {
    require(size > 0) { "size must be positive" }
    val scale = size.toFloat() / max(width, height)
    val resized = resize(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
    val out = ByteArray(size * size * RgbImage.CHANNELS)
    val rowBytes = resized.width * RgbImage.CHANNELS
    for (y in 0 until resized.height) {
        resized.pixels.copyInto(out, y * size * RgbImage.CHANNELS, y * rowBytes, (y + 1) * rowBytes)
    }
    return Letterboxed(RgbImage(size, size, out), scale)
}

/** Channel order of a model input. */
enum class ChannelOrder { RGB, BGR }

/** Planar CHW float input: `(value - mean) / std` per channel, in the given channel [order]. */
fun RgbImage.toChw(
    order: ChannelOrder,
    mean: Float,
    std: Float,
): FloatArray {
    require(std != 0f) { "std must not be zero" }
    val plane = width * height
    val out = FloatArray(plane * RgbImage.CHANNELS)
    for (i in 0 until plane) {
        for (ch in 0 until RgbImage.CHANNELS) {
            val target = if (order == ChannelOrder.RGB) ch else RgbImage.CHANNELS - 1 - ch
            val value = (pixels[i * RgbImage.CHANNELS + ch].toInt() and MAX_CHANNEL).toFloat()
            out[target * plane + i] = (value - mean) / std
        }
    }
    return out
}
