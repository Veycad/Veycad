package com.veycad.app

import java.util.Collections

data class ProjectAsset(val id: String, val fileName: String, val kind: Kind,
    val durationUs: Long, val contentHash: String, val displayName: String = fileName,
    /** Null means legacy metadata has not been inspected, never synthetic geometry. */
    val videoMetadata: VideoSourceMetadata? = null) {
    enum class Kind { VIDEO, AUDIO }
    init {
        require(id.isNotBlank() && durationUs > 0 && contentHash.isNotBlank() && displayName.isNotBlank())
        requireProjectFileName(fileName)
        require(videoMetadata == null || kind == Kind.VIDEO)
    }
}

data class HybridClip(val id: String, val assetId: String, val span: FrameSpan,
    val sourceMap: SourceTimeMap, val original: MontageGraph.Clip,
    /** Original local output frame at current local frame 0; independent of source PTS/holds. */
    val originalFrameOffset: Int = 0) {
    init {
        require(id.isNotBlank() && assetId.isNotBlank())
        require(sourceMap.points.last().localFrame == span.length)
    }
}

data class ProjectMusic(val assetId: String, val startUs: Long, val gain: Float,
    val fadeInUs: Long, val fadeOutUs: Long, val repeat: Boolean) {
    init { require(assetId.isNotBlank() && startUs >= 0 && gain in 0f..2f && fadeInUs >= 0 && fadeOutUs >= 0) }
}

data class TextItem(val id: String, val text: String, val span: FrameSpan,
    val x: Float, val y: Float, val width: Float, val size: Float, val color: Int,
    val appearance: Appearance, val fadeFrames: Int) {
    enum class Appearance { PLAIN, ACCENT, BACKGROUND }
    init {
        require(id.isNotBlank())
        require(x in 0f..1f && y in 0f..1f && width > 0f && width <= 1f && size > 0f && size <= 1f)
        require(fadeFrames in 0..span.length)
    }
}

data class ProjectStyle(val recipeId: String, val recipeVersion: Int, val mode: Mode,
    val showAuthoredText: Boolean) {
    enum class Mode { AUTHORED, ADAPTIVE }
    init { require(recipeId.isNotBlank() && recipeVersion > 0) }
}

/** Editable clips own frame timing; graph retains the director/style template. */
class HybridRevision(val id: Long, val parentId: Long?, val graph: MontageGraph,
    clips: List<HybridClip>, val music: ProjectMusic, texts: List<TextItem>,
    val style: ProjectStyle, lockedCutIds: Set<String>,
    /** Event on this revision only: RestoreAutomatic selected the original source windows. */
    val restoresAutomaticSources: Boolean = false,
    val visualSettings: ProjectVisualSettings = ProjectVisualSettings(ProjectAspect.PORTRAIT_9_16, false),
    val textState: HybridTextState = HybridTextState()) {
    val clips: List<HybridClip> = snapshotList(clips)
    val texts: List<TextItem> = snapshotList(texts)
    /** A cut is identified by the incoming (right) clip; the first clip has no cut. */
    val lockedCutIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(lockedCutIds))

    init {
        require(id >= 0 && (parentId == null || parentId in 0 until id))
        require(!restoresAutomaticSources || parentId != null)
        require(this.clips.isNotEmpty() && this.clips.first().span.start == 0)
        require(this.clips.zipWithNext().all { (a, b) -> a.span.endExclusive == b.span.start })
        require(this.clips.map { it.id }.toSet().size == this.clips.size)
        require(this.texts.map { it.id }.toSet().size == this.texts.size)
        require(this.texts.all { it.span.endExclusive <= this.clips.last().span.endExclusive })
        require(this.lockedCutIds.all { cutId -> this.clips.drop(1).any { it.id == cutId } })
    }

    fun copy(id: Long = this.id, parentId: Long? = this.parentId, graph: MontageGraph = this.graph,
        clips: List<HybridClip> = this.clips, music: ProjectMusic = this.music,
        texts: List<TextItem> = this.texts, style: ProjectStyle = this.style,
        lockedCutIds: Set<String> = this.lockedCutIds,
        restoresAutomaticSources: Boolean = this.restoresAutomaticSources,
        visualSettings: ProjectVisualSettings = this.visualSettings, textState: HybridTextState = this.textState) =
        HybridRevision(id, parentId, graph, clips, music, texts, style, lockedCutIds, restoresAutomaticSources, visualSettings, textState)

    private fun values() = listOf(id, parentId, graph, clips, music, texts, style, lockedCutIds, restoresAutomaticSources, visualSettings, textState)
    override fun equals(other: Any?): Boolean = other is HybridRevision && values() == other.values()
    override fun hashCode(): Int = values().hashCode()
    override fun toString(): String = "HybridRevision(id=$id, parentId=$parentId, clips=$clips, music=$music, texts=$texts, style=$style, lockedCutIds=$lockedCutIds, restoresAutomaticSources=$restoresAutomaticSources, graph=$graph)"
}

/** nextRevisionId lives outside undoable revisions; commands retain it when moving history. */
class HybridProject(val id: String, val schemaVersion: Int, val fps: Int,
    val nextRevisionId: Long, assets: List<ProjectAsset>, val original: HybridRevision,
    val current: HybridRevision, undo: List<HybridRevision>, redo: List<HybridRevision>,
    exports: List<ProjectExportRef>, selectedVideos: List<SelectedVideo>? = null) {
    val assets: List<ProjectAsset> = snapshotList(assets)
    val undo: List<HybridRevision> = snapshotList(undo)
    val redo: List<HybridRevision> = snapshotList(redo)
    val exports: List<ProjectExportRef> = snapshotList(exports)
    /** Null is an explicitly unknown legacy order; the list retains even unused selections. */
    val selectedVideos: List<SelectedVideo>? = selectedVideos?.let { snapshotList(it) }

    init {
        require(id.isNotBlank() && schemaVersion == 1)
        ProjectClock(fps)
        require(this.assets.map { it.id }.toSet().size == this.assets.size)
        require(original.parentId == null && this.undo.size <= 50 && this.redo.size <= 50)
        require(this.undo.map { it.id }.toSet().size == this.undo.size && this.redo.map { it.id }.toSet().size == this.redo.size)
        require(this.undo.none { u -> this.redo.any { it.id == u.id } })
        require(this.undo.none { it.id == current.id } && this.redo.none { it.id == current.id })
        val revisions = listOf(original, current) + this.undo + this.redo
        require(revisions.groupBy { it.id }.values.all { versions -> versions.all { it == versions.first() } })
        require(nextRevisionId > 0 && revisions.all { it.id < nextRevisionId })
        require(this.exports.all { it.revisionId < nextRevisionId })
        val byId = this.assets.associateBy { it.id }
        this.selectedVideos?.let { selections ->
            require(selections.isNotEmpty() && selections.map { it.id }.distinct().size == selections.size)
            require(selections.all { selection -> byId[selection.assetId]?.let {
                it.kind == ProjectAsset.Kind.VIDEO && it.videoMetadata != null &&
                    it.contentHash.matches(Regex("[0-9a-f]{64}"))
            } == true }) { "Selected videos require inspected physical metadata" }
        }
        for (revision in revisions) {
            val durationUs = ProjectClock(fps).timeUs(revision.clips.last().span.endExclusive)
            require(revision.textState.layers.all { it.endUs <= durationUs } &&
                revision.textState.captions.all { it.endUs <= durationUs }) { "Text ends after the project" }
            val selectionIds = this.selectedVideos?.map { it.id }?.toSet().orEmpty()
            require(revision.visualSettings.framings.keys.all { it.sourceId in selectionIds }) {
                "Framing requires a known selected element"
            }
            this.selectedVideos?.let { selections ->
                require(revision.graph.clips.all { it.sourceIndex in selections.indices })
            }
            require(!revision.restoresAutomaticSources || revision.clips == original.clips) {
                "An automatic source reset must contain the complete original clips"
            }
            for (clip in revision.clips) {
                val asset = byId[clip.assetId]
                require(asset != null && asset.kind == ProjectAsset.Kind.VIDEO)
                this.selectedVideos?.let { selections ->
                    require(clip.original.sourceIndex in selections.indices &&
                        selections[clip.original.sourceIndex].assetId == clip.assetId) {
                        "Clip sourceIndex does not match its immutable selection binding"
                    }
                }
                require(clip.sourceMap.points.last().sourceTimeUs <= asset.durationUs)
                require(clip.sourceMap.sample(clip.span.length - 1) < asset.durationUs)
            }
            val musicAsset = byId[revision.music.assetId]
            require(musicAsset != null && musicAsset.kind == ProjectAsset.Kind.AUDIO)
            require(revision.music.startUs < musicAsset.durationUs)
        }
    }

    fun copy(id: String = this.id, schemaVersion: Int = this.schemaVersion, fps: Int = this.fps,
        nextRevisionId: Long = this.nextRevisionId, assets: List<ProjectAsset> = this.assets,
        original: HybridRevision = this.original, current: HybridRevision = this.current,
        undo: List<HybridRevision> = this.undo, redo: List<HybridRevision> = this.redo,
        exports: List<ProjectExportRef> = this.exports,
        selectedVideos: List<SelectedVideo>? = this.selectedVideos): HybridProject {
        require(nextRevisionId >= this.nextRevisionId) { "Revision IDs cannot be reused" }
        require(id != this.id || this.selectedVideos == null || this.selectedVideos == selectedVideos) {
            "Selected videos are immutable; change the selection in a new project"
        }
        return HybridProject(id, schemaVersion, fps, nextRevisionId, assets, original, current, undo, redo, exports, selectedVideos)
    }

    private fun values() = listOf(id, schemaVersion, fps, nextRevisionId, assets, original, current, undo, redo, exports, selectedVideos)
    override fun equals(other: Any?): Boolean = other is HybridProject && values() == other.values()
    override fun hashCode(): Int = values().hashCode()
    override fun toString(): String = "HybridProject(id=$id, schemaVersion=$schemaVersion, fps=$fps, nextRevisionId=$nextRevisionId, assets=$assets, original=$original, current=$current, undo=$undo, redo=$redo, exports=$exports)"
}

data class ProjectExportRef(val revisionId: Long, val fileName: String, val settings: ProjectExportSettings) {
    init { require(revisionId >= 0); requireProjectFileName(fileName) }
}

data class ProjectExportSettings(val width: Int, val height: Int, val fps: Int,
    val bitrate: Int, val audioBitrate: Int) {
    init { require(width > 0 && height > 0 && bitrate > 0 && audioBitrate > 0); ProjectClock(fps) }
}

private fun requireProjectFileName(name: String) {
    require(name.isNotBlank() && name != "." && name != "..")
    require(name.none { it == '/' || it == '\\' || it == ':' || it.code < 32 })
}

private fun <T> snapshotList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
