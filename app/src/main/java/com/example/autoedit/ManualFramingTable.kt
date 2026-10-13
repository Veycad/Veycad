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
    samples: Map<SourceId, FramingPlan.Sample>
) {
    val sourceOrder: List<SourceId> = Collections.unmodifiableList(sourceOrder.toList())
    private val geometries = geometries.toMap()
    private val samples = samples.toMap()
    private val crops: List<SourceFraming.Crop>

    init {
        require(this.sourceOrder.isNotEmpty() && this.sourceOrder.distinct().size == this.sourceOrder.size)
        val ids = this.sourceOrder.toSet()
        require(this.geometries.keys == ids) { "Inspected geometry must cover the exact selection table" }
        require(this.samples.keys == ids) { "MANUAL samples must cover the exact selection table" }
        crops = this.sourceOrder.map { id ->
            val sample = this.samples.getValue(id)
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

    fun crop(sourceIndex: Int): SourceFraming.Crop {
        require(sourceIndex in sourceOrder.indices) { "Source role is absent from selection table: $sourceIndex" }
        return crops[sourceIndex]
    }

    fun requireTarget(size: OutputSize) {
        val expected = aspect.size(360)
        require(size.width.toLong() * expected.height == size.height.toLong() * expected.width) {
            "MANUAL table aspect does not match target; format changes require the resize controller"
        }
    }

    fun requireCompatible(other: ManualFramingTable) {
        require(sourceOrder == other.sourceOrder && geometries == other.geometries && aspect == other.aspect) {
            "Cached MANUAL update must retain selection order, inspected geometry and project aspect"
        }
    }

    companion object {
        private const val EPSILON = .00001f

        fun fromDraft(project: DraftProject): ManualFramingTable {
            val visual = project.visualSettings
            val samples = project.sourceOrder.associateWith { id ->
                val settings = visual.framings[FramingKey(id, visual.aspect)] ?: SourceFramingSettings()
                require(settings.mode == FramingMode.MANUAL) { "Fit background and SMART rendering are not implemented by MANUAL input" }
                FramingPlan.sample(requireNotNull(project.sourceGeometry[id]) { "Inspected source geometry is unknown" },
                    visual.aspect, settings.manual)
            }
            return ManualFramingTable(project.sourceOrder, project.sourceGeometry, visual.aspect, samples)
        }
    }
}
