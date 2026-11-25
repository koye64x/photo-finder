package app.photofinder.core.ml

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val LANDMARKS = 5
private const val BOX_VALUES = 4
private val STRIDES = intArrayOf(8, 16, 32)

/**
 * YuNet settings. [inputSize] must be a multiple of 32. The score threshold is lower than OpenCV's default (0.9)
 * because event photos have many small and turned faces; calibrate it with the accuracy test.
 */
data class YuNetConfig(
    val inputSize: Int = 640,
    val scoreThreshold: Float = 0.7f,
    val nmsThreshold: Float = 0.3f,
    val maxFaces: Int = 200,
) {
    init {
        require(inputSize > 0 && inputSize % STRIDES.last() == 0) { "inputSize must be a multiple of 32" }
    }
}

/**
 * Face detector on the YuNet 2023mar model (models/yunet_2023mar.onnx): BGR input in 0..255, outputs
 * `cls_*`, `obj_*`, `bbox_*`, `kps_*` per stride. Decoding follows OpenCV's FaceDetectorYN. The image is
 * letterboxed into a square input; boxes and landmarks are returned in the coordinates of [detect]'s image.
 */
class YuNetDetector(
    private val session: InferenceSession,
    private val config: YuNetConfig = YuNetConfig(),
) : FaceDetector {
    override suspend fun detect(image: RgbImage): List<DetectedFace> {
        val size = config.inputSize
        val boxed = image.letterbox(size)
        val input =
            FloatTensor(intArrayOf(1, RgbImage.CHANNELS, size, size), boxed.image.toChw(ChannelOrder.BGR, 0f, 1f))
        val faces = decodeYuNet(session.run(input), size, size, config.scoreThreshold)
        return nonMaxSuppression(faces, config.nmsThreshold)
            .take(config.maxFaces)
            .map { it.scaled(1f / boxed.scale).clippedTo(image.width, image.height) }
    }
}

/** Decodes raw YuNet outputs for an input of [inputWidth] x [inputHeight] into faces above [scoreThreshold]. */
internal fun decodeYuNet(
    outputs: Map<String, FloatTensor>,
    inputWidth: Int,
    inputHeight: Int,
    scoreThreshold: Float,
): List<DetectedFace> {
    val faces = mutableListOf<DetectedFace>()
    for (stride in STRIDES) {
        val cols = inputWidth / stride
        val rows = inputHeight / stride
        val cls = outputs.require("cls_$stride", rows * cols)
        val obj = outputs.require("obj_$stride", rows * cols)
        val bbox = outputs.require("bbox_$stride", rows * cols * BOX_VALUES)
        val kps = outputs.require("kps_$stride", rows * cols * LANDMARKS * 2)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = r * cols + c
                val score = sqrt(cls[idx].coerceIn(0f, 1f) * obj[idx].coerceIn(0f, 1f))
                if (score < scoreThreshold) continue
                val b = idx * BOX_VALUES
                val cx = (c + bbox[b]) * stride
                val cy = (r + bbox[b + 1]) * stride
                val w = exp(bbox[b + 2]) * stride
                val h = exp(bbox[b + 3]) * stride
                val landmarks =
                    List(LANDMARKS) { n ->
                        val k = idx * LANDMARKS * 2 + n * 2
                        Point((kps[k] + c) * stride, (kps[k + 1] + r) * stride)
                    }
                faces += DetectedFace(BoundingBox(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), landmarks, score)
            }
        }
    }
    return faces
}

private fun Map<String, FloatTensor>.require(
    name: String,
    size: Int,
): FloatArray {
    val tensor = get(name) ?: error("YuNet output '$name' is missing; outputs: $keys")
    check(tensor.data.size == size) { "YuNet output '$name' has ${tensor.data.size} values, expected $size" }
    return tensor.data
}

/** Greedy non-maximum suppression: keeps the highest-scoring face among overlaps above [iouThreshold]. */
fun nonMaxSuppression(
    faces: List<DetectedFace>,
    iouThreshold: Float,
): List<DetectedFace> {
    val kept = mutableListOf<DetectedFace>()
    for (face in faces.sortedByDescending { it.score }) {
        if (kept.none { iou(it.box, face.box) > iouThreshold }) kept += face
    }
    return kept
}

internal fun iou(
    a: BoundingBox,
    b: BoundingBox,
): Float {
    val w = min(a.right, b.right) - max(a.left, b.left)
    val h = min(a.bottom, b.bottom) - max(a.top, b.top)
    if (w <= 0f || h <= 0f) return 0f
    val intersection = w * h
    return intersection / (a.width * a.height + b.width * b.height - intersection)
}

private fun DetectedFace.scaled(factor: Float) =
    DetectedFace(
        BoundingBox(box.left * factor, box.top * factor, box.right * factor, box.bottom * factor),
        landmarks.map { Point(it.x * factor, it.y * factor) },
        score,
    )

private fun DetectedFace.clippedTo(
    width: Int,
    height: Int,
) = copy(
    box =
        BoundingBox(
            box.left.coerceIn(0f, width.toFloat()),
            box.top.coerceIn(0f, height.toFloat()),
            box.right.coerceIn(0f, width.toFloat()),
            box.bottom.coerceIn(0f, height.toFloat()),
        ),
)
