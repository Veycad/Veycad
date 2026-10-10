package com.veycad.app

import java.util.Collections

/** Wrapper of an existing ProjectAsset.id, never a second asset allocator. */
data class SourceId(val value: String) {
    init { require(value.isNotBlank()) }
}

data class SourceGeometry(
    val encodedWidth: Int,
    val encodedHeight: Int,
    val rotation: Int,
    val pixelAspectRatio: Float
) {
    init {
        require(encodedWidth > 0 && encodedHeight > 0)
        require(rotation in listOf(0, 90, 180, 270))
        require(pixelAspectRatio.isFinite() && pixelAspectRatio > 0f)
    }
}

enum class SourceOwnership { IMPORTED, CAPTURE }

data class CaptureOrigin(val sessionId: String, val takeOrdinal: Int, val recommended: Boolean) {
    init { require(sessionId.isNotBlank() && takeOrdinal >= 1) }
}

enum class FramingMode { MANUAL, SMART_PERSON, BLURRED_FIT }

data class FramingSettings(
    val mode: FramingMode,
    val centerX: Float = .5f,
    val centerY: Float = .5f,
    val zoom: Float = 1f
) {
    init {
        require(centerX.isFinite() && centerX in 0f..1f)
        require(centerY.isFinite() && centerY in 0f..1f)
        require(zoom.isFinite() && zoom in 1f..3f)
    }
}

data class FramingKey(val sourceId: SourceId, val aspect: ProjectAspect)

/** Switching modes selects saved parameters rather than overwriting manual framing. */
data class SourceFramingSettings(
    val mode: FramingMode = FramingMode.MANUAL,
    val manual: FramingSettings = FramingSettings(FramingMode.MANUAL),
    val smartPerson: FramingSettings = FramingSettings(FramingMode.SMART_PERSON),
    val blurredFit: FramingSettings = FramingSettings(FramingMode.BLURRED_FIT)
) {
    init {
        require(manual.mode == FramingMode.MANUAL)
        require(smartPerson.mode == FramingMode.SMART_PERSON)
        require(blurredFit.mode == FramingMode.BLURRED_FIT)
    }
    val current: FramingSettings get() = when (mode) {
        FramingMode.MANUAL -> manual
        FramingMode.SMART_PERSON -> smartPerson
        FramingMode.BLURRED_FIT -> blurredFit
    }

    fun withMode(mode: FramingMode): SourceFramingSettings = copy(mode = mode)

    fun withSettings(settings: FramingSettings): SourceFramingSettings = when (settings.mode) {
        FramingMode.MANUAL -> copy(mode = settings.mode, manual = settings)
        FramingMode.SMART_PERSON -> copy(mode = settings.mode, smartPerson = settings)
        FramingMode.BLURRED_FIT -> copy(mode = settings.mode, blurredFit = settings)
    }
}

/** Proposed visual value for a common-core command; edits allocate no revision or history. */
class ProjectVisualSettings(
    val aspect: ProjectAspect,
    val explicitlySelected: Boolean,
    framings: Map<FramingKey, SourceFramingSettings> = emptyMap()
) {
    val framings: Map<FramingKey, SourceFramingSettings> = immutableMap(framings)

    fun withAspect(aspect: ProjectAspect, explicit: Boolean = true): ProjectVisualSettings =
        if (this.aspect == aspect && explicitlySelected == explicit) this
        else ProjectVisualSettings(aspect, explicit, framings)

    fun withFraming(key: FramingKey, settings: SourceFramingSettings): ProjectVisualSettings =
        if (framings[key] == settings) this
        else ProjectVisualSettings(aspect, explicitlySelected, framings + (key to settings))

    override fun equals(other: Any?): Boolean = other is ProjectVisualSettings &&
        aspect == other.aspect && explicitlySelected == other.explicitlySelected && framings == other.framings
    override fun hashCode(): Int = 31 * (31 * aspect.hashCode() + explicitlySelected.hashCode()) + framings.hashCode()
    override fun toString(): String = "ProjectVisualSettings(aspect=$aspect, explicitlySelected=$explicitlySelected, framings=$framings)"
}

/** CAS reference to common-core identity. Revision zero is the original revision. */
data class DraftVersion(val projectId: String, val revision: Long) {
    init { require(projectId.isNotBlank() && revision >= 0) }
}

/** Read-only projection of a common revision. Core owns clocks, assets, cuts and identity. */
internal class DraftProject(
    val project: HybridProject,
    val visualSettings: ProjectVisualSettings,
    sourceOrder: List<SourceId>,
    sourceGeometry: Map<SourceId, SourceGeometry>,
    semanticAssetIds: Map<SourceId, String> = emptyMap()
) {
    val sourceOrder: List<SourceId> = Collections.unmodifiableList(ArrayList(sourceOrder))
    val sourceGeometry: Map<SourceId, SourceGeometry> = immutableMap(sourceGeometry)
    val semanticAssetIds: Map<SourceId, String> = immutableMap(semanticAssetIds)
    val projectId: String get() = project.id
    val revision: Long get() = project.current.id
    val version: DraftVersion get() = DraftVersion(projectId, revision)
    val fps: Int get() = project.fps
    val clock: ProjectClock = ProjectClock(project.fps)
    val graph: MontageGraph get() = project.current.graph
    val clips: List<HybridClip> get() = project.current.clips
    val assets: List<ProjectAsset> get() = project.assets
    val music: ProjectMusic get() = project.current.music
    val texts: List<TextItem> get() = project.current.texts
    val style: ProjectStyle get() = project.current.style
    val lockedCutIds: Set<String> get() = project.current.lockedCutIds
    val sources: List<ProjectAsset>

    init {
        val videos = assets.filter { it.kind == ProjectAsset.Kind.VIDEO }.associateBy { SourceId(it.id) }
        val ids = this.sourceOrder.toSet()
        require(ids.size == this.sourceOrder.size && ids == videos.keys)
        require(this.sourceGeometry.keys == ids)
        require(visualSettings.framings.keys.all { it.sourceId in ids })
        require(this.semanticAssetIds.all { (id, reference) -> id in ids && reference.isNotBlank() })
        require(graph.clips.all { it.sourceIndex in this.sourceOrder.indices })
        require(clips.all { clip -> clip.original.sourceIndex in this.sourceOrder.indices &&
            this.sourceOrder[clip.original.sourceIndex].value == clip.assetId })
        sources = Collections.unmodifiableList(this.sourceOrder.map { videos.getValue(it) })
    }
}

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))
