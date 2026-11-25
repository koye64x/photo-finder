package app.photofinder.core.ml

import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the real models from models/ on ONNX Runtime: output contracts and determinism, not accuracy (that is the
 * accuracy test on testdata/). Skipped when a model is only a Git LFS pointer (repository cloned without LFS).
 */
class OnnxModelsTest {
    private fun model(name: String): String {
        val file = File(System.getProperty("user.dir")).resolve("../../models/$name").canonicalFile
        assumeTrue("$name is missing or an LFS pointer; run git lfs pull", file.length() > LFS_POINTER_MAX_SIZE)
        return file.path
    }

    private fun noiseFace(seed: Int): RgbImage =
        RgbImage(
            ALIGNED_FACE_SIZE,
            ALIGNED_FACE_SIZE,
            Random(seed).nextBytes(ALIGNED_FACE_SIZE * ALIGNED_FACE_SIZE * 3),
        )

    @Test
    fun yunetFindsNoFacesOnFlatImage() =
        runTest {
            OrtInferenceSession.fromFile(model("yunet_2023mar.onnx")).use { session ->
                val faces = YuNetDetector(session).detect(solidImage(800, 600, 128, 128, 128))
                assertEquals(emptyList(), faces)
            }
        }

    @Test
    fun sfaceProducesDeterministic128dEmbeddings() = embedderContract("sface_2021dec.onnx", EmbedderSpec.SFACE)

    @Test
    fun aurafaceProducesDeterministic512dEmbeddings() =
        embedderContract("auraface_v1_glintr100.onnx", EmbedderSpec.AURAFACE)

    private fun embedderContract(
        file: String,
        spec: EmbedderSpec,
    ) = runTest {
        OrtInferenceSession.fromFile(model(file)).use { session ->
            val embedder = ModelFaceEmbedder(session, spec)
            val a = embedder.embed(noiseFace(1))
            val again = embedder.embed(noiseFace(1))
            val b = embedder.embed(noiseFace(2))

            assertEquals(spec.dimension, a.dimension)
            assertTrue((a cosine again) > 0.9999f, "same input must give the same vector")
            assertTrue((a cosine b) < 0.9999f, "different inputs must give different vectors")
        }
    }

    private companion object {
        const val LFS_POINTER_MAX_SIZE = 1024L
    }
}
