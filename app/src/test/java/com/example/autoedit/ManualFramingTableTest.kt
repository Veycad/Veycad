package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class ManualFramingTableTest {
    private val id = SourceId("selection")
    private val square = SourceGeometry(1000, 1000, 0, 1f)
    private val whole = NormalizedRect(0f, 0f, 1f, 1f)

    @Test fun off_center_edges_and_zoom_limits_preserve_all_four_exact_aspects() {
        // Literal edges: source is square; zoom 3, requested center at the upper right.
        val edges = listOf(
            ProjectAspect.PORTRAIT_9_16 to NormalizedRect(.8125f, 0f, 1f, 1f / 3f),
            ProjectAspect.LANDSCAPE_16_9 to NormalizedRect(2f / 3f, 0f, 1f, .1875f),
            ProjectAspect.SQUARE_1_1 to NormalizedRect(2f / 3f, 0f, 1f, 1f / 3f),
            ProjectAspect.FEED_4_5 to NormalizedRect(11f / 15f, 0f, 1f, 1f / 3f))
        for ((aspect, expected) in edges) {
            val actual = table(aspect, square, FramingSettings(FramingMode.MANUAL, 1f, 0f, 3f)).crop(0)
            assertEquals(expected.left, actual.sourceX(0f), .000001f)
            assertEquals(expected.top, actual.sourceY(0f), .000001f)
            assertEquals(expected.right, actual.sourceX(1f), .000001f)
            assertEquals(expected.bottom, actual.sourceY(1f), .000001f)
            val centered = table(aspect, square, FramingSettings(FramingMode.MANUAL)).crop(0)
            val legacy = SourceFraming.crop(1000, 1000, 0, aspect.size(360).width, aspect.size(360).height)
            for (position in listOf(0f, .25f, .5f, 1f)) {
                assertEquals(legacy.sourceX(position), centered.sourceX(position), 0f)
                assertEquals(legacy.sourceY(position), centered.sourceY(position), 0f)
            }
        }
    }

    @Test fun rotation_and_sar_affect_only_the_window_dimensions_once() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val crop = table(ProjectAspect.SQUARE_1_1, SourceGeometry(400, 200, rotation, 2f),
                FramingSettings(FramingMode.MANUAL, .2f, .7f, 2f)).crop(0)
            if (rotation % 180 == 0) {
                assertEquals(.1375f, crop.sourceX(0f), .000001f)
                assertEquals(.2625f, crop.sourceX(1f), .000001f)
                assertEquals(.45f, crop.sourceY(0f), .000001f)
                assertEquals(.95f, crop.sourceY(1f), .000001f)
            } else {
                assertEquals(0f, crop.sourceX(0f), .000001f)
                assertEquals(.5f, crop.sourceX(1f), .000001f)
                assertEquals(.6375f, crop.sourceY(0f), .000001f)
                assertEquals(.7625f, crop.sourceY(1f), .000001f)
            }
        }
    }

    @Test fun draft_adapter_uses_selection_identity_and_keeps_unused_table_positions() {
        val core = PreviewTestFixtures.core(sourceCount = 1)
        val first = core.selectedVideos!!.single()
        val unused = first.copy(id = SourceId("unused"))
        val repeat = first.copy(id = SourceId("same-file-again"))
        val aspect = ProjectAspect.SQUARE_1_1
        val visual = ProjectVisualSettings(aspect, true, mapOf(
            FramingKey(first.id, aspect) to SourceFramingSettings(manual = FramingSettings(FramingMode.MANUAL, .2f, .5f, 2f)),
            FramingKey(repeat.id, aspect) to SourceFramingSettings(manual = FramingSettings(FramingMode.MANUAL, .8f, .5f, 2f))))
        val revision = core.current.copy(visualSettings = visual)
        val draft = DraftProject(core.copy(id = "repeated-selections", selectedVideos = listOf(first, unused, repeat), original = revision, current = revision))
        val table = ManualFramingTable.fromDraft(draft)
        assertEquals(listOf(first.id, unused.id, repeat.id), table.sourceOrder)
        assertEquals(.2f, table.crop(0).sourceX(.5f), .000001f)
        assertEquals(.5f, table.crop(1).sourceX(.5f), .000001f)
        assertEquals(.8f, table.crop(2).sourceX(.5f), .000001f)
        assertThrows(IllegalArgumentException::class.java) { table.crop(3) }
        val reordered = ManualFramingTable(listOf(repeat.id, unused.id, first.id), draft.sourceGeometry, aspect,
            draft.sourceOrder.associateWith { FramingPlan.sample(draft.sourceGeometry.getValue(it), aspect,
                visual.framings[FramingKey(it, aspect)]?.manual ?: FramingSettings(FramingMode.MANUAL)) })
        assertEquals(.8f, reordered.crop(0).sourceX(.5f), .000001f)
        assertThrows(IllegalArgumentException::class.java) { reordered.requireCompatible(table) }
    }

    @Test fun supplied_samples_are_snapshots_and_reject_missing_roles_stretch_fit_and_bad_zoom() {
        val sample = FramingPlan.Sample(NormalizedRect(.125f, .0625f, .625f, .5625f), whole, null, 0f)
        val ids = mutableListOf(id)
        val geometries = mutableMapOf(id to square)
        val samples = mutableMapOf(id to sample)
        val table = ManualFramingTable(ids, geometries, ProjectAspect.SQUARE_1_1, samples)
        ids.clear(); geometries.clear(); samples.clear()
        assertEquals(.125f, table.crop(0).sourceX(0f), 0f)
        table.requireTarget(OutputSize(720, 720))
        assertThrows(IllegalArgumentException::class.java) { table.requireTarget(OutputSize(720, 900)) }
        assertThrows(IllegalArgumentException::class.java) { table.crop(-1) }
        fun rejected(value: FramingPlan.Sample) = assertThrows(IllegalArgumentException::class.java) {
            ManualFramingTable(listOf(id), mapOf(id to square), ProjectAspect.SQUARE_1_1, mapOf(id to value))
        }
        rejected(sample.copy(foregroundSource = NormalizedRect(0f, 0f, .5f, 1f)))
        rejected(sample.copy(foregroundSource = NormalizedRect(0f, 0f, .25f, .25f)))
        rejected(sample.copy(foregroundSource = NormalizedRect(Float.NaN, 0f, 1f, 1f)))
        rejected(sample.copy(foregroundDestination = NormalizedRect(0f, 0f, .5f, .5f)))
        rejected(sample.copy(backgroundSource = whole, blurRadiusFraction = .06f))
        assertThrows(IllegalArgumentException::class.java) {
            ManualFramingTable(listOf(id), mapOf(id to square), ProjectAspect.SQUARE_1_1, emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualFramingTable(listOf(id), emptyMap(), ProjectAspect.SQUARE_1_1, mapOf(id to sample))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualFramingTable(listOf(id, id), mapOf(id to square), ProjectAspect.SQUARE_1_1, mapOf(id to sample))
        }
        val changedGeometry = this.table(ProjectAspect.SQUARE_1_1, SourceGeometry(1000, 1000, 180, 1f), FramingSettings(FramingMode.MANUAL))
        assertThrows(IllegalArgumentException::class.java) { table.requireCompatible(changedGeometry) }
        for (mode in listOf(FramingMode.BLURRED_FIT, FramingMode.SMART_PERSON)) {
            val core = PreviewTestFixtures.core()
            val visual = core.current.visualSettings.withFraming(FramingKey(core.selectedVideos!!.single().id,
                core.current.visualSettings.aspect), SourceFramingSettings(mode = mode))
            val revision = core.current.copy(visualSettings = visual)
            assertThrows(IllegalArgumentException::class.java) {
                ManualFramingTable.fromDraft(DraftProject(core.copy(original = revision, current = revision)))
            }
        }
    }

    private fun table(aspect: ProjectAspect, geometry: SourceGeometry, settings: FramingSettings) =
        ManualFramingTable(listOf(id), mapOf(id to geometry), aspect,
            mapOf(id to FramingPlan.sample(geometry, aspect, settings)))
}
