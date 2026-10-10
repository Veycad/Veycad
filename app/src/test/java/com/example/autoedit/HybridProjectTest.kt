package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class HybridProjectTest {
    private fun clip(id: String = "clip", start: Int = 0, end: Int = 30) = HybridClip(id, "video",
        FrameSpan(start, end), SourceTimeMap(listOf(SourceTimeMap.Point(0, 0),
            SourceTimeMap.Point(end - start, 1_000_000))), MontageGraph.Clip(id, 0, 1000, 1000,
            MontageGraph.ShotRole.OPENING, MontageGraph.Transition.OPEN, MontageGraph.Motion.HOLD, 1f, 0))
    private fun revision(id: Long = 0, parent: Long? = null, clips: List<HybridClip> = listOf(clip())) =
        HybridRevision(id, parent, MontageGraph(1000, clips.size * 1000L, clips = clips.map { it.original }),
            clips, ProjectMusic("music", 0, 1f, 0, 0, false), emptyList(),
            ProjectStyle("style", 1, ProjectStyle.Mode.AUTHORED, true), emptySet())
    private fun project() = HybridProject("project", 1, 30, 1, listOf(
        ProjectAsset("video", "video.mp4", ProjectAsset.Kind.VIDEO, 1_000_000, "hash"),
        ProjectAsset("music", "music.wav", ProjectAsset.Kind.AUDIO, 2_000_000, "hash2")),
        revision(), revision(), emptyList(), emptyList(), emptyList())

    @Test fun projectRejectsGapsAndDuplicateIds() {
        assertThrows(IllegalArgumentException::class.java) { revision(clips = listOf(clip("a"), clip("b", 31, 61))) }
        assertThrows(IllegalArgumentException::class.java) { revision(clips = listOf(clip(), clip(start = 30, end = 60))) }
        assertThrows(IllegalArgumentException::class.java) { project().copy(assets = project().assets + project().assets.first()) }
        assertThrows(IllegalArgumentException::class.java) { project().copy(current = revision(1, 0).copy(clips = listOf(clip().copy(assetId = "missing"))), nextRevisionId = 2) }
        assertThrows(IllegalArgumentException::class.java) { project().copy(current = revision(1, 0).copy(music = ProjectMusic("video", 0, 1f, 0, 0, false)), nextRevisionId = 2) }
        assertThrows(IllegalArgumentException::class.java) { project().copy(current = revision(1, 0)) }
        assertThrows(IllegalArgumentException::class.java) { project().copy(undo = listOf(revision().copy(style = ProjectStyle("other", 1, ProjectStyle.Mode.AUTHORED, true)))) }
    }
    @Test fun revisionsAndExportsRetainSeparateMonotonicCounter() {
        val later = revision(2, 0)
        val advanced = project().copy(current = later, nextRevisionId = 3, undo = listOf(revision()))
        val undone = advanced.copy(current = advanced.original, undo = emptyList(), redo = listOf(later))
        assertEquals(3L, undone.nextRevisionId)
        assertEquals(2L, undone.redo.single().id)
        val settings = ProjectExportSettings(1080, 1920, 30, 8_000_000, 192_000)
        assertEquals(2L, undone.copy(exports = listOf(ProjectExportRef(2, "export.mp4", settings))).exports.single().revisionId)
        assertThrows(IllegalArgumentException::class.java) { undone.copy(nextRevisionId = 2) }
    }
    @Test fun rejectsInvalidRelativeNamesAndValueRanges() {
        for (name in listOf("../video.mp4", "C:\\video.mp4", "/video.mp4", "nested/video.mp4", "", "..")) {
            assertThrows(IllegalArgumentException::class.java) { project().assets.first().copy(fileName = name) }
        }
        assertThrows(IllegalArgumentException::class.java) { ProjectMusic("music", -1, 1f, 0, 0, false) }
        assertThrows(IllegalArgumentException::class.java) { ProjectMusic("music", 0, Float.NaN, 0, 0, false) }
        assertThrows(IllegalArgumentException::class.java) { TextItem("text", "Hi", FrameSpan(0, 30), 0f, 0f, 1f, 0f, 0, TextItem.Appearance.PLAIN, 0) }
        assertThrows(IllegalArgumentException::class.java) { clip().copy(sourceMap = SourceTimeMap(listOf(SourceTimeMap.Point(0, 0), SourceTimeMap.Point(29, 1000)))) }
        assertThrows(IllegalArgumentException::class.java) { project().copy(assets = project().assets.map { if (it.id == "video") it.copy(durationUs = 500_000) else it }) }
        assertThrows(IllegalArgumentException::class.java) { revision().copy(lockedCutIds = setOf("missing")) }
        assertThrows(IllegalArgumentException::class.java) { project().copy(fps = 24) }
        assertThrows(IllegalArgumentException::class.java) { ProjectExportSettings(0, 1920, 30, 1, 1) }
    }

    @Test fun lockedCutsReferToIncomingClipsAndVisibleSamplesPrecedeSourceEnd() {
        assertThrows(IllegalArgumentException::class.java) { revision().copy(lockedCutIds = setOf("clip")) }
        val pair = revision(clips = listOf(clip("left"), clip("right", 30, 60)))
        assertEquals(setOf("right"), pair.copy(lockedCutIds = setOf("right")).lockedCutIds)
        val ended = clip().copy(sourceMap = SourceTimeMap(listOf(SourceTimeMap.Point(0, 1_000_000),
            SourceTimeMap.Point(30, 1_000_000))))
        assertThrows(IllegalArgumentException::class.java) { project().copy(current = revision(1, 0, listOf(ended)), nextRevisionId = 2) }
    }

    @Test fun revisionSnapshotsCallerCollectionsIncludingCopy() {
        val clips = mutableListOf(clip("left"), clip("right", 30, 60))
        val texts = mutableListOf(TextItem("title", "Hi", FrameSpan(0, 30), .5f, .5f, .8f, .1f, 0,
            TextItem.Appearance.PLAIN, 3))
        val locked = mutableSetOf("right")
        val snapshot = revision(clips = clips).copy(texts = texts, lockedCutIds = locked)
        clips.clear(); texts.clear(); locked.clear()
        assertEquals(2, snapshot.clips.size)
        assertEquals("Hi", snapshot.texts.single().text)
        assertEquals(setOf("right"), snapshot.lockedCutIds)
        val replacements = mutableListOf(clip())
        val copied = snapshot.copy(clips = replacements, lockedCutIds = emptySet())
        replacements.clear()
        assertEquals(1, copied.clips.size)
    }

    @Test fun projectSnapshotsCallerCollectionsIncludingCopy() {
        val assets = project().assets.toMutableList()
        val undo = mutableListOf(revision())
        val redo = mutableListOf(revision(3, 2))
        val exports = mutableListOf(ProjectExportRef(2, "out.mp4", ProjectExportSettings(1080, 1920, 30, 1, 1)))
        val snapshot = project().copy(assets = assets, current = revision(2, 0), nextRevisionId = 4,
            undo = undo, redo = redo, exports = exports)
        assets.clear(); undo.clear(); redo.clear(); exports.clear()
        assertEquals(2, snapshot.assets.size)
        assertEquals(0L, snapshot.undo.single().id)
        assertEquals(3L, snapshot.redo.single().id)
        assertEquals("out.mp4", snapshot.exports.single().fileName)
        assertEquals(snapshot, snapshot.copy())
        assertEquals(snapshot.hashCode(), snapshot.copy().hashCode())
    }

    @Test fun assetsKeepUserVisibleNameSeparateFromStoredName() {
        val asset = project().assets.last()
        assertEquals("music.wav", asset.displayName)
        assertEquals("My song", asset.copy(displayName = "My song").displayName)
        assertEquals("music.wav", asset.copy(displayName = "My song").fileName)
        assertThrows(IllegalArgumentException::class.java) { asset.copy(displayName = " ") }
    }

    @Test fun copyingCannotReusePreviouslyAllocatedRevisionIds() {
        val retained = project().copy(nextRevisionId = 9)
        assertThrows(IllegalArgumentException::class.java) { retained.copy(nextRevisionId = 1) }
        assertEquals(9L, retained.copy(current = retained.original).nextRevisionId)
    }
}
