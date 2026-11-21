package app.photofinder.core.ml

import kotlin.math.sqrt

/**
 * L2-normalized face embedding. Because vectors are unit length, cosine similarity is a dot product.
 *
 * Embeddings are biometric data: never log them, never persist them outside the encrypted index.
 */
class FaceEmbedding private constructor(
    private val values: FloatArray,
) {
    val dimension: Int get() = values.size

    /** Cosine similarity in [-1, 1]. Both embeddings must have the same dimension. */
    infix fun cosine(other: FaceEmbedding): Float {
        require(dimension == other.dimension) { "Dimension mismatch: $dimension vs ${other.dimension}" }
        var dot = 0f
        for (i in values.indices) dot += values[i] * other.values[i]
        return dot
    }

    /** Defensive copy of the raw values, e.g. for writing to the encrypted index. */
    fun toFloatArray(): FloatArray = values.copyOf()

    override fun toString(): String = "FaceEmbedding(dimension=$dimension)"

    companion object {
        /** Normalizes [raw] to unit length. Fails on an empty or all-zero vector. */
        fun of(raw: FloatArray): FaceEmbedding {
            require(raw.isNotEmpty()) { "Embedding must not be empty" }
            val norm = sqrt(raw.fold(0f) { acc, v -> acc + v * v })
            require(norm > 0f) { "Embedding must not be a zero vector" }
            return FaceEmbedding(FloatArray(raw.size) { raw[it] / norm })
        }

        /** Normalized mean of [embeddings]: used to average frames in a session and for query expansion. */
        fun average(embeddings: Collection<FaceEmbedding>): FaceEmbedding {
            require(embeddings.isNotEmpty()) { "Cannot average an empty collection" }
            val dimension = embeddings.first().dimension
            val sum = FloatArray(dimension)
            for (embedding in embeddings) {
                require(embedding.dimension == dimension) { "Dimension mismatch in average" }
                for (i in 0 until dimension) sum[i] += embedding.values[i]
            }
            return of(sum)
        }
    }
}
