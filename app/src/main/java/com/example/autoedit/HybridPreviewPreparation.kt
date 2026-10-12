package com.veycad.app

import java.io.File
import java.security.MessageDigest

/** One saved selection slot; duplicate files retain distinct selections and source indices. */
data class PreparedVideoSource(
    val sourceIndex: Int,
    val selection: SelectedVideo,
    val assetId: String,
    val file: File,
    val metadata: VideoSourceMetadata,
    val contentHash: String
)

/** Detached analysis-free values. Physical project files remain owned and retained by the caller. */
class PreparedHybridPreview private constructor(
    val projectId: String,
    val revisionId: Long,
    val exportSettings: ProjectExportSettings,
    val graph: MontageGraph,
    val sources: List<PreparedVideoSource>,
    val framePlan: HighQualityFramePlan.Plan
) {
    internal companion object {
        fun prepare(project: HybridProject, export: ProjectExportSettings, assets: ProjectAssetStore): PreparedHybridPreview {
            val selections = requireNotNull(project.selectedVideos) {
                "Prepare requires a saved video selection table; reopen an inspected project instead of guessing legacy order"
            }
            require(export.fps == project.fps) { "Preparation FPS must match the saved project grid" }
            val compiled = EditableMontageCompiler.compile(EditableMontageProject(project, export))
            fun emptyTimeline(timeline: FrameAttachmentTimeline): FrameAttachmentTimeline {
                require(timeline.frames.isEmpty() && timeline.maskRefinements.isEmpty()) {
                    "Prepared preview MVP supports analysis-free graphs only; analysis-backed winners need a safe attachment handoff"
                }
                // Even an originally empty mutable list must not escape this boundary.
                return FrameAttachmentTimeline(montageSnapshot(emptyList()), montageSnapshot(emptyList()))
            }
            val graph = compiled.copy(frameAttachments = emptyTimeline(compiled.frameAttachments),
                sourceAttachments = montageSnapshot(compiled.sourceAttachments.map {
                    require(it.sourceIndex in selections.indices) { "Attachment source index is outside the saved selection table" }
                    it.copy(timeline = emptyTimeline(it.timeline))
                }))
            require(graph.clips.all { it.sourceIndex in selections.indices }) {
                "Clip source index is outside the saved selection table"
            }
            val byId = project.assets.associateBy { it.id }
            val sources = montageSnapshot(selections.mapIndexed { index, selection ->
                val asset = requireNotNull(byId[selection.assetId]) { "Selected video asset is missing" }
                require(asset.kind == ProjectAsset.Kind.VIDEO && asset.contentHash.matches(Regex("[0-9a-f]{64}"))) {
                    "Selected video requires a VIDEO asset with a SHA-256 identity"
                }
                val metadata = requireNotNull(asset.videoMetadata) { "Selected video requires inspected metadata and geometry" }
                val file = assets.resolve(asset)
                require(file.isFile && file.length() == metadata.sizeBytes) { "Owned selected video is missing or its size changed" }
                val digest = MessageDigest.getInstance("SHA-256")
                var byteCount = 0L
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        byteCount = Math.addExact(byteCount, count.toLong())
                    }
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                require(byteCount == metadata.sizeBytes && actualHash == asset.contentHash) {
                    "Owned selected video no longer matches its inspected size and SHA-256 identity"
                }
                PreparedVideoSource(index, selection, asset.id, file, metadata, asset.contentHash)
            })
            val plan = HighQualityFramePlan.build(graph, export.fps)
            require(plan.frames.all { it.sourceIndex in sources.indices &&
                (it.secondarySourceIndex == null || it.secondarySourceIndex in sources.indices) }) {
                "Frame source index is outside the saved selection table"
            }
            return PreparedHybridPreview(project.id, project.current.id, export, graph, sources,
                plan.copy(frames = montageSnapshot(plan.frames)))
        }
    }
}

/**
 * Prepare only the installed current revision using the existing compiler and canonical frame plan.
 * The MVP rejects analysis-backed graphs; it does not strip their planes. No encoder is created.
 * Serialize against editing and retain project source bytes unchanged until all consumers stop.
 */
object HybridPreviewPreparation {
    fun prepare(project: HybridProject, export: ProjectExportSettings, assets: ProjectAssetStore): PreparedHybridPreview =
        PreparedHybridPreview.prepare(project, export, assets)
}
