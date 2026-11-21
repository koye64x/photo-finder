package app.photofinder.core.camera

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

/** A front camera frame in packed RGB. Frames are processed in memory only and never stored. */
class CameraFrame(
    val width: Int,
    val height: Int,
    val timestampMillis: Long,
    val pixels: ByteArray,
)

/** Front camera stream: CameraX (Android), AVFoundation (iOS), or a fake source in tests. */
interface FrameSource {
    fun frames(): Flow<CameraFrame>
}

/** Fake source replaying prepared frames, for session tests and the desktop target. */
class ListFrameSource(
    private val frames: List<CameraFrame>,
) : FrameSource {
    override fun frames(): Flow<CameraFrame> = frames.asFlow()
}
