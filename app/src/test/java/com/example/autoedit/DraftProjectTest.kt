package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class DraftProjectTest {
    @Test fun defaults_fear_square_others_portrait() {
        MontageStyleCatalog.Recipe.entries.forEach {
            assertEquals(if (it == MontageStyleCatalog.Recipe.FEAR_STROBE) ProjectAspect.SQUARE_1_1
                else ProjectAspect.PORTRAIT_9_16, ProjectFormats.defaultFor(it))
        }
    }
    @Test fun explicit_format_survives_recipe_change() {
        val selection = ProjectFormatSelection(ProjectAspect.PORTRAIT_9_16, true)
        assertEquals(selection, ProjectFormats.forRecipe(MontageStyleCatalog.Recipe.FEAR_STROBE, selection))
        assertEquals(ProjectAspect.SQUARE_1_1, ProjectFormats.forRecipe(MontageStyleCatalog.Recipe.FEAR_STROBE,
            selection.copy(explicitlySelected = false)).aspect)
    }
    @Test fun settings_round_trip_between_aspects_and_modes() {
        val p = PreviewTestFixtures.project(sourceCount = 2)
        val key = FramingKey(p.sourceOrder.first(), p.visualSettings.aspect)
        val manual = FramingSettings(FramingMode.MANUAL, .3f, .6f, 3f)
        val settings = SourceFramingSettings().withSettings(manual)
            .withSettings(FramingSettings(FramingMode.SMART_PERSON, zoom = 2f))
            .withSettings(FramingSettings(FramingMode.BLURRED_FIT, zoom = 1.5f))
        val squareKey = key.copy(aspect = ProjectAspect.SQUARE_1_1)
        val secondKey = key.copy(sourceId = p.sourceOrder.last())
        val visual = p.visualSettings.withFraming(key, settings)
            .withFraming(squareKey, SourceFramingSettings())
            .withFraming(secondKey, SourceFramingSettings())
            .withAspect(ProjectAspect.SQUARE_1_1).withAspect(key.aspect)
        val restored = visual.framings.getValue(key)
        assertEquals(3f, restored.withMode(FramingMode.MANUAL).current.zoom)
        assertEquals(manual, restored.withMode(FramingMode.MANUAL).current)
        assertEquals(2f, restored.withMode(FramingMode.SMART_PERSON).current.zoom)
        assertEquals(1.5f, restored.withMode(FramingMode.BLURRED_FIT).current.zoom)
        assertEquals(1f, visual.framings.getValue(squareKey).current.zoom)
        assertEquals(1f, visual.framings.getValue(secondKey).current.zoom)
    }
    @Test fun adapter_reads_exact_core_identity_timing_and_manual_content() {
        val p = PreviewTestFixtures.project(sourceCount = 2)
        val core = p.project
        assertEquals(core.id, p.projectId)
        assertEquals(0L, p.revision)
        assertEquals(DraftVersion(core.id, core.current.id), p.version)
        assertEquals(core.fps, p.fps)
        assertEquals(ProjectClock(core.fps), p.clock)
        assertSame(core.current.graph, p.graph)
        assertSame(core.current.clips, p.clips)
        assertSame(core.assets, p.assets)
        assertEquals(listOf("video-0", "video-1"), p.sources.map { it.id })
        assertSame(core.current.clips.last().sourceMap, p.clips.last().sourceMap)
        assertSame(core.current.music, p.music)
        assertSame(core.current.texts, p.texts)
        assertSame(core.current.style, p.style)
        assertEquals(core.current.lockedCutIds, p.lockedCutIds)
    }
    @Test fun visual_edits_leave_core_revision_and_timing_until_core_supplies_revision() {
        val p = PreviewTestFixtures.project()
        val proposal = p.visualSettings.withAspect(ProjectAspect.LANDSCAPE_16_9)
        val pending = DraftProject(p.project, proposal, p.sourceOrder, p.sourceGeometry)
        assertEquals(0L, pending.revision)
        assertSame(p.graph, pending.graph)
        assertEquals(p.clips, pending.clips)
        val allocated = p.project.current.copy(id = p.project.nextRevisionId, parentId = p.revision)
        val updatedCore = p.project.copy(current = allocated, nextRevisionId = 2,
            undo = listOf(p.project.current))
        val received = DraftProject(updatedCore, proposal, p.sourceOrder, p.sourceGeometry)
        assertEquals(1L, received.revision)
        assertEquals(p.projectId, received.projectId)
        assertEquals(p.clips, received.clips)
    }
    @Test fun snapshots_all_incoming_visual_and_adapter_collections() {
        val p = PreviewTestFixtures.project()
        val key = FramingKey(p.sourceOrder.single(), p.visualSettings.aspect)
        val map = mutableMapOf(key to SourceFramingSettings())
        val visual = ProjectVisualSettings(p.visualSettings.aspect, false, map)
        val order = p.sourceOrder.toMutableList()
        val geometry = p.sourceGeometry.toMutableMap()
        val semantic = p.semanticAssetIds.toMutableMap()
        val snapshot = DraftProject(p.project, visual, order, geometry, semantic)
        map.clear(); order.clear(); geometry.clear(); semantic.clear()
        assertEquals(1, snapshot.visualSettings.framings.size)
        assertEquals(1, snapshot.sourceOrder.size)
        assertEquals(1, snapshot.sourceGeometry.size)
        assertEquals(1, snapshot.semanticAssetIds.size)
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.sourceOrder as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.visualSettings.framings as MutableMap).clear() }
    }
    @Test fun rejects_invalid_zoom_nan_geometry_and_core_identity() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, .9f, 3.1f).forEach {
            assertThrows(IllegalArgumentException::class.java) { FramingSettings(FramingMode.MANUAL, zoom = it) }
        }
        assertThrows(IllegalArgumentException::class.java) { FramingSettings(FramingMode.MANUAL, centerX = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { SourceGeometry(0, 1080, 0, 1f) }
        assertThrows(IllegalArgumentException::class.java) { SourceGeometry(1920, 1080, 45, 1f) }
        assertThrows(IllegalArgumentException::class.java) { SourceGeometry(1920, 1080, 0, Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { SourceId(" ") }
        assertThrows(IllegalArgumentException::class.java) { DraftVersion(" ", 0) }
        assertThrows(IllegalArgumentException::class.java) { DraftVersion("project", -1) }
        assertEquals(DraftVersion("project", 0), DraftVersion("project", 0))
        assertThrows(IllegalArgumentException::class.java) { CaptureOrigin("session", 0, false) }
        assertThrows(IllegalArgumentException::class.java) {
            SourceFramingSettings(manual = FramingSettings(FramingMode.SMART_PERSON))
        }
    }
    @Test fun rejects_unknown_source_ids_and_invalid_graph_index_binding() {
        val p = PreviewTestFixtures.project(sourceCount = 2)
        val missing = SourceId("missing")
        assertThrows(IllegalArgumentException::class.java) {
            DraftProject(p.project, p.visualSettings.withFraming(FramingKey(missing, p.visualSettings.aspect),
                SourceFramingSettings()), p.sourceOrder, p.sourceGeometry)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DraftProject(p.project, p.visualSettings, p.sourceOrder.reversed(), p.sourceGeometry)
        }
        val badGraph = p.graph.copy(clips = p.graph.clips.map { it.copy(sourceIndex = 2) })
        val badRevision = p.project.current.copy(id = 1, parentId = 0, graph = badGraph)
        val badCore = p.project.copy(current = badRevision, nextRevisionId = 2)
        assertThrows(IllegalArgumentException::class.java) {
            DraftProject(badCore, p.visualSettings, p.sourceOrder, p.sourceGeometry)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DraftProject(p.project, p.visualSettings, p.sourceOrder, p.sourceGeometry, mapOf(missing to "semantic"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DraftProject(p.project, p.visualSettings, listOf(SourceId("music")), p.sourceGeometry)
        }
    }
}
