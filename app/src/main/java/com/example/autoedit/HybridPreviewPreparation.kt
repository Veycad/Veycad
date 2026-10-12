package com.veycad.app

import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** One saved selection slot; duplicate files retain distinct selections and source indices. */
data class PreparedVideoSource(
    val sourceIndex: Int,
    val selection: SelectedVideo,
    val assetId: String,
    val file: File,
    val metadata: VideoSourceMetadata,
    val contentHash: String
)

/** Borrowed detached values. Stop consumers before closing on the shared budget's GL worker. */
class PreparedHybridPreview private constructor(
    val projectId: String,
    val revisionId: Long,
    val exportSettings: ProjectExportSettings,
    graph: MontageGraph,
    sources: List<PreparedVideoSource>,
    framePlan: HighQualityFramePlan.Plan,
    private val budget: PreviewMemoryBudget?,
    private val reservationKey: String?
) : AutoCloseable {
    private var graphValue: MontageGraph? = graph
    private var sourcesValue: List<PreparedVideoSource>? = sources
    private var planValue: HighQualityFramePlan.Plan? = framePlan
    val graph: MontageGraph get() = checkNotNull(graphValue) { "Prepared preview is closed" }
    val sources: List<PreparedVideoSource> get() = checkNotNull(sourcesValue) { "Prepared preview is closed" }
    val framePlan: HighQualityFramePlan.Plan get() = checkNotNull(planValue) { "Prepared preview is closed" }

    private fun invalidate() { graphValue = null; sourcesValue = null; planValue = null }

    override fun close() {
        if (budget == null) invalidate() else budget.release(reservationKey!!)
    }

    internal companion object {
        fun prepare(project: HybridProject, export: ProjectExportSettings, assets: ProjectAssetStore,
            budget: PreviewMemoryBudget? = null): PreparedHybridPreview {
            val selections = requireNotNull(project.selectedVideos) {
                "Prepare requires a saved video selection table; reopen an inspected project instead of guessing legacy order"
            }
            require(export.fps == project.fps) { "Preparation FPS must match the saved project grid" }
            budget?.usedBytes // Enforce GL-worker ownership before preflight or detachment.
            val lifetime = budget?.let { PreparedAttachmentLifetime() }
            val key = budget?.let { "prepared-hybrid-${UUID.randomUUID()}" }
            var prepared: PreparedHybridPreview? = null
            if (budget != null) {
                val bytes = PreparedAttachmentSnapshot.reservationBytes(project, export.fps)
                require(budget.reserve(key!!, bytes) {
                    lifetime!!.dispose()
                    prepared?.invalidate()
                }) { "Analyzed prepared preview exceeds the available shared preview budget" }
                budget.pin(key)
            }
            try {
                val compiled = EditableMontageCompiler.compile(EditableMontageProject(project, export))
                fun emptyTimeline(timeline: FrameAttachmentTimeline): FrameAttachmentTimeline {
                    require(timeline.frames.isEmpty() && timeline.maskRefinements.isEmpty()) {
                        "Prepared preview MVP supports analysis-free graphs only; analysis-backed winners need a safe attachment handoff"
                    }
                    // Even an originally empty mutable list must not escape this boundary.
                    return FrameAttachmentTimeline(montageSnapshot(emptyList()), montageSnapshot(emptyList()))
                }
                require(compiled.sourceAttachments.all { it.sourceIndex in selections.indices }) {
                    "Attachment source index is outside the saved selection table"
                }
                val graph = if (lifetime != null) PreparedAttachmentSnapshot(lifetime).graph(compiled)
                    else compiled.copy(frameAttachments = emptyTimeline(compiled.frameAttachments),
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
                val result = PreparedHybridPreview(project.id, project.current.id, export, graph,
                    lifetime?.list(sources) ?: sources,
                    plan.copy(frames = lifetime?.list(plan.frames) ?: montageSnapshot(plan.frames)), budget, key)
                prepared = result
                return result
            } catch (error: Throwable) {
                // Release only this handoff's key; shared textures/readers belong to their owners.
                if (budget != null) budget.release(key!!)
                throw error
            }
        }
    }
}

/**
 * Prepare only the installed current revision using the existing compiler and canonical frame plan.
 * The three-argument path remains analysis-free. The shared-budget overload preserves analysis.
 * No encoder is created; callers enter/dispose on the budget's GL worker and stop consumers first.
 * Serialize against editing and retain project source bytes unchanged until all consumers stop.
 */
object HybridPreviewPreparation {
    fun prepare(project: HybridProject, export: ProjectExportSettings, assets: ProjectAssetStore): PreparedHybridPreview =
        PreparedHybridPreview.prepare(project, export, assets)

    fun prepare(project: HybridProject, export: ProjectExportSettings, assets: ProjectAssetStore,
        budget: PreviewMemoryBudget): PreparedHybridPreview = PreparedHybridPreview.prepare(project, export, assets, budget)
}
