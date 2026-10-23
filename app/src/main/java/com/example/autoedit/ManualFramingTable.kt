package com.veycad.app

import java.util.Collections
import kotlin.math.abs

/**
 * Immutable MANUAL geometry input to the existing GL owner, indexed by selection order.
 * This is neither a compiled frame plan nor a revision/store. All entries, including unused
 * selections, must be supplied; a draw must never substitute another role's geometry.
 */
internal class ManualFramingTable(
    sourceOrder: List<SourceId>,
    geometries: Map<SourceId, SourceGeometry>,
    val aspect: ProjectAspect,
    samples: Map<SourceId, FramingPlan.Sample>,
    settings: Map<SourceId, SourceFramingSettings> = emptyMap(),
    smartTracks: Map<SourceId, SmartFramingTrack> = emptyMap()
) : AutoCloseable {
    val sourceOrder: List<SourceId> = Collections.unmodifiableList(sourceOrder.toList())
    private val geometries = geometries.toMap()
    private val samples = samples.toMap()
    private val settings = settings.toMap()
    private var smartTracks = smartTracks.toMap()
    private var closed = false
    private val crops: List<SourceFraming.Crop>

    init {
        require(this.sourceOrder.isNotEmpty() && this.sourceOrder.distinct().size == this.sourceOrder.size)
        val ids = this.sourceOrder.toSet()
        require(this.geometries.keys == ids) { "Inspected geometry must cover the exact selection table" }
        require(this.samples.keys == ids) { "MANUAL samples must cover the exact selection table" }
        require(this.settings.keys.all { it in ids } && this.smartTracks.keys.all { it in ids })
        crops = this.sourceOrder.map { id ->
            val sample = this.samples.getValue(id)
            val setting = this.settings[id] ?: SourceFramingSettings()
            if (setting.mode == FramingMode.SMART_PERSON) require(this.smartTracks[id] != null) { "SMART analysis is not ready" }
            if (setting.mode == FramingMode.BLURRED_FIT) {
                require(sample == FramingPlan.sample(this.geometries.getValue(id), aspect, setting.blurredFit))
                return@map SourceFraming.Crop(1f, 1f)
            }
            val rect = sample.foregroundSource
            require(sample.foregroundDestination == NormalizedRect(0f, 0f, 1f, 1f) &&
                sample.backgroundSource == null && sample.blurRadiusFraction == 0f) { "Only MANUAL fill is supported" }
            require(listOf(rect.left, rect.top, rect.right, rect.bottom).all { it.isFinite() && it in 0f..1f } &&
                rect.right > rect.left && rect.bottom > rect.top) { "Invalid source window" }
            val fill = FramingPlan.sample(this.geometries.getValue(id), aspect, FramingSettings(FramingMode.MANUAL)).foregroundSource
            val width = rect.right - rect.left
            val height = rect.bottom - rect.top
            val zoomX = (fill.right - fill.left) / width
            val zoomY = (fill.bottom - fill.top) / height
            require(zoomX in (1f - EPSILON)..(3f + EPSILON) &&
                zoomY in (1f - EPSILON)..(3f + EPSILON) && abs(zoomX - zoomY) <= EPSILON) {
                "Source window must preserve target aspect at MANUAL zoom 1..3"
            }
            SourceFraming.Crop(width, height, (rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)
        }
    }

    fun crop(sourceIndex: Int, sourceTimeUs: Long = 0L): SourceFraming.Crop {
        check(!closed) { "Framing input is closed" }
        require(sourceIndex in sourceOrder.indices) { "Source role is absent from selection table: $sourceIndex" }
        val id = sourceOrder[sourceIndex]
        if (settings[id]?.mode == FramingMode.SMART_PERSON) {
            val rect = sample(sourceIndex, sourceTimeUs).foregroundSource
            return SourceFraming.Crop(rect.right - rect.left, rect.bottom - rect.top,
                (rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)
        }
        return crops[sourceIndex]
    }

    fun sample(sourceIndex: Int, sourceTimeUs: Long): FramingPlan.Sample {
        check(!closed) { "Framing input is closed" }
        require(sourceIndex in sourceOrder.indices && sourceTimeUs >= 0)
        val id = sourceOrder[sourceIndex]
        val setting = settings[id] ?: SourceFramingSettings()
        if (setting.mode != FramingMode.SMART_PERSON) return samples.getValue(id)
        val point = smartTracks.getValue(id).sample(sourceTimeUs)
        return FramingPlan.sample(geometries.getValue(id), aspect,
            setting.smartPerson.copy(centerX = point.centerX, centerY = point.centerY))
    }

    fun status(sourceIndex: Int, sourceTimeUs: Long): SmartFramingStatus? {
        check(!closed) { "Framing input is closed" }
        require(sourceIndex in sourceOrder.indices && sourceTimeUs >= 0)
        val id = sourceOrder[sourceIndex]
        return if (settings[id]?.mode == FramingMode.SMART_PERSON) smartTracks.getValue(id).sample(sourceTimeUs).status else null
    }

    /** Charge compact objects once per identity, including repeated physical selections. */
    fun smartReservationBytes(): Long {
        check(!closed) { "Framing input is closed" }
        return smartTracks.values.distinct().sumOf { 512L + it.points.size * 96L + it.sourceWindows.size * 64L }
    }

    override fun close() { smartTracks = emptyMap(); closed = true }

    fun requireTarget(size: OutputSize) {
        check(!closed) { "Framing input is closed" }
        val expected = aspect.size(360)
        require(size.width.toLong() * expected.height == size.height.toLong() * expected.width) {
            "MANUAL table aspect does not match target; format changes require the resize controller"
        }
    }

    fun requireCompatible(other: ManualFramingTable) {
        check(!closed && !other.closed) { "Framing input is closed" }
        require(sourceOrder == other.sourceOrder && geometries == other.geometries && aspect == other.aspect) {
            "Cached MANUAL update must retain selection order, inspected geometry and project aspect"
        }
    }

    companion object {
        private const val EPSILON = .00001f

        fun fromDraft(project: DraftProject, smartTracks: Map<SourceId, SmartFramingTrack> = emptyMap()): ManualFramingTable {
            val visual = project.visualSettings
            val settings = project.sourceOrder.associateWith { id ->
                visual.framings[FramingKey(id, visual.aspect)] ?: SourceFramingSettings()
            }
            val samples = project.sourceOrder.associateWith { id ->
                FramingPlan.sample(requireNotNull(project.sourceGeometry[id]) { "Inspected source geometry is unknown" },
                    visual.aspect, settings.getValue(id).current)
            }
            return ManualFramingTable(project.sourceOrder, project.sourceGeometry, visual.aspect, samples, settings, smartTracks)
        }
    }
}
