package app.photofinder.core.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * [InferenceSession] on ONNX Runtime for the JVM (tests, desktop). Thin adapter: no pre- or post-processing.
 *
 * ONNX Runtime's Windows build can emit ETW telemetry events; they are switched off here (AGENTS.md, rule 1).
 */
class OrtInferenceSession private constructor(
    private val session: OrtSession,
) : InferenceSession {
    private val inputName: String = session.inputNames.single()

    override suspend fun run(input: FloatTensor): Map<String, FloatTensor> {
        val shape = LongArray(input.shape.size) { input.shape[it].toLong() }
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.data), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                return result.associate { (name, value) ->
                    val onnx = value as OnnxTensor
                    val buffer = onnx.floatBuffer
                    val data = FloatArray(buffer.remaining()).also { buffer.get(it) }
                    name to
                        FloatTensor(
                            onnx.info.shape
                                .map { it.toInt() }
                                .toIntArray(),
                            data,
                        )
                }
            }
        }
    }

    override fun close() = session.close()

    companion object {
        private val environment: OrtEnvironment by lazy {
            // ERROR level: AuraFace triggers harmless graph-optimization warnings on every load.
            OrtEnvironment
                .getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR, "photo-finder")
                .apply { setTelemetry(false) }
        }

        /** Loads a model from a file, e.g. `models/yunet_2023mar.onnx`. */
        fun fromFile(path: String): OrtInferenceSession =
            OrtSession.SessionOptions().use { options ->
                OrtInferenceSession(environment.createSession(path, options))
            }
    }
}
