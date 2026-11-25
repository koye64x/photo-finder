package app.photofinder.core.ml

/** Side of the aligned face crop expected by ArcFace-style embedders (AuraFace, SFace). */
const val ALIGNED_FACE_SIZE = 112

/**
 * Standard ArcFace landmark positions in a 112x112 crop: eye on the image's left, other eye, nose tip,
 * mouth corner on the image's left, other mouth corner. YuNet returns landmarks in the same order.
 */
@Suppress("MagicNumber") // Reference template from InsightFace / OpenCV FaceRecognizerSF.
val ARCFACE_TEMPLATE: List<Point> =
    listOf(
        Point(38.2946f, 51.6963f),
        Point(73.5318f, 51.5014f),
        Point(56.0252f, 71.7366f),
        Point(41.5493f, 92.3655f),
        Point(70.7299f, 92.2041f),
    )

/**
 * Least-squares similarity transform (rotation, uniform scale, translation; no reflection) mapping [from] onto
 * [to]. Same result as Umeyama's method used by InsightFace and OpenCV for face alignment.
 */
fun estimateSimilarity(
    from: List<Point>,
    to: List<Point>,
): AffineTransform {
    require(from.size == to.size && from.size >= 2) { "Need at least two point pairs of equal count" }
    val fx = from.map { it.x }.average().toFloat()
    val fy = from.map { it.y }.average().toFloat()
    val tx = to.map { it.x }.average().toFloat()
    val ty = to.map { it.y }.average().toFloat()
    var norm = 0f
    var dot = 0f
    var cross = 0f
    for (i in from.indices) {
        val sx = from[i].x - fx
        val sy = from[i].y - fy
        val dx = to[i].x - tx
        val dy = to[i].y - ty
        norm += sx * sx + sy * sy
        dot += sx * dx + sy * dy
        cross += sx * dy - sy * dx
    }
    require(norm > 0f) { "Source points must not coincide" }
    val a = dot / norm
    val b = cross / norm
    return AffineTransform(a, -b, tx - (a * fx - b * fy), b, a, ty - (b * fx + a * fy))
}

/** Crops [face] from [image] into a 112x112 aligned face, the input of [FaceEmbedder]. */
fun alignFace(
    image: RgbImage,
    face: DetectedFace,
): RgbImage {
    require(face.landmarks.size == ARCFACE_TEMPLATE.size) { "Expected ${ARCFACE_TEMPLATE.size} landmarks" }
    val transform = estimateSimilarity(face.landmarks, ARCFACE_TEMPLATE)
    return image.warpAffine(transform, ALIGNED_FACE_SIZE, ALIGNED_FACE_SIZE)
}
