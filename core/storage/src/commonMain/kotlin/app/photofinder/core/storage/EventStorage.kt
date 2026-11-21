package app.photofinder.core.storage

import kotlin.jvm.JvmInline

/** Stable photo identifier: the file name inside the event folder on the external storage. */
@JvmInline
value class PhotoId(
    val value: String,
)

/** A photo file in the event folder. Name + modification time drive incremental indexing. */
data class PhotoRef(
    val id: PhotoId,
    val lastModifiedMillis: Long,
    val sizeBytes: Long,
)

/**
 * Event folder on external storage (microSD/USB) chosen by the user via SAF (Android) or
 * UIDocumentPicker (iOS). All app data lives here; nothing is written to internal storage.
 */
interface EventStorage {
    /** False when the storage was removed; the app must stop working with the event. */
    suspend fun isAvailable(): Boolean

    suspend fun listPhotos(): List<PhotoRef>

    suspend fun readPhoto(id: PhotoId): ByteArray
}
