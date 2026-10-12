package com.veycad.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Preview adoption exercises the real common import/store/command boundary. */
class DraftCoreIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun repeated_physical_video_keeps_selection_framing_order_and_unused_selection_after_reopen() {
        val root = temporary.newFolder()
        val store = HybridProjectStore(root)
        val initial = importedProject(store)
        store.create(initial)
        val first = FramingSettings(FramingMode.MANUAL, .2f, .3f, 3f)
        val second = FramingSettings(FramingMode.MANUAL, .8f, .7f, 1.5f)
        val visual = initial.current.visualSettings
            .withFraming(key("first"), SourceFramingSettings().withSettings(first))
            .withFraming(key("second"), SourceFramingSettings().withSettings(second))
        val reordered = initial.current.clips.reversed().mapIndexed { index, clip ->
            clip.copy(span = FrameSpan(index * initial.fps, (index + 1) * initial.fps))
        }
        val edited = HybridEditCommands.commitRevision(initial,
            // The locked cut belongs to the incoming clip, never the first clip after reordering.
            initial.current.copy(clips = reordered, lockedCutIds = setOf("clip-0"), visualSettings = visual))
        store.save(edited, initial.current.id)
        val reopened = HybridProjectStore(root).load(initial.id)
        val draft = DraftProject(reopened)
        assertEquals(listOf("first", "second", "unused"), draft.sourceOrder.map { it.value })
        assertEquals(listOf(1, 0), draft.clips.map { it.original.sourceIndex })
        assertEquals(setOf("clip-0"), draft.lockedCutIds)
        assertEquals(listOf("second", "first"), draft.clips.map { draft.sourceOrder[it.original.sourceIndex].value })
        assertEquals(1, draft.sources.map { it.id }.distinct().size)
        assertEquals(first, draft.visualSettings.framings.getValue(key("first")).manual)
        assertEquals(second, draft.visualSettings.framings.getValue(key("second")).manual)
        assertEquals(listOf("First", "Second", "Unused"), reopened.selectedVideos!!.map { it.displayName })
        assertEquals(CaptureOrigin("capture", 2, true), reopened.selectedVideos!![1].captureOrigin)
        assertEquals(initial.selectedVideos, reopened.selectedVideos)
        assertEquals(initial.assets, reopened.assets)
        assertEquals(SourceGeometry(1920, 1080, 90, 1.2f), draft.sourceGeometry.getValue(SourceId("second")))
    }

    @Test fun visual_command_CAS_and_undo_preserve_core_timing_music_and_both_text_models() {
        val root = temporary.newFolder()
        val store = HybridProjectStore(root)
        val initial = importedProject(store)
        store.create(initial)
        val draft = DraftProject(initial)
        val proposal = draft.visualSettings.withAspect(ProjectAspect.LANDSCAPE_16_9)
        assertEquals(0L, draft.revision)
        assertEquals(ProjectAspect.PORTRAIT_9_16, draft.visualSettings.aspect)
        val edited = HybridEditCommands.commitRevision(initial, initial.current.copy(visualSettings = proposal))
        assertEquals(1L, edited.current.id)
        assertEquals(0L, edited.current.parentId)
        assertEquals(2L, edited.nextRevisionId)
        assertSame(edited, HybridEditCommands.commitRevision(edited, edited.current.copy()))
        store.save(edited, 0)
        assertThrows(IllegalStateException::class.java) { store.save(edited, 0) }
        val loaded = HybridProjectStore(root).load(initial.id)
        val received = DraftProject(loaded)
        assertEquals(DraftVersion(initial.id, 1), received.version)
        assertSame(loaded.current.visualSettings, received.visualSettings)
        assertSame(loaded.current.textState, received.textState)
        assertEquals(draft.clock, received.clock)
        assertEquals(draft.graph, received.graph)
        assertEquals(draft.clips, received.clips)
        assertEquals(draft.music, received.music)
        assertEquals(draft.texts, received.texts)
        assertEquals(draft.textState, received.textState)
        assertEquals(draft.style, received.style)
        assertEquals(draft.lockedCutIds, received.lockedCutIds)
        val undone = HybridEditCommands.undo(loaded)
        store.save(undone, 1)
        val restored = HybridProjectStore(root).load(initial.id)
        assertEquals(initial.current, restored.current)
        assertEquals(0L, DraftProject(restored).revision)
        assertEquals(2L, restored.nextRevisionId)
        assertEquals(edited.current, store.loadRevision(initial.id, 1))
        val redone = HybridEditCommands.redo(restored)
        store.save(redone, 0)
        assertEquals(edited.current, store.load(initial.id).current)
    }

    @Test fun unknown_legacy_order_is_rejected_without_invented_selection_or_metadata() {
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        for (version in 1..3) {
            val bytes = javaClass.getResourceAsStream("/hybrid-v$version/project.bin")!!.use { it.readBytes() }
            val before = bytes.copyOf()
            val legacy = codec.decode(bytes)
            assertNull(legacy.selectedVideos)
            assertTrue(legacy.assets.all { it.videoMetadata == null })
            val error = assertThrows(IllegalArgumentException::class.java) { DraftProject(legacy) }
            assertEquals("Selected source order is unknown", error.message)
            assertArrayEquals(before, bytes)
        }
    }

    private fun key(id: String) = FramingKey(SourceId(id), ProjectAspect.PORTRAIT_9_16)

    private fun importedProject(store: HybridProjectStore): HybridProject {
        val base = PreviewTestFixtures.core(sourceCount = 2)
        val assets = ProjectAssetStore(store.directory(base.id))
        // Synthetic bytes test import integrity and persistence, not a decoder or media inspector.
        val video = temporary.newFile("video-${temporary.root.list()!!.size}.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val source = MediaSource("first", video, "First", 2_000_000, video.length(), 90,
            1920, 1080, "video/avc", 3, true, contentHash(video.readBytes()))
        val physical = assets.importVideo(source, 1.2f)
        val second = source.copy(id = "second", displayName = "Second")
        assertEquals(physical, assets.importVideo(second, 1.2f))
        val musicFile = temporary.newFile("music-${temporary.root.list()!!.size}.wav").apply { writeText("music fixture") }
        val music = assets.import(musicFile, ProjectAsset.Kind.AUDIO, 3_000_000)
        val clips = base.current.clips.map { it.copy(assetId = physical.id) }
        val rich = HybridTextState(layers = listOf(TextLayer("rich", "Rich title", 100_000, 900_000)),
            captions = listOf(CaptionCue("caption", "Caption", 200_000, 800_000)), captionsEdited = true, language = "ru")
        val revision = base.current.copy(clips = clips, music = base.current.music.copy(assetId = music.id), textState = rich)
        return HybridProject(base.id, 1, base.fps, 1, listOf(music, physical), revision, revision,
            emptyList(), emptyList(), emptyList(), listOf(
                SelectedVideo.fromInspected(source, physical, SourceOwnership.IMPORTED),
                SelectedVideo.fromInspected(second, physical, SourceOwnership.CAPTURE, CaptureOrigin("capture", 2, true)),
                SelectedVideo.fromInspected(source.copy(id = "unused", displayName = "Unused"), physical, SourceOwnership.IMPORTED)))
    }
}
