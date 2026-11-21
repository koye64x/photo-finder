package app.photofinder.core.ml

/** Two similarity thresholds: matches above [sure] are shown as "mine", between [maybe] and [sure] as "possibly". */
data class MatchThresholds(
    val sure: Float,
    val maybe: Float,
) {
    init {
        require(maybe <= sure) { "maybe ($maybe) must not exceed sure ($sure)" }
    }
}

/** A face from the index, identified by an arbitrary [key] (e.g. photo id + face number). */
data class FaceCandidate<K>(
    val key: K,
    val embedding: FaceEmbedding,
)

data class Match<K>(
    val key: K,
    val similarity: Float,
)

/** Matches sorted by descending similarity. */
data class MatchResult<K>(
    val sure: List<Match<K>>,
    val maybe: List<Match<K>>,
) {
    companion object {
        fun <K> empty(): MatchResult<K> = MatchResult(emptyList(), emptyList())
    }
}

/** Brute-force cosine search; fast enough for tens of thousands of faces per event. */
object FaceMatcher {
    fun <K> search(
        query: FaceEmbedding,
        candidates: Iterable<FaceCandidate<K>>,
        thresholds: MatchThresholds,
    ): MatchResult<K> {
        val sure = mutableListOf<Match<K>>()
        val maybe = mutableListOf<Match<K>>()
        for (candidate in candidates) {
            val similarity = query cosine candidate.embedding
            when {
                similarity >= thresholds.sure -> sure += Match(candidate.key, similarity)
                similarity >= thresholds.maybe -> maybe += Match(candidate.key, similarity)
            }
        }
        return MatchResult(
            sure = sure.sortedByDescending { it.similarity },
            maybe = maybe.sortedByDescending { it.similarity },
        )
    }

    /**
     * Query expansion: searches again with the centroid of the query and its sure matches, which helps find
     * photos where the face differs from the selfie (angle, lighting). Each key keeps its best similarity.
     */
    fun <K> searchExpanded(
        query: FaceEmbedding,
        candidates: Collection<FaceCandidate<K>>,
        thresholds: MatchThresholds,
    ): MatchResult<K> {
        val first = search(query, candidates, thresholds)
        if (first.sure.isEmpty()) return first

        val sureKeys = first.sure.map { it.key }.toSet()
        val centroid =
            FaceEmbedding.average(
                listOf(query) + candidates.filter { it.key in sureKeys }.map { it.embedding },
            )
        val second = search(centroid, candidates, thresholds)

        val best = HashMap<K, Match<K>>()
        for (match in first.sure + first.maybe + second.sure + second.maybe) {
            val current = best[match.key]
            if (current == null || match.similarity > current.similarity) best[match.key] = match
        }
        val all = best.values.sortedByDescending { it.similarity }
        return MatchResult(
            sure = all.filter { it.similarity >= thresholds.sure },
            maybe = all.filter { it.similarity < thresholds.sure },
        )
    }
}
