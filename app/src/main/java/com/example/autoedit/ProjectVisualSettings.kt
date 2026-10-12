package com.veycad.app

import java.util.Collections

/** Identity of a selected element, not its physical asset; repeated files keep distinct IDs. */
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

/** Common revision visual value; edits allocate no revision or history. */
class ProjectVisualSettings(
    val aspect: ProjectAspect,
    val explicitlySelected: Boolean,
    framings: Map<FramingKey, SourceFramingSettings> = emptyMap()
) {
    val framings: Map<FramingKey, SourceFramingSettings> = immutableMap(framings)

    fun copy(aspect: ProjectAspect = this.aspect, explicitlySelected: Boolean = this.explicitlySelected,
        framings: Map<FramingKey, SourceFramingSettings> = this.framings) =
        ProjectVisualSettings(aspect, explicitlySelected, framings)

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


private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))
