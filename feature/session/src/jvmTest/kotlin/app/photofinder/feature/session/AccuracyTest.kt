package app.photofinder.feature.session

import app.photofinder.core.db.InMemoryFaceIndex
import app.photofinder.core.db.IndexedFace
import app.photofinder.core.ml.EmbedderSpec
import app.photofinder.core.ml.FaceAnalyzer
import app.photofinder.core.ml.FaceEmbedding
import app.photofinder.core.ml.ImageIoPhotoDecoder
import app.photofinder.core.ml.MatchThresholds
import app.photofinder.core.ml.ModelFaceEmbedder
import app.photofinder.core.ml.OrtInferenceSession
import app.photofinder.core.ml.YuNetDetector
import app.photofinder.core.storage.PhotoId
import app.photofinder.core.storage.PhotoRef
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Locale
import java.util.Properties
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Accuracy test on the reference set in testdata/ (see docs/testdata.md): the acceptance criterion for any
 * ML change (AGENTS.md). Runs pipelines A and B end to end with the real models for every embedding candidate,
 * writes a threshold sweep to build/reports/accuracy/, and enforces testdata/accuracy.properties once it exists.
 * Skipped while the reference set has not been collected.
 */
class AccuracyTest {
    private val root = File(System.getProperty("user.dir")).resolve("../..").canonicalFile
    private val testdata = root.resolve("testdata")
    private val models = root.resolve("models")

    private val candidates =
        listOf(
            "sface_2021dec.onnx" to EmbedderSpec.SFACE,
            "auraface_v1_glintr100.onnx" to EmbedderSpec.AURAFACE,
        )

    @Test
    fun accuracyOnReferenceSet() =
        runBlocking {
            val labels = testdata.resolve("labels.csv")
            val labeled = labels.isFile && labels.readLines().drop(1).any { it.isNotBlank() }
            assumeTrue("testdata/ has no labeled photos: clone the reference set (docs/testdata.md)", labeled)
            val set = ReferenceSet.load(testdata)
            val gate = AccuracyGate.load(testdata.resolve("accuracy.properties"))
            val reportDir = File("build/reports/accuracy").apply { mkdirs() }

            for ((file, spec) in candidates.filter { models.resolve(it.first).length() > LFS_POINTER_MAX_SIZE }) {
                val evaluation = evaluate(set, models.resolve(file), spec)
                reportDir.resolve("${spec.name}.md").writeText(evaluation.report())
                if (gate?.model == spec.name) gate.check(evaluation)
            }
        }

    private suspend fun evaluate(
        set: ReferenceSet,
        modelFile: File,
        spec: EmbedderSpec,
    ): Evaluation {
        OrtInferenceSession.fromFile(models.resolve("yunet_2023mar.onnx").path).use { detectorSession ->
            OrtInferenceSession.fromFile(modelFile.path).use { embedderSession ->
                val analyzer =
                    FaceAnalyzer(
                        ImageIoPhotoDecoder(),
                        YuNetDetector(detectorSession),
                        ModelFaceEmbedder(embedderSession, spec),
                    )
                val index = InMemoryFaceIndex()
                val started = TimeSource.Monotonic.markNow()
                var faces = 0
                for (photo in set.photos) {
                    val ref = PhotoRef(PhotoId(photo.name), photo.lastModified(), photo.length())
                    val found = analyzer.analyzePhoto(photo.readBytes())
                    index.put(ref, found.map { IndexedFace(ref.id, it.box, it.embedding) })
                    faces += found.size
                }
                val msPerPhoto = started.elapsedNow().inWholeMilliseconds.toDouble() / set.photos.size
                val queries = set.queries.mapValues { (_, selfies) -> query(analyzer, selfies) }
                return Evaluation(spec.name, set, faces, msPerPhoto, queries, FindMyPhotosRunner(index))
            }
        }
    }

    /** Session query: the largest face of each selfie, averaged like [EmbeddingAccumulator] does for frames. */
    private suspend fun query(
        analyzer: FaceAnalyzer,
        selfies: List<File>,
    ): FaceEmbedding? {
        val embeddings =
            selfies.mapNotNull { selfie ->
                analyzer.analyzePhoto(selfie.readBytes()).maxByOrNull { it.box.width * it.box.height }?.embedding
            }
        return if (embeddings.isEmpty()) null else FaceEmbedding.average(embeddings)
    }

    private companion object {
        const val LFS_POINTER_MAX_SIZE = 1024L
    }
}

/** testdata/: event-photos/, guest-camera/<person>/ captures and labels "photo,person". */
private class ReferenceSet(
    val photos: List<File>,
    val queries: Map<String, List<File>>,
    val expected: Map<String, Set<String>>,
    val version: String,
) {
    companion object {
        /** Commit of the reference set repository, so before/after numbers can be compared on the same data. */
        private fun versionOf(dir: File): String =
            runCatching {
                val git = ProcessBuilder("git", "-C", dir.path, "describe", "--always", "--dirty").start()
                git.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
                    .ifEmpty { null }
            }.getOrNull() ?: "unknown"

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")

        fun load(dir: File): ReferenceSet {
            val photos = dir.resolve("event-photos").images()
            val queries =
                dir
                    .resolve("guest-camera")
                    .listFiles { f -> f.isDirectory }
                    .orEmpty()
                    .associate { it.name to it.images() }
                    .filterValues { it.isNotEmpty() }
            val expected =
                dir
                    .resolve("labels.csv")
                    .readLines()
                    .drop(1)
                    .filter { it.isNotBlank() }
                    .map { line -> line.split(',').map { it.trim() } }
                    .groupBy({ it[1] }, { it[0] })
                    .mapValues { it.value.toSet() }
            check(photos.isNotEmpty()) { "testdata/event-photos has no photos" }
            check(photos.none { it.isLfsPointer() }) { "testdata photos are LFS pointers; run git lfs pull" }
            return ReferenceSet(photos, queries, expected, versionOf(dir))
        }

        private fun File.images() =
            listFiles { f -> f.extension.lowercase() in IMAGE_EXTENSIONS }.orEmpty().sortedBy { it.name }

        private fun File.isLfsPointer() = length() < 1024 && readText().startsWith("version https://git-lfs")
    }
}

private class FindMyPhotosRunner(
    private val index: InMemoryFaceIndex,
) {
    suspend fun find(
        query: FaceEmbedding,
        thresholds: MatchThresholds,
    ): Pair<Set<String>, Set<String>> {
        val result = FindMyPhotos(index, thresholds).find(query)
        return result.sure.map { it.key.value }.toSet() to result.maybe.map { it.key.value }.toSet()
    }
}

private data class Score(
    val recallSure: Double,
    val precisionSure: Double,
    val recallAll: Double,
    val precisionAll: Double,
)

private class Evaluation(
    val model: String,
    val set: ReferenceSet,
    val faces: Int,
    val msPerPhoto: Double,
    val queries: Map<String, FaceEmbedding?>,
    val runner: FindMyPhotosRunner,
) {
    /** Micro-averaged over people with a query: "sure" alone and "sure + maybe" (what the guest sees). */
    suspend fun score(thresholds: MatchThresholds): Score {
        var tpSure = 0
        var fpSure = 0
        var tpAll = 0
        var fpAll = 0
        var relevant = 0
        for ((person, query) in queries) {
            val truth = set.expected[person].orEmpty()
            relevant += truth.size
            if (query == null) continue
            val (sure, maybe) = runner.find(query, thresholds)
            tpSure += sure.count { it in truth }
            fpSure += sure.count { it !in truth }
            tpAll += (sure + maybe).count { it in truth }
            fpAll += (sure + maybe).count { it !in truth }
        }
        return Score(
            recallSure = ratio(tpSure, relevant),
            precisionSure = ratio(tpSure, tpSure + fpSure),
            recallAll = ratio(tpAll, relevant),
            precisionAll = ratio(tpAll, tpAll + fpAll),
        )
    }

    suspend fun report(): String =
        buildString {
            appendLine("# Accuracy: $model")
            appendLine()
            appendLine("- Reference set version: ${set.version}")
            appendLine("- Photos: ${set.photos.size}, faces found: $faces, people with queries: ${queries.size}")
            appendLine("- Queries without a detected face: ${queries.filterValues { it == null }.keys.sorted()}")
            appendLine("- Indexing on this machine: ${msPerPhoto.roundToInt()} ms per photo")
            appendLine()
            appendLine("| sure | maybe | recall sure | precision sure | recall all | precision all |")
            appendLine("| --- | --- | --- | --- | --- | --- |")
            for (step in SWEEP_STEPS) {
                val sure = SWEEP_START + step * SWEEP_STEP
                val t = MatchThresholds(sure = sure, maybe = (sure - MAYBE_GAP).coerceAtLeast(0f))
                val s = score(t)
                appendLine(
                    "| %.2f | %.2f | %.3f | %.3f | %.3f | %.3f |"
                        .format(
                            Locale.ROOT,
                            t.sure,
                            t.maybe,
                            s.recallSure,
                            s.precisionSure,
                            s.recallAll,
                            s.precisionAll,
                        ),
                )
            }
        }

    private fun ratio(
        numerator: Int,
        denominator: Int,
    ) = if (denominator == 0) 0.0 else numerator.toDouble() / denominator

    private companion object {
        const val SWEEP_START = 0.2f
        const val SWEEP_STEP = 0.05f
        const val MAYBE_GAP = 0.1f
        val SWEEP_STEPS = 0..12
    }
}

/**
 * Fixed acceptance thresholds (testdata/accuracy.properties): `model`, `sure`, `maybe`, `minRecallAll`,
 * `minPrecisionSure`. Changed only in a dedicated PR with before/after numbers.
 */
private class AccuracyGate(
    val model: String,
    private val thresholds: MatchThresholds,
    private val minRecallAll: Double,
    private val minPrecisionSure: Double,
) {
    suspend fun check(evaluation: Evaluation) {
        val score = evaluation.score(thresholds)
        assertTrue(score.recallAll >= minRecallAll, "$model: recall (sure + maybe) ${score.recallAll} < $minRecallAll")
        assertTrue(
            score.precisionSure >= minPrecisionSure,
            "$model: precision (sure) ${score.precisionSure} < $minPrecisionSure",
        )
    }

    companion object {
        fun load(file: File): AccuracyGate? {
            if (!file.isFile) return null
            val p = Properties().apply { file.inputStream().use { load(it) } }
            return AccuracyGate(
                model = p.getProperty("model"),
                thresholds = MatchThresholds(p.getProperty("sure").toFloat(), p.getProperty("maybe").toFloat()),
                minRecallAll = p.getProperty("minRecallAll").toDouble(),
                minPrecisionSure = p.getProperty("minPrecisionSure").toDouble(),
            )
        }
    }
}
