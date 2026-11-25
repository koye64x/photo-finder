package app.photofinder.core.ml

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

private const val MAX_CHANNEL = 255
private const val LUMA_R = 0.299f
private const val LUMA_G = 0.587f
private const val LUMA_B = 0.114f
private const val LAPLACIAN_CENTER = 4f
private const val LAPLACIAN_KERNEL = 3

/**
 * Decodes an encoded photo (JPEG etc.) into raw pixels as stored, without applying EXIF orientation:
 * [FaceAnalyzer] does that in common code so all platforms see the same pixels. Implementations may return an
 * image larger than [maxSide] (e.g. decoder subsampling only halves); the caller downsizes the rest.
 */
interface PhotoDecoder {
    suspend fun decode(
        bytes: ByteArray,
        maxSide: Int,
    ): RgbImage
}

/**
 * Settings of [FaceAnalyzer]. [minFaceSize] is the shorter box side in pixels of the analyzed image; smaller
 * faces give unreliable embeddings. [minSharpness] is the Laplacian variance of the aligned crop; 0 disables the
 * blur filter until it is calibrated with the accuracy test.
 */
data class AnalyzerConfig(
    val maxSide: Int = 1024,
    val minFaceSize: Float = 40f,
    val minSharpness: Float = 0f,
)

/** A face found in a photo: [box] in coordinates normalized to 0..1 of the upright image. */
class AnalyzedFace(
    val box: BoundingBox,
    val embedding: FaceEmbedding,
) {
    override fun toString(): String = "AnalyzedFace(box=$box)"
}

/**
 * Pipeline A steps 2-5 and pipeline B per frame: decode, EXIF orientation, detection, quality filter, alignment,
 * embedding. Runs on [dispatcher] (CPU-heavy) and is cancellable between faces.
 */
class FaceAnalyzer(
    private val decoder: PhotoDecoder,
    private val detector: FaceDetector,
    private val embedder: FaceEmbedder,
    private val config: AnalyzerConfig = AnalyzerConfig(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Faces in an encoded photo from the event folder. */
    suspend fun analyzePhoto(bytes: ByteArray): List<AnalyzedFace> =
        withContext(dispatcher) {
            val raw = decoder.decode(bytes, config.maxSide)
            analyzeUpright(raw.applyExifOrientation(ExifOrientation.read(bytes)).scaledToFit(config.maxSide))
        }

    /** Faces in an already upright image, e.g. a camera frame. */
    suspend fun analyze(image: RgbImage): List<AnalyzedFace> = withContext(dispatcher) { analyzeUpright(image) }

    private suspend fun analyzeUpright(image: RgbImage): List<AnalyzedFace> {
        val result = mutableListOf<AnalyzedFace>()
        val largeEnough = detector.detect(image).filter { min(it.box.width, it.box.height) >= config.minFaceSize }
        for (face in largeEnough) {
            val aligned = alignFace(image, face)
            if (isSharpEnough(aligned)) {
                result += AnalyzedFace(face.box.normalizedTo(image.width, image.height), embedder.embed(aligned))
            }
        }
        return result
    }

    private fun isSharpEnough(aligned: RgbImage) =
        config.minSharpness <= 0f || sharpness(aligned) >= config.minSharpness

    private fun BoundingBox.normalizedTo(
        width: Int,
        height: Int,
    ) = BoundingBox(left / width, top / height, right / width, bottom / height)
}

/** Variance of the Laplacian of the grayscale image: low values mean a blurry face. */
fun sharpness(image: RgbImage): Float {
    val w = image.width
    val h = image.height
    if (w < LAPLACIAN_KERNEL || h < LAPLACIAN_KERNEL) return 0f
    val gray =
        FloatArray(w * h) { i ->
            val p = i * RgbImage.CHANNELS
            LUMA_R * (image.pixels[p].toInt() and MAX_CHANNEL) +
                LUMA_G * (image.pixels[p + 1].toInt() and MAX_CHANNEL) +
                LUMA_B * (image.pixels[p + 2].toInt() and MAX_CHANNEL)
        }
    var sum = 0.0
    var sumSq = 0.0
    var n = 0
    for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
            val i = y * w + x
            val lap = gray[i - 1] + gray[i + 1] + gray[i - w] + gray[i + w] - LAPLACIAN_CENTER * gray[i]
            sum += lap
            sumSq += lap * lap
            n++
        }
    }
    val mean = sum / n
    return (sumSq / n - mean * mean).toFloat()
}
