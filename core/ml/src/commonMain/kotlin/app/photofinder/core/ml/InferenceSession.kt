package app.photofinder.core.ml

/** Dense float tensor in row-major order, e.g. NCHW for image inputs. */
class FloatTensor(
    val shape: IntArray,
    val data: FloatArray,
) {
    init {
        require(shape.isNotEmpty() && shape.all { it > 0 }) { "Tensor dimensions must be positive" }
        require(data.size == shape.fold(1) { acc, d -> acc * d }) { "Data size does not match the shape" }
    }

    override fun toString(): String = "FloatTensor(shape=${shape.contentToString()})"
}

/**
 * One loaded ML model. Platform adapters only wrap the shared runtime (ONNX Runtime on every platform, AGENTS.md
 * rule 5); all pre- and post-processing stays in commonMain so every platform produces the same vectors.
 */
interface InferenceSession : AutoCloseable {
    /** Runs the model on its single input and returns all outputs by name. */
    suspend fun run(input: FloatTensor): Map<String, FloatTensor>
}
