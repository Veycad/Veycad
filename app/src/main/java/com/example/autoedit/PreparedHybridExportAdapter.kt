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
        checkCancelled: () -> Unit = {},
        smartTracks: Map<SourceId, SmartFramingTrack> = emptyMap()
    ): MediaCodecSpeedRampRenderer.Request {
        checkCancelled()
        require(installedProject.id == prepared.projectId && installedProject.current.id == prepared.revisionId) {
            "Prepared export requires the same installed project revision"
        }
        require(installedProject.fps == prepared.exportSettings.fps)
        val revision = installedProject.current
        val text = prepared.renderText
        require(revision.texts == text?.legacyItems.orEmpty() && revision.textState.layers == text?.layers.orEmpty() &&
            revision.textState.captions == text?.captions.orEmpty() &&
            (text == null || (revision.textState.captionStyle == text.captionStyle &&
                revision.textState.captionsEdited == text.captionsEdited && revision.textState.language == text.language)) &&
            revision.style.showAuthoredText == prepared.showAuthoredText) {
            "Prepared text/visibility differs from the installed revision"
        }
        require(prepared.audio.projectId == installedProject.id && prepared.audio.revisionId == revision.id &&
            prepared.audio.music == revision.music && prepared.audio.settings == revision.audioMix &&
            prepared.audio.durationUs == prepared.framePlan.durationUs) {
            "Prepared audio differs from the installed revision or output duration"
        }
        require(audioFile == null || audioFile.canonicalFile == prepared.audio.musicFile?.canonicalFile) {
            "Local music must be the canonical prepared asset"
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
        val framing = ManualFramingTable.fromDraft(DraftProject(installedProject), smartTracks)
        val binding = PreparedHybridExportBinding.create(prepared, visual, framing)
        val export = prepared.exportSettings
        return MediaCodecSpeedRampRenderer.Request(
            graph = prepared.graph, sourceFiles = prepared.sources.map { it.file }, outputFile = outputFile,
            width = export.width, height = export.height, fps = export.fps, bitrate = export.bitrate,
            audioFile = audioFile, audioDurationUs = prepared.framePlan.durationUs,
            context = context, checkCancelled = checkCancelled, textProject = prepared.renderText,
            textBehavior = MediaCodecSpeedRampRenderer.TextBehavior.MONTAGE_OVERLAY,
            showAuthoredText = prepared.showAuthoredText
        ).also { it.bindPreparedExport(binding); prepared.retainFraming(framing) }
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
            request.sourceFiles == prepared.sources.map { it.file } && request.outputWindow == null &&
            request.textProject === prepared.renderText && request.showAuthoredText == prepared.showAuthoredText &&
            request.textBehavior == MediaCodecSpeedRampRenderer.TextBehavior.MONTAGE_OVERLAY &&
            request.audioDurationUs == prepared.framePlan.durationUs &&
            (request.audioFile == null || request.audioFile.canonicalFile == prepared.audio.musicFile?.canonicalFile)) {
            "Request differs from its prepared graph, saved export settings or full source selection table"
        }
        require(export.fps in listOf(30, 60) && minOf(export.width, export.height) in listOf(720, 1080)) {
            "Prepared export MVP supports saved 30/60 FPS at 720/1080 only"
        }
        framing.requireTarget(OutputSize(export.width, export.height))
        requirePlanBindings(prepared.graph, prepared.framePlan, prepared.sources, prepared.ownsAnalysis)
        val outputPaths = setOf(request.outputFile.canonicalFile,
            File(request.outputFile.parentFile, request.outputFile.nameWithoutExtension + ".video.mp4").canonicalFile)
        require(outputPaths.size == 2 && prepared.sources.none { it.file.canonicalFile in outputPaths } &&
            (prepared.audio.musicFile == null || prepared.audio.musicFile!!.canonicalFile !in outputPaths) &&
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
        fun requirePlanBindings(graph: MontageGraph, plan: HighQualityFramePlan.Plan, sources: List<PreparedVideoSource>,
            ownsAnalysis: Boolean = false) {
            require(sources.isNotEmpty() && sources.map { it.selection.id }.distinct().size == sources.size &&
                sources.withIndex().all { (index, source) -> source.sourceIndex == index && source.assetId == source.selection.assetId }) {
                "Invalid prepared source selection table"
            }
            require(graph.clips.all { it.sourceIndex in sources.indices }) { "Graph source is absent from selection table" }
            require(ownsAnalysis || (graph.frameAttachments.frames.isEmpty() && graph.frameAttachments.maskRefinements.isEmpty() &&
                graph.sourceAttachments.all { it.sourceIndex in sources.indices && it.timeline.frames.isEmpty() &&
                    it.timeline.maskRefinements.isEmpty() })) { "Prepared analysis requires the budget-owned detached handoff" }
            require(graph.sourceAttachments.all { it.sourceIndex in sources.indices })
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
                require(ownsAnalysis || (frame.attachments == null && frame.secondaryAttachments == null)) {
                    "Prepared analysis frames require the budget-owned detached handoff"
                }
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
