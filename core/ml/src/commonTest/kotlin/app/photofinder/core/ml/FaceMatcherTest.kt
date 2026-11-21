package app.photofinder.core.ml

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FaceMatcherTest {
    private fun emb(vararg v: Float) = FaceEmbedding.of(v)

    private val thresholds = MatchThresholds(sure = 0.9f, maybe = 0.6f)

    @Test
    fun embeddingIsNormalized() {
        val e = emb(3f, 4f)
        assertTrue(abs((e cosine e) - 1f) < 1e-6f)
    }

    @Test
    fun zeroVectorIsRejected() {
        assertFailsWith<IllegalArgumentException> { emb(0f, 0f) }
    }

    @Test
    fun splitsMatchesIntoSureAndMaybe() {
        val query = emb(1f, 0f)
        val candidates =
            listOf(
                FaceCandidate("same", emb(1f, 0.05f)),
                FaceCandidate("similar", emb(1f, 0.8f)),
                FaceCandidate("other", emb(0f, 1f)),
            )

        val result = FaceMatcher.search(query, candidates, thresholds)

        assertEquals(listOf("same"), result.sure.map { it.key })
        assertEquals(listOf("similar"), result.maybe.map { it.key })
    }

    @Test
    fun expansionPromotesFacesCloseToSureMatches() {
        val query = emb(1f, 0f, 0f)
        val candidates =
            listOf(
                FaceCandidate("a", emb(1f, 0.4f, 0f)),
                FaceCandidate("b", emb(1f, 0.9f, 0f)),
            )

        val plain = FaceMatcher.search(query, candidates, thresholds)
        val expanded = FaceMatcher.searchExpanded(query, candidates, thresholds)

        assertEquals(listOf("a"), plain.sure.map { it.key })
        assertEquals(listOf("b"), plain.maybe.map { it.key })
        assertTrue(expanded.maybe.single().similarity > plain.maybe.single().similarity)
    }

    @Test
    fun averageOfIdenticalEmbeddingsIsTheSame() {
        val e = emb(0.2f, 0.7f, 0.1f)
        val avg = FaceEmbedding.average(listOf(e, e, e))
        assertTrue(abs((avg cosine e) - 1f) < 1e-6f)
    }
}
