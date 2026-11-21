package app.photofinder.feature.indexing

import app.photofinder.core.db.FaceIndex
import app.photofinder.core.db.IndexedFace
import app.photofinder.core.storage.EventStorage
import app.photofinder.core.storage.PhotoId
import app.photofinder.core.storage.PhotoRef

/** Pipeline A steps 2-5: decode, detect, align, embed. Implemented on top of the shared ML runtime. */
fun interface PhotoFaceExtractor {
    suspend fun extract(
        photo: PhotoRef,
        bytes: ByteArray,
    ): List<IndexedFace>
}

/** Photos that are new or changed since they were indexed (by name and modification time). */
fun selectPhotosToIndex(
    photos: List<PhotoRef>,
    indexed: Map<PhotoId, Long>,
): List<PhotoRef> = photos.filter { indexed[it.id] != it.lastModifiedMillis }

/**
 * Pipeline A: background indexing of new photos on the external storage. Runs in batches so it can be paused
 * for a user session and to limit thermal throttling. Platform schedulers (WorkManager etc.) call [runBatch].
 */
class IndexingPipeline(
    private val storage: EventStorage,
    private val index: FaceIndex,
    private val extractor: PhotoFaceExtractor,
) {
    /** Indexes up to [batchSize] pending photos. Returns the number of photos processed. */
    suspend fun runBatch(batchSize: Int): Int {
        require(batchSize > 0) { "batchSize must be positive" }
        val pending = selectPhotosToIndex(storage.listPhotos(), index.indexedPhotos()).take(batchSize)
        for (photo in pending) {
            val faces = extractor.extract(photo, storage.readPhoto(photo.id))
            index.put(photo, faces)
        }
        return pending.size
    }
}
