package app.photofinder.feature.share

import app.photofinder.core.storage.PhotoId

/** URL encoded into the QR code; reachable only inside the local network (hotspot or shared Wi-Fi). */
data class ShareLink(
    val url: String,
)

/**
 * Local HTTP server (Ktor CIO) serving selected photos to a guest's phone without internet.
 * The only network code allowed in the app; it must never call external hosts.
 */
interface ShareServer {
    suspend fun start(photos: List<PhotoId>): ShareLink

    suspend fun stop()
}
