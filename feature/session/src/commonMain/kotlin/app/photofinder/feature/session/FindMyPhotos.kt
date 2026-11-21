package app.photofinder.feature.session

import app.photofinder.core.db.FaceIndex
import app.photofinder.core.ml.FaceCandidate
import app.photofinder.core.ml.FaceEmbedding
import app.photofinder.core.ml.FaceMatcher
import app.photofinder.core.ml.Match
import app.photofinder.core.ml.MatchResult
import app.photofinder.core.ml.MatchThresholds
import app.photofinder.core.storage.PhotoId

/**
 * Accumulates embeddings of one viewer over several frames and averages them (pipeline B, step 2).
 * Lives in memory only; call [clear] when the session ends ("Done").
 */
class EmbeddingAccumulator(
    private val requiredFrames: Int,
) {
    private val frames = mutableListOf<FaceEmbedding>()

    init {
        require(requiredFrames > 0) { "requiredFrames must be positive" }
    }

    val isReady: Boolean get() = frames.size >= requiredFrames

    fun add(embedding: FaceEmbedding) {
        frames += embedding
    }

    /** Averaged query embedding, or null if not enough frames yet. */
    fun query(): FaceEmbedding? = if (isReady) FaceEmbedding.average(frames.takeLast(requiredFrames)) else null

    fun clear() = frames.clear()
}

/** Pipeline B, steps 3-4: search photos of one viewer in the event index, one result per photo. */
class FindMyPhotos(
    private val index: FaceIndex,
    private val thresholds: MatchThresholds,
) {
    suspend fun find(query: FaceEmbedding): MatchResult<PhotoId> {
        val candidates =
            index.allFaces().withIndex().map { (i, face) -> FaceCandidate(FaceKey(face.photoId, i), face.embedding) }
        val result = FaceMatcher.searchExpanded(query, candidates, thresholds)

        // A photo may contain several faces close to the query; keep the best one per photo.
        val best =
            (result.sure + result.maybe)
                .groupBy { it.key.photoId }
                .map { (photoId, matches) -> Match(photoId, matches.maxOf { it.similarity }) }
                .sortedByDescending { it.similarity }
        return MatchResult(
            sure = best.filter { it.similarity >= thresholds.sure },
            maybe = best.filter { it.similarity < thresholds.sure },
        )
    }

    private data class FaceKey(
        val photoId: PhotoId,
        val faceIndex: Int,
    )
}
