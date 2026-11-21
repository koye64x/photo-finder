package app.photofinder.feature.session

import app.photofinder.core.db.InMemoryFaceIndex
import app.photofinder.core.db.IndexedFace
import app.photofinder.core.ml.BoundingBox
import app.photofinder.core.ml.FaceEmbedding
import app.photofinder.core.ml.MatchThresholds
import app.photofinder.core.storage.PhotoId
import app.photofinder.core.storage.PhotoRef
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FindMyPhotosTest {
    private val box = BoundingBox(0f, 0f, 1f, 1f)

    private fun emb(vararg v: Float) = FaceEmbedding.of(v)

    @Test
    fun returnsEachPhotoOnceWithBestFace() =
        runTest {
            val index = InMemoryFaceIndex()
            val group = PhotoRef(PhotoId("group.jpg"), 1, 1)
            val solo = PhotoRef(PhotoId("solo.jpg"), 1, 1)
            val other = PhotoRef(PhotoId("other.jpg"), 1, 1)
            index.put(
                group,
                listOf(IndexedFace(group.id, box, emb(1f, 0.1f)), IndexedFace(group.id, box, emb(1f, 0.05f))),
            )
            index.put(solo, listOf(IndexedFace(solo.id, box, emb(1f, 0f))))
            index.put(other, listOf(IndexedFace(other.id, box, emb(0f, 1f))))

            val result = FindMyPhotos(index, MatchThresholds(sure = 0.9f, maybe = 0.6f)).find(emb(1f, 0f))

            assertEquals(listOf("solo.jpg", "group.jpg"), result.sure.map { it.key.value })
            assertEquals(emptyList(), result.maybe)
        }

    @Test
    fun accumulatorWaitsForEnoughFrames() {
        val acc = EmbeddingAccumulator(requiredFrames = 3)
        acc.add(emb(1f, 0f))
        acc.add(emb(1f, 0.1f))
        assertNull(acc.query())

        acc.add(emb(1f, -0.1f))
        assertEquals(2, acc.query()?.dimension)

        acc.clear()
        assertNull(acc.query())
    }
}
