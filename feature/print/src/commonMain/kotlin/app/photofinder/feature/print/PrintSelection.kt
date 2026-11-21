package app.photofinder.feature.print

import app.photofinder.core.storage.PhotoId

/**
 * Photos one person selected for printing, with a per-person print limit.
 * Contains only photo ids, no biometric data.
 */
class PrintSelection(
    val limit: Int,
) {
    private val selected = LinkedHashSet<PhotoId>()

    init {
        require(limit > 0) { "limit must be positive" }
    }

    val photos: List<PhotoId> get() = selected.toList()

    val remaining: Int get() = limit - selected.size

    /** Selects or deselects [photo]. Returns false if selecting would exceed the limit. */
    fun toggle(photo: PhotoId): Boolean =
        when {
            photo in selected -> selected.remove(photo)
            selected.size >= limit -> false
            else -> selected.add(photo)
        }

    fun clear() = selected.clear()
}
