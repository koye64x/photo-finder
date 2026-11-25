package app.photofinder.core.ml

/**
 * Input and output contract of an embedding model. Both candidates take a 112x112 RGB crop aligned to
 * [ARCFACE_TEMPLATE]; the final choice is made by the accuracy test (docs/roadmap.md, stage 1).
 */
data class EmbedderSpec(
    val name: String,
    val mean: Float,
    val std: Float,
    val channelOrder: ChannelOrder,
    val dimension: Int,
) {
    companion object {
        /** AuraFace v1 (models/auraface_v1_glintr100.onnx): ResNet100, normalized to [-1, 1], 512-d. */
        @Suppress("MagicNumber") // InsightFace ArcFace preprocessing constants.
        val AURAFACE = EmbedderSpec("auraface-v1", mean = 127.5f, std = 127.5f, ChannelOrder.RGB, dimension = 512)

        /** SFace 2021dec (models/sface_2021dec.onnx): raw 0..255 input, 128-d. */
        @Suppress("MagicNumber") // OpenCV FaceRecognizerSF preprocessing constants.
        val SFACE = EmbedderSpec("sface-2021dec", mean = 0f, std = 1f, ChannelOrder.RGB, dimension = 128)
    }
}

/** [FaceEmbedder] on an ONNX model described by [spec]. The output is L2-normalized by [FaceEmbedding.of]. */
class ModelFaceEmbedder(
    private val session: InferenceSession,
    val spec: EmbedderSpec,
) : FaceEmbedder {
    override suspend fun embed(alignedFace: RgbImage): FaceEmbedding {
        require(alignedFace.width == ALIGNED_FACE_SIZE && alignedFace.height == ALIGNED_FACE_SIZE) {
            "Expected a ${ALIGNED_FACE_SIZE}x$ALIGNED_FACE_SIZE aligned face"
        }
        val input =
            FloatTensor(
                intArrayOf(1, RgbImage.CHANNELS, ALIGNED_FACE_SIZE, ALIGNED_FACE_SIZE),
                alignedFace.toChw(spec.channelOrder, spec.mean, spec.std),
            )
        val output =
            session
                .run(input)
                .values
                .single()
                .data
        check(output.size == spec.dimension) { "${spec.name}: got ${output.size} values, expected ${spec.dimension}" }
        return FaceEmbedding.of(output)
    }
}
