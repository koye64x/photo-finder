package app.photofinder.core.db

import app.photofinder.core.ml.BoundingBox
import app.photofinder.core.ml.FaceEmbedding
import app.photofinder.core.storage.PhotoId
import app.photofinder.core.storage.PhotoRef

data class IndexedFace(
    val photoId: PhotoId,
    val box: BoundingBox,
    val embedding: FaceEmbedding,
)

/**
 * Face index of one event. Production implementation: SQLite + SQLCipher on the external storage,
 * key in Android Keystore / Keychain. [clear] is called on "Finish event" together with key destruction.
 */
interface FaceIndex {
    /** Replaces all faces of [photo] (re-indexing a changed file). A photo without faces is still recorded. */
    suspend fun put(
        photo: PhotoRef,
        faces: List<IndexedFace>,
    )

    /** Indexed photos with the modification time they were indexed at. */
    suspend fun indexedPhotos(): Map<PhotoId, Long>

    suspend fun allFaces(): List<IndexedFace>

    suspend fun clear()
}

/** In-memory index for tests and the desktop target until the encrypted implementation exists. */
class InMemoryFaceIndex : FaceIndex {
    private val photos = LinkedHashMap<PhotoId, Long>()
    private val faces = LinkedHashMap<PhotoId, List<IndexedFace>>()

    override suspend fun put(
        photo: PhotoRef,
        faces: List<IndexedFace>,
    ) {
        require(faces.all { it.photoId == photo.id }) { "All faces must belong to ${photo.id}" }
        photos[photo.id] = photo.lastModifiedMillis
        this.faces[photo.id] = faces
    }

    override suspend fun indexedPhotos(): Map<PhotoId, Long> = photos.toMap()

    override suspend fun allFaces(): List<IndexedFace> = faces.values.flatten()

    override suspend fun clear() {
        photos.clear()
        faces.clear()
    }
}
