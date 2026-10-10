package com.veycad.app

import java.util.Collections

/** Read-only current-revision adapter. No second duration, allocator, history or store. */
internal class DraftProject(val project: HybridProject) {
    private val selections = requireNotNull(project.selectedVideos) { "Selected source order is unknown" }
    private val byId = project.assets.associateBy { it.id }
    val sourceOrder: List<SourceId> = Collections.unmodifiableList(selections.map { it.id })
    val sourceGeometry: Map<SourceId, SourceGeometry> = Collections.unmodifiableMap(
        selections.associate { it.id to requireNotNull(byId.getValue(it.assetId).videoMetadata).geometry })
    val sources: List<ProjectAsset> = Collections.unmodifiableList(selections.map { byId.getValue(it.assetId) })
    val visualSettings: ProjectVisualSettings get() = project.current.visualSettings
    val textState: HybridTextState get() = project.current.textState
    val projectId: String get() = project.id
    val revision: Long get() = project.current.id
    val version: DraftVersion get() = DraftVersion(projectId, revision)
    val fps: Int get() = project.fps
    val clock: ProjectClock get() = ProjectClock(project.fps)
    val graph: MontageGraph get() = project.current.graph
    val clips: List<HybridClip> get() = project.current.clips
    val assets: List<ProjectAsset> get() = project.assets
    val music: ProjectMusic get() = project.current.music
    val texts: List<TextItem> get() = project.current.texts
    val style: ProjectStyle get() = project.current.style
    val lockedCutIds: Set<String> get() = project.current.lockedCutIds
}
