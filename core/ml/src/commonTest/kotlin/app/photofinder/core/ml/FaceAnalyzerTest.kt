package app.photofinder.core.ml

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FaceAnalyzerTest {
    private fun near(
        expected: Float,
        actual: Float,
        eps: Float = 1e-3f,
    ) = assertTrue(abs(expected - actual) < eps, "expected $expected, got $actual")

    @Test
    fun similarityRecoversKnownTransform() {
        val known = AffineTransform(0.6f, -0.8f, 10f, 0.8f, 0.6f, -5f) // rotation + scale 1 + shift
        val moved = ARCFACE_TEMPLATE.map { known.apply(it) }

        val estimated = estimateSimilarity(ARCFACE_TEMPLATE, moved)

        near(known.a, estimated.a)
        near(known.b, estimated.b)
        near(known.c, estimated.c)
        near(known.d, estimated.d)
        near(known.f, estimated.f)
    }

    @Test
    fun alignFaceIsIdentityWhenLandmarksMatchTemplate() {
        val image = coordImage(ALIGNED_FACE_SIZE, ALIGNED_FACE_SIZE)
        val aligned = alignFace(image, DetectedFace(BoundingBox(0f, 0f, 112f, 112f), ARCFACE_TEMPLATE, 1f))
        assertEquals(image.pixel(40, 60), aligned.pixel(40, 60))
        assertEquals(image.pixel(100, 5), aligned.pixel(100, 5))
    }

    @Test
    fun embedderNormalizesInputAndOutput() =
        runTest {
            val spec = EmbedderSpec("test", mean = 127.5f, std = 127.5f, ChannelOrder.RGB, dimension = 2)
            val session = FakeSession { mapOf("out" to FloatTensor(intArrayOf(1, 2), floatArrayOf(3f, 4f))) }

            val embedding = ModelFaceEmbedder(session, spec).embed(solidImage(112, 112, 255, 0, 127))

            assertEquals(listOf(1, 3, 112, 112), session.lastInput!!.shape.toList())
            near(1f, session.lastInput!!.data[0])
            near(-1f, session.lastInput!!.data[112 * 112])
            near(1f, embedding cosine FaceEmbedding.of(floatArrayOf(3f, 4f)))
        }

    @Test
    fun embedderRejectsWrongOutputDimension() =
        runTest {
            val spec = EmbedderSpec("test", 0f, 1f, ChannelOrder.RGB, dimension = 512)
            val session = FakeSession { mapOf("out" to FloatTensor(intArrayOf(1, 128), FloatArray(128) { 1f })) }
            assertFailsWith<IllegalStateException> {
                ModelFaceEmbedder(
                    session,
                    spec,
                ).embed(solidImage(112, 112, 0, 0, 0))
            }
        }

    @Test
    fun analyzerAppliesExifSkipsSmallFacesAndNormalizesBoxes() =
        runTest {
            // Raw 200x100 image with EXIF orientation 6 -> upright 100x200.
            val decoder =
                object : PhotoDecoder {
                    override suspend fun decode(
                        bytes: ByteArray,
                        maxSide: Int,
                    ) = solidImage(200, 100, 50, 50, 50)
                }
            var seenSize = 0 to 0
            val detector =
                object : FaceDetector {
                    override suspend fun detect(image: RgbImage): List<DetectedFace> {
                        seenSize = image.width to image.height
                        val big = ARCFACE_TEMPLATE.map { Point(it.x / 2, it.y / 2 + 100) }
                        return listOf(
                            DetectedFace(BoundingBox(0f, 100f, 50f, 160f), big, 0.9f),
                            DetectedFace(BoundingBox(0f, 0f, 20f, 20f), ARCFACE_TEMPLATE, 0.9f),
                        )
                    }
                }
            val embedder =
                object : FaceEmbedder {
                    override suspend fun embed(alignedFace: RgbImage) = FaceEmbedding.of(floatArrayOf(1f, 0f))
                }
            val analyzer =
                FaceAnalyzer(
                    decoder,
                    detector,
                    embedder,
                    AnalyzerConfig(minFaceSize = 40f),
                    StandardTestDispatcher(testScheduler),
                )

            val faces = analyzer.analyzePhoto(jpegOriented6())

            assertEquals(100 to 200, seenSize)
            val box = faces.single().box
            near(0f, box.left)
            near(0.5f, box.top)
            near(0.5f, box.right)
            near(0.8f, box.bottom)
        }

    @Test
    fun sharpnessIsZeroForFlatAndHighForCheckerboard() {
        assertEquals(0f, sharpness(solidImage(16, 16, 90, 90, 90)))
        val checker = solidImage(16, 16, 0, 0, 0)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                if ((x + y) % 2 == 0) for (c in 0 until 3) checker.pixels[(y * 16 + x) * 3 + c] = 255.toByte()
            }
        }
        assertTrue(sharpness(checker) > 1000f)
    }

    private fun jpegOriented6(): ByteArray {
        val tiff =
            listOf(0x4D, 0x4D, 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0)
        val exif = "Exif".encodeToByteArray().map { it.toInt() } + listOf(0, 0) + tiff
        val app1 = listOf(0xFF, 0xE1, 0, exif.size + 2) + exif
        return (listOf(0xFF, 0xD8) + app1 + listOf(0xFF, 0xDA)).map { it.toByte() }.toByteArray()
    }
}
