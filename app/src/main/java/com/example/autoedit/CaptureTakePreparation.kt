package com.example.autoedit

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Prepares whole takes; an aesthetic recommendation never replaces the product material gates. */
internal object CaptureTakePreparation {
    data class Assessment(val ordinal: Int, val eligible: Boolean, val score: Float?, val reason: String)
    data class Result(val file: File, val session: CaptureSessionStore.Session, val assessments: List<Assessment>, val musicFile: File)

    fun prepare(context: Context, sessionId: String, requestedTake: Int? = null,
        checkCancelled: () -> Unit = {}, onProgress: (String) -> Unit = {}): Result {
        val store = CaptureSessionStore(context.filesDir)
        val session = requireNotNull(store.load(sessionId)) { "Съёмка не найдена" }
        require(session.status == CaptureSessionStore.Status.READY) { "Запись ещё не сохранена" }
        val script = requireNotNull(CaptureScript.forStyle(session.styleId))
        require(script.version == session.scriptVersion) { "Для этой съёмки нужна прежняя версия сценария" }
        val source = store.recording(sessionId)
        require(source.isFile && source.length() == session.bytes) { "Файл съёмки изменился или отсутствует" }
        val style = MontageStyleCatalog.restore(session.styleId)
        require(style.id == session.styleId && style.available)
        val recipe = requireNotNull(style.recipe)
        val music = BuiltInMusicCatalog.select(context, style.id)
        val duration = CaptureRecordingSession.readableVideoDurationMs(source)
        val windows = CaptureTakeTimeline.windows(script, session.requestedTakes, duration)
            .filter { requestedTake == null || it.ordinal == requestedTake }
        require(windows.isNotEmpty()) { "В этой записи нет выбранного дубля" }
        val assessments = ArrayList<Assessment>()
        val prepared = linkedMapOf<Int, File>()
        windows.forEach { window ->
            checkCancelled()
            if (!CaptureTakeTimeline.usableForMontage(window)) {
                val reason = context.getString(if (!window.complete)
                    R.string.capture_reason_incomplete else R.string.capture_reason_too_short)
                assessments += Assessment(window.ordinal, false, null, reason)
                return@forEach
            }
            onProgress("Проверяю дубль ${window.ordinal} из ${session.requestedTakes}")
            val file = if (window.startMs == 0L && duration <= script.durationMs + 250) source
                else extract(source, store.directory(sessionId), window, duration, checkCancelled)
            checkCancelled()
            // Decoder/model/IO failures abort selection. Only typed material limitations reject a take.
            try {
                val analysis = MediaFrameVisualAnalyzer.analyze(context, file,
                    SourceAnalysisProfile.requiredFor(recipe), checkCancelled = checkCancelled)
                val visual = VisualEventMapAnalyzer.analyze(analysis.durationUs, analysis.observations)
                MaterialSuitability.checkDuration(recipe, listOf(analysis.durationUs))
                MaterialSuitability.checkHumanEvidence(recipe, listOf(visual))
                when (recipe) {
                    MontageStyleCatalog.Recipe.FEAR_STROBE -> {
                        val pool = FearSourcePool.select(visual, analysis.attachments)
                        val graph = FearDirector.build(pool, music.file.absolutePath, visual)
                        FearCascadeEvidence.requireSupported(graph, visual)
                    }
                    MontageStyleCatalog.Recipe.HEARTBEAT -> HeartbeatSourcePool.direct(visual,
                        analysis.attachments, music.file.absolutePath, checkCancelled = checkCancelled)
                    else -> error("Для этого стиля пока нет сценария съёмки")
                }
                val score = recommendationScore(analysis.observations)
                assessments += Assessment(window.ordinal, true, score, "Подходит для ${style.title}")
                prepared[window.ordinal] = file
            } catch (error: MaterialRejectedException) {
                CaptureDiagnostics.failure(context, CaptureDiagnostics.Stage.TAKE_SELECTION, error,
                    mapOf("take" to window.ordinal.toString(), "style" to style.id, "eligible" to "false"))
                assessments += Assessment(window.ordinal, false, null,
                    MontageFailurePresentation.message(error, 1))
            }
        }
        checkCancelled()
        val chosen = select(assessments, requestedTake)
        val explained = assessments.map { assessment ->
            if (requestedTake == null && assessment.ordinal == chosen?.ordinal)
                assessment.copy(reason = context.getString(R.string.capture_recommendation_reason, style.title))
            else assessment
        }
        writeAssessments(store.directory(sessionId), if (requestedTake == null) explained else
            (readAssessments(store.directory(sessionId)).filter { it.ordinal != requestedTake } + explained)
                .sortedBy { it.ordinal })
        val selected = chosen ?: if (requestedTake == null && assessments.any { it.eligible })
            throw MaterialRejectedException("capture_selection_required",
                context.getString(R.string.capture_selection_required))
        else throw MaterialRejectedException("capture_no_eligible_take",
            context.getString(R.string.capture_no_eligible_take, style.title))
        val updated = session.copy(selectedTake = selected.ordinal)
        LocalDiagnostics.record(context, "capture_take_selected", mapOf("style" to style.id,
            "selected_take" to selected.ordinal.toString()))
        return Result(requireNotNull(prepared[selected.ordinal]), updated, explained, music.file)
    }

    fun readAssessments(directory: File): List<Assessment> = runCatching {
        directory.resolve("take-assessments.tsv").takeIf(File::isFile)?.readLines().orEmpty().mapNotNull { line ->
            val parts = line.split('\t', limit = 4)
            if (parts.size != 4) return@mapNotNull null
            val ordinal = parts[0].toIntOrNull() ?: return@mapNotNull null
            val eligible = parts[1].toBooleanStrictOrNull() ?: return@mapNotNull null
            Assessment(ordinal, eligible, parts[2].takeUnless { it == "unknown" }?.toFloatOrNull(), parts[3])
        }
    }.getOrDefault(emptyList())

    private fun writeAssessments(directory: File, assessments: List<Assessment>) {
        val pending = directory.resolve("take-assessments.new")
        pending.writeText(assessments.joinToString("\n") {
            "${it.ordinal}\t${it.eligible}\t${it.score ?: "unknown"}\t${it.reason.replace('\n', ' ').replace('\t', ' ')}"
        })
        Files.move(pending.toPath(), directory.resolve("take-assessments.tsv").toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    internal fun choose(assessments: List<Assessment>): Assessment? = assessments
        .filter { it.eligible && it.score?.isFinite() == true }
        .maxWithOrNull(compareBy<Assessment> { it.score }.thenBy { -it.ordinal })

    internal fun select(assessments: List<Assessment>, requestedTake: Int?): Assessment? =
        if (requestedTake == null) choose(assessments)
        else assessments.singleOrNull { it.ordinal == requestedTake && it.eligible }

    /** Prototype ordering among eligible takes, pending calibration against human preferences. */
    internal fun recommendationScore(observations: List<VisualEventMap.Observation>): Float {
        require(observations.isNotEmpty())
        val quality = observations.map { it.visualQuality }.average().toFloat()
        val human = observations.map { it.humanPresenceConfidence }.average().toFloat()
        val composition = observations.mapNotNull { it.composition?.quality }
        // Missing composition contributes no positive evidence; it is not a measured failure.
        return if (composition.isEmpty()) .65f * quality + .35f * human
            else .50f * quality + .30f * human + .20f * composition.average().toFloat()
    }

    private fun extract(source: File, directory: File, window: CaptureTakeTimeline.Window,
        sourceDurationMs: Long, checkCancelled: () -> Unit): File {
        val metadata = MediaMetadataRetriever()
        val dimensions = try {
            metadata.setDataSource(source.absolutePath)
            val width = requireNotNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)).toInt()
            val height = requireNotNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)).toInt()
            val rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation % 180 == 0) Pair(width, height) else Pair(height, width)
        } finally { metadata.release() }
        val pending = File(directory, "take-${window.ordinal}.pending.mp4")
        val target = File(directory, "take-${window.ordinal}.mp4")
        val graph = MontageGraph(sourceDurationMs, window.durationMs, clips = listOf(MontageGraph.Clip(
            "capture-take-${window.ordinal}", window.startMs, window.endMs, window.durationMs,
            MontageGraph.ShotRole.ACTION, MontageGraph.Transition.HARD_CUT, MontageGraph.Motion.HOLD, 1f, 0)),
            metadata = NleProjectMetadata(generator = "capture-take-extract-v1"))
        try {
            MediaCodecSpeedRampRenderer.render(MediaCodecSpeedRampRenderer.Request(
                masterFile = source, graph = graph, outputFile = pending, width = dimensions.first / 2 * 2,
                height = dimensions.second / 2 * 2, fps = 30, bitrate = 12_000_000, checkCancelled = checkCancelled))
            checkCancelled()
            val actualMs = CaptureRecordingSession.readableVideoDurationMs(pending)
            check(kotlin.math.abs(actualMs - window.durationMs) <= 100) { "Не удалось точно выделить границы дубля" }
            java.nio.file.Files.move(pending.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            return target
        } catch (error: Throwable) {
            pending.delete()
            throw error
        }
    }
}
