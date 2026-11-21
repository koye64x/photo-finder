package app.photofinder.core.ml

/** Decoded image in packed RGB (3 bytes per pixel, row-major). */
class RgbImage(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "Image size must be positive" }
        require(pixels.size == width * height * CHANNELS) { "Expected ${width * height * CHANNELS} bytes" }
    }

    companion object {
        const val CHANNELS = 3
    }
}

data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class Point(
    val x: Float,
    val y: Float,
)

/** A face found by the detector: box, 5 landmarks (eyes, nose, mouth corners) and confidence. */
data class DetectedFace(
    val box: BoundingBox,
    val landmarks: List<Point>,
    val score: Float,
)

/** Face detector (YuNet). Implementations wrap the shared ML runtime, never ML Kit / Apple Vision. */
interface FaceDetector {
    suspend fun detect(image: RgbImage): List<DetectedFace>
}

/** Face embedder (AuraFace). Input is an aligned 112x112 face crop. */
interface FaceEmbedder {
    suspend fun embed(alignedFace: RgbImage): FaceEmbedding
}
