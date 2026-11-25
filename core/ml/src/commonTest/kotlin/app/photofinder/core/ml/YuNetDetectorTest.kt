package app.photofinder.core.ml

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Raw YuNet outputs for a square input with one face at stride-8 cell ([row], [col]). */
internal fun yunetOutputs(
    size: Int,
    row: Int,
    col: Int,
    score: Float,
): Map<String, FloatTensor> {
    val outputs = mutableMapOf<String, FloatTensor>()
    for (stride in intArrayOf(8, 16, 32)) {
        val n = (size / stride) * (size / stride)
        val cls = FloatArray(n)
        val obj = FloatArray(n)
        val bbox = FloatArray(n * 4)
        val kps = FloatArray(n * 10)
        if (stride == 8) {
            val idx = row * (size / stride) + col
            cls[idx] = score
            obj[idx] = score
            // Center offset 0.5 cell; width/height = exp(ln 4) * 8 = 32 px.
            bbox[idx * 4] = 0.5f
            bbox[idx * 4 + 1] = 0.5f
            bbox[idx * 4 + 2] = kotlin.math.ln(4f)
            bbox[idx * 4 + 3] = kotlin.math.ln(4f)
            for (k in 0 until 5) {
                kps[idx * 10 + k * 2] = k.toFloat() / 10
                kps[idx * 10 + k * 2 + 1] = 0.25f
            }
        }
        outputs["cls_$stride"] = FloatTensor(intArrayOf(1, n, 1), cls)
        outputs["obj_$stride"] = FloatTensor(intArrayOf(1, n, 1), obj)
        outputs["bbox_$stride"] = FloatTensor(intArrayOf(1, n, 4), bbox)
        outputs["kps_$stride"] = FloatTensor(intArrayOf(1, n, 10), kps)
    }
    return outputs
}

internal class FakeSession(
    private val respond: (FloatTensor) -> Map<String, FloatTensor>,
) : InferenceSession {
    var lastInput: FloatTensor? = null

    override suspend fun run(input: FloatTensor): Map<String, FloatTensor> {
        lastInput = input
        return respond(input)
    }

    override fun close() = Unit
}

class YuNetDetectorTest {
    private fun near(
        expected: Float,
        actual: Float,
    ) = assertTrue(abs(expected - actual) < 1e-3f, "expected $expected, got $actual")

    @Test
    fun decodesBoxLandmarksAndScore() {
        val faces = decodeYuNet(yunetOutputs(64, row = 2, col = 3, score = 0.9f), 64, 64, scoreThreshold = 0.5f)

        val face = faces.single()
        near(0.9f, face.score)
        // Center (3 + 0.5) * 8 = 28, (2 + 0.5) * 8 = 20; size 32.
        near(12f, face.box.left)
        near(4f, face.box.top)
        near(44f, face.box.right)
        near(36f, face.box.bottom)
        near(24.8f, face.landmarks[1].x)
        near(18f, face.landmarks[1].y)
    }

    @Test
    fun dropsFacesBelowThreshold() {
        assertTrue(decodeYuNet(yunetOutputs(64, 1, 1, score = 0.4f), 64, 64, scoreThreshold = 0.5f).isEmpty())
    }

    @Test
    fun failsClearlyOnUnexpectedOutputs() {
        val outputs = yunetOutputs(64, 1, 1, 0.9f) - "kps_16"
        val error = assertFailsWith<IllegalStateException> { decodeYuNet(outputs, 64, 64, 0.5f) }
        assertTrue("kps_16" in error.message.orEmpty())
    }

    @Test
    fun nmsKeepsBestOfOverlappingFaces() {
        fun face(
            left: Float,
            score: Float,
        ) = DetectedFace(BoundingBox(left, 0f, left + 10f, 10f), emptyList(), score)

        val kept = nonMaxSuppression(listOf(face(0f, 0.8f), face(1f, 0.9f), face(30f, 0.7f)), iouThreshold = 0.3f)

        assertEquals(listOf(0.9f, 0.7f), kept.map { it.score })
    }

    @Test
    fun detectFeedsBgrLetterboxAndMapsBackToImageCoordinates() =
        runTest {
            val session = FakeSession { yunetOutputs(64, row = 2, col = 3, score = 0.9f) }
            val detector = YuNetDetector(session, YuNetConfig(inputSize = 64, scoreThreshold = 0.5f))

            // 128x64 image is scaled by 0.5 into the 64x64 input.
            val faces = detector.detect(solidImage(128, 64, 10, 20, 30))

            val input = session.lastInput!!
            assertEquals(listOf(1, 3, 64, 64), input.shape.toList())
            assertEquals(30f, input.data[0]) // blue plane first
            // Input box (12, 4, 44, 36) / 0.5 = (24, 8, 88, 72); the bottom is clipped to the image height.
            val box = faces.single().box
            near(24f, box.left)
            near(8f, box.top)
            near(88f, box.right)
            assertEquals(64f, box.bottom)
        }
}
