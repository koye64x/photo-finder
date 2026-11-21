package app.photofinder.feature.indexing

import app.photofinder.core.db.InMemoryFaceIndex
import app.photofinder.core.db.IndexedFace
import app.photofinder.core.ml.BoundingBox
import app.photofinder.core.ml.FaceEmbedding
import app.photofinder.core.storage.EventStorage
import app.photofinder.core.storage.PhotoId
import app.photofinder.core.storage.PhotoRef
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class IndexingPipelineTest {
    private class FakeStorage(
        var photos: List<PhotoRef>,
    ) : EventStorage {
        override suspend fun isAvailable() = true

        override suspend fun listPhotos() = photos

        override suspend fun readPhoto(id: PhotoId) = ByteArray(0)
    }

    private val oneFace =
        PhotoFaceExtractor { photo, _ ->
            listOf(IndexedFace(photo.id, BoundingBox(0f, 0f, 1f, 1f), FaceEmbedding.of(floatArrayOf(1f, 0f))))
        }

    private fun photo(
        name: String,
        modified: Long = 1,
    ) = PhotoRef(PhotoId(name), modified, 100)

    @Test
    fun indexesInBatchesAndSkipsAlreadyIndexed() =
        runTest {
            val storage = FakeStorage(listOf(photo("a.jpg"), photo("b.jpg"), photo("c.jpg")))
            val index = InMemoryFaceIndex()
            val pipeline = IndexingPipeline(storage, index, oneFace)

            assertEquals(2, pipeline.runBatch(2))
            assertEquals(1, pipeline.runBatch(2))
            assertEquals(0, pipeline.runBatch(2))
            assertEquals(3, index.allFaces().size)
        }

    @Test
    fun reindexesChangedPhotoWithoutDuplicates() =
        runTest {
            val storage = FakeStorage(listOf(photo("a.jpg", modified = 1)))
            val index = InMemoryFaceIndex()
            val pipeline = IndexingPipeline(storage, index, oneFace)
            pipeline.runBatch(10)

            storage.photos = listOf(photo("a.jpg", modified = 2))

            assertEquals(1, pipeline.runBatch(10))
            assertEquals(1, index.allFaces().size)
        }
}
