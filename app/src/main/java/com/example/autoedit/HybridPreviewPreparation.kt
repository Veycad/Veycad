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
    val contentHash: String,
    val durationUs: Long = metadata.videoEndPtsUs ?: Long.MAX_VALUE
)

/** Borrowed detached values. Stop consumers before closing on the shared budget's GL worker. */
class PreparedHybridPreview private constructor(
    val projectId: String,
    val revisionId: Long,
    val exportSettings: ProjectExportSettings,
    graph: MontageGraph,
    sources: List<PreparedVideoSource>,
    framePlan: HighQualityFramePlan.Plan,
    renderText: TextEditProject?,
    val showAuthoredText: Boolean,
    audio: PreparedProjectAudio,
    private val budget: PreviewMemoryBudget?,
    private val reservationKey: String?
) : AutoCloseable {
    private var graphValue: MontageGraph? = graph
    private var sourcesValue: List<PreparedVideoSource>? = sources
    private var planValue: HighQualityFramePlan.Plan? = framePlan
    private var textValue = renderText
    private var audioValue: PreparedProjectAudio? = audio
    private val framingDisposals = mutableListOf<() -> Unit>()
    val graph: MontageGraph get() = checkNotNull(graphValue) { "Prepared preview is closed" }
    val sources: List<PreparedVideoSource> get() = checkNotNull(sourcesValue) { "Prepared preview is closed" }
    val framePlan: HighQualityFramePlan.Plan get() = checkNotNull(planValue) { "Prepared preview is closed" }
    val renderText: TextEditProject? get() { check(graphValue != null) { "Prepared preview is closed" }; return textValue }
    internal val audio: PreparedProjectAudio get() { check(graphValue != null) { "Prepared preview is closed" }; return checkNotNull(audioValue) }
    internal val ownsAnalysis: Boolean get() = budget != null
    internal val sharedBudget: PreviewMemoryBudget? get() = budget

    internal fun retainFraming(table: ManualFramingTable) {
        val bytes = table.smartReservationBytes()
        if (budget != null && bytes > 0) {
            val key = "prepared-framing-${UUID.randomUUID()}"
            require(budget.reserve(key, bytes, table::close)) { "SMART framing exceeds the shared preview budget" }
            budget.pin(key)
            framingDisposals += { budget.release(key) }
        } else framingDisposals += table::close
    }

    private fun invalidate() { graphValue = null; sourcesValue = null; planValue = null; textValue = null; audioValue = null }

    override fun close() {
        while (framingDisposals.isNotEmpty()) {
            framingDisposals.first().invoke()
            framingDisposals.removeAt(0)
        }
        if (budget == null) invalidate() else budget.release(reservationKey!!)
    }

    internal companion object {
        fun prepare(project: HybridProject, export: ProjectExportSettings, assets: ProjectAssetStore,
            budget: PreviewMemoryBudget? = null, scratchDirectory: File? = null,
            checkCancelled: () -> Unit = {}): PreparedHybridPreview {
            checkCancelled()
            val selections = requireNotNull(project.selectedVideos) {
                "Prepare requires a saved video selection table; reopen an inspected project instead of guessing legacy order"
            }
            require(export.fps == project.fps) { "Preparation FPS must match the saved project grid" }
            budget?.usedBytes // Enforce GL-worker ownership before preflight or detachment.
            val lifetime = budget?.let { PreparedAttachmentLifetime() }
            val key = budget?.let { "prepared-hybrid-${UUID.randomUUID()}" }
            var prepared: PreparedHybridPreview? = null
            var paged = false
            if (budget != null) {
                val eagerBytes = PreparedAttachmentSnapshot.reservationBytes(project, export.fps)
                paged = eagerBytes > 8L * 1024 * 1024
                val bytes = if (paged) PreparedAttachmentSnapshot.reservationBytes(project, export.fps, true) else eagerBytes
                require(budget.reserve(key!!, bytes) {
                    lifetime!!.dispose()
                    prepared?.invalidate()
                }) { "Analyzed prepared preview exceeds the available shared preview budget" }
                budget.pin(key)
            }
            try {
                if (paged) lifetime!!.pageStorage(scratchDirectory, checkCancelled)
                val compiled = EditableMontageCompiler.compile(EditableMontageProject(project, export))
                checkCancelled()
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
                lifetime?.sealPages()
                val byId = project.assets.associateBy { it.id }
                val sources = montageSnapshot(selections.mapIndexed { index, selection ->
                    checkCancelled()
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
                            checkCancelled()
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
                    PreparedVideoSource(index, selection, asset.id, file, metadata, asset.contentHash, asset.durationUs)
                })
                val plan = HighQualityFramePlan.build(graph, export.fps)
                require(plan.frames.all { it.sourceIndex in sources.indices &&
                    (it.secondarySourceIndex == null || it.secondarySourceIndex in sources.indices) }) {
                    "Frame source index is outside the saved selection table"
                }
                val current = project.current
                val state = current.textState
                val renderText = if (current.texts.isEmpty() && state.layers.isEmpty() && state.captions.isEmpty()) null
                    else TextEditProject(sources.first().file.absolutePath,
                        maxOf(ProjectClock(project.fps).timeUs(current.clips.last().span.endExclusive), plan.durationUs), export.width, export.height,
                        layers = lifetime?.list(state.layers) ?: montageSnapshot(state.layers),
                        captions = lifetime?.list(state.captions) ?: montageSnapshot(state.captions),
                        captionStyle = state.captionStyle, captionsEdited = state.captionsEdited, language = state.language,
                        legacyItems = lifetime?.list(current.texts) ?: montageSnapshot(current.texts), legacyFps = project.fps)
                val audio = PreparedProjectAudio.from(project, assets, checkCancelled)
                val result = PreparedHybridPreview(project.id, project.current.id, export, graph,
                    lifetime?.list(sources) ?: sources,
                    plan.copy(frames = lifetime?.list(plan.frames) ?: montageSnapshot(plan.frames)),
                    renderText, current.style.showAuthoredText, audio, budget, key)
                prepared = result
                return result
            } catch (error: Throwable) {
                // Release only this handoff's key; shared textures/readers belong to their owners.
                if (budget != null) try { budget.release(key!!) } catch (cleanup: Throwable) {
                    throw PreviewAllocationFailure(error, cleanup, AutoCloseable { budget.release(key!!) })
                }
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
        budget: PreviewMemoryBudget, scratchDirectory: File? = null, checkCancelled: () -> Unit = {}): PreparedHybridPreview =
        PreparedHybridPreview.prepare(project, export, assets, budget, scratchDirectory, checkCancelled)
}
