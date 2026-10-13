package com.veycad.app

import android.content.Context
import java.io.File

/** Restricted export seam; caller serializes preparation/request against edits and retains source bytes. */
object PreparedHybridExportAdapter {
    fun request(
        prepared: PreparedHybridPreview,
        installedProject: HybridProject,
        outputFile: File,
        audioFile: File? = null,
        context: Context? = null,
        checkCancelled: () -> Unit = {}
    ): MediaCodecSpeedRampRenderer.Request {
        checkCancelled()
        require(installedProject.id == prepared.projectId && installedProject.current.id == prepared.revisionId) {
            "Prepared export requires the same installed project revision"
        }
        require(installedProject.fps == prepared.exportSettings.fps)
        val revision = installedProject.current
        require(revision.texts.isEmpty() && revision.textState.layers.isEmpty() && revision.textState.captions.isEmpty()) {
            "Prepared export MVP does not render editable text or captions"
        }
        val music = revision.music
        val preparedAudio = prepared.graph.audioTrack
        require(music == installedProject.original.music && music.startUs == 0L && music.gain == 1f &&
            music.fadeInUs == 0L && music.fadeOutUs == 0L && music.repeat &&
            (preparedAudio == null || preparedAudio.gain == 1f)) {
            "Prepared export MVP requires unchanged unity-gain looping music without offsets or fades"
        }
        require(installedProject.selectedVideos == prepared.sources.map { it.selection }) {
            "Prepared export selection order differs from installed project"
        }
        val assets = installedProject.assets.associateBy { it.id }
        prepared.sources.forEach { source ->
            checkCancelled()
            val asset = requireNotNull(assets[source.assetId])
            require(asset.kind == ProjectAsset.Kind.VIDEO && asset.videoMetadata == source.metadata &&
                asset.contentHash == source.contentHash && asset.id == source.selection.assetId) {
                "Prepared export source metadata differs from installed project"
            }
        }
        // ProjectVisualSettings snapshots its map; source settings and geometry are immutable values.
        val visual = revision.visualSettings.copy()
        val order = prepared.sources.map { it.selection.id }
        require(visual.framings.keys.all { it.sourceId in order }) { "Framing references an unknown selection" }
        val geometries = prepared.sources.associate { it.selection.id to it.metadata.geometry }
        val samples = order.associateWith { id ->
            val settings = visual.framings[FramingKey(id, visual.aspect)] ?: SourceFramingSettings()
            require(settings.mode == FramingMode.MANUAL) { "Prepared export supports MANUAL framing only" }
            FramingPlan.sample(geometries.getValue(id), visual.aspect, settings.manual)
        }
        val framing = ManualFramingTable(order, geometries, visual.aspect, samples)
        val binding = PreparedHybridExportBinding.create(prepared, visual, framing)
        val export = prepared.exportSettings
        return MediaCodecSpeedRampRenderer.Request(
            graph = prepared.graph, sourceFiles = prepared.sources.map { it.file }, outputFile = outputFile,
            width = export.width, height = export.height, fps = export.fps, bitrate = export.bitrate,
            audioFile = audioFile, audioDurationUs = prepared.framePlan.durationUs,
            context = context, checkCancelled = checkCancelled
        ).also { it.bindPreparedExport(binding) }
    }
}

/** Not a standalone plan override: construction requires the preparation owner's sealed values. */
internal class PreparedHybridExportBinding private constructor(
    val prepared: PreparedHybridPreview,
    val visualSettings: ProjectVisualSettings,
    val framing: ManualFramingTable
) {
    fun validate(request: MediaCodecSpeedRampRenderer.Request) {
        request.checkCancelled()
        val export = prepared.exportSettings
        require(request.graph === prepared.graph && request.fps == export.fps &&
            request.width == export.width && request.height == export.height && request.bitrate == export.bitrate &&
            request.sourceFiles == prepared.sources.map { it.file } && request.outputWindow == null) {
            "Request differs from its prepared graph, saved export settings or full source selection table"
        }
        require(export.fps in listOf(30, 60) && minOf(export.width, export.height) in listOf(720, 1080)) {
            "Prepared export MVP supports saved 30/60 FPS at 720/1080 only"
        }
        framing.requireTarget(OutputSize(export.width, export.height))
        requirePlanBindings(prepared.graph, prepared.framePlan, prepared.sources)
        val outputPaths = setOf(request.outputFile.canonicalFile,
            File(request.outputFile.parentFile, request.outputFile.nameWithoutExtension + ".video.mp4").canonicalFile)
        require(outputPaths.size == 2 && prepared.sources.none { it.file.canonicalFile in outputPaths } &&
            (request.audioFile == null || request.audioFile.canonicalFile !in outputPaths)) {
            "Export output must not replace an input file"
        }
        prepared.sources.forEach { source ->
            request.checkCancelled()
            require(source.file.isFile && source.file.length() == source.metadata.sizeBytes) {
                "Prepared source is missing or its size changed; prepare again"
            }
        }
        require(request.audioFile == null || request.audioFile.isFile) { "Local music file is missing" }
    }

    companion object {
        fun create(prepared: PreparedHybridPreview, visual: ProjectVisualSettings, framing: ManualFramingTable) =
            PreparedHybridExportBinding(prepared, visual, framing)

        /** Admission only. Never compiles, samples effects or builds a replacement frame schedule. */
        fun requirePlanBindings(graph: MontageGraph, plan: HighQualityFramePlan.Plan, sources: List<PreparedVideoSource>) {
            require(sources.isNotEmpty() && sources.map { it.selection.id }.distinct().size == sources.size &&
                sources.withIndex().all { (index, source) -> source.sourceIndex == index && source.assetId == source.selection.assetId }) {
                "Invalid prepared source selection table"
            }
            require(graph.clips.all { it.sourceIndex in sources.indices }) { "Graph source is absent from selection table" }
            require(graph.frameAttachments.frames.isEmpty() && graph.frameAttachments.maskRefinements.isEmpty() &&
                graph.sourceAttachments.all { it.sourceIndex in sources.indices && it.timeline.frames.isEmpty() &&
                    it.timeline.maskRefinements.isEmpty() }) { "Prepared export requires analysis-free inputs" }
            val timing = graph.editableTiming
            require(plan.fps in listOf(30, 60) && (timing == null || timing.fps == plan.fps)) { "Prepared FPS differs from graph" }
            val duration = timing?.let { frameTimeUs(it.frameCount, plan.fps) } ?: graph.outputDurationMs * 1000L
            require(plan.durationUs == duration) { "Prepared duration differs from graph" }
            val expectedCount = timing?.frameCount ?: ((duration * plan.fps + 999_999L) / 1_000_000L)
            require(plan.frames.size.toLong() == expectedCount) { "Prepared frame count differs from graph/FPS" }
            val boundaries = graph.clips.runningFold(0L) { cursor, clip -> cursor + clip.outputDurationMs * 1000L }
            plan.frames.forEachIndexed { index, frame ->
                require(frame.outputTimeUs == frameTimeUs(index.toLong(), plan.fps) &&
                    frame.globalOutputTimeUs == frame.outputTimeUs && frame.clipIndex in graph.clips.indices) {
                    "Invalid prepared frame grid or clip binding"
                }
                val clip = graph.clips[frame.clipIndex]
                val inClip = timing?.clips?.get(frame.clipIndex)?.let {
                    index.toLong() in it.startFrame until it.endFrameExclusive
                } ?: (frame.outputTimeUs in boundaries[frame.clipIndex] until boundaries[frame.clipIndex + 1])
                require(inClip && frame.sourceIndex == clip.sourceIndex && frame.sourceIndex in sources.indices &&
                    (frame.secondarySourceIndex == null || frame.secondarySourceIndex in sources.indices)) {
                    "Prepared frame source/clip differs from graph or selection table"
                }
                require(frame.attachments == null && frame.secondaryAttachments == null) { "Prepared export requires analysis-free frames" }
                require(frame.sourceTimeUs >= 0 && (sources[frame.sourceIndex].metadata.videoEndPtsUs?.let {
                    frame.sourceTimeUs < it
                } ?: true)) { "Prepared frame exceeds its inspected source endpoint" }
                frame.secondarySourceTimeUs?.let { pts ->
                    val secondary = frame.secondarySourceIndex ?: frame.sourceIndex
                    require(pts >= 0 && (sources[secondary].metadata.videoEndPtsUs?.let { pts < it } ?: true)) {
                        "Prepared temporal frame exceeds its inspected source endpoint"
                    }
                }
            }
        }
    }
}
