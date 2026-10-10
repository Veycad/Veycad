package com.veycad.app

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HybridIntegrationA1Test {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun legacyMusicHeadroomSurvivesNoOpAndCodec() {
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        for (gain in listOf(1.5f, 2f)) {
            val base = editProject()
            val revision = base.current.copy(music = base.current.music.copy(gain = gain),
                graph = base.current.graph.copy(audioTrack = MontageGraph.AudioTrack("music", gain)))
            val project = base.copy(original = revision, current = revision)
            assertSame(project, HybridEditCommands.commitRevision(project, project.current))
            val reopened = codec.decode(codec.encode(project))
            assertEquals(gain, reopened.current.music.gain, 0f)
            assertEquals(gain, reopened.current.graph.audioTrack!!.gain, 0f)
        }
    }

    @Test fun genuinePhysicalV3KeepsResetHistoryAndUnknownMetadata() {
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        val moved = HybridEditCommands.moveCut(editProject(), "right", 28)
        val expected = HybridEditCommands.restoreAutomatic(moved)
        val bytes = javaClass.getResourceAsStream("/hybrid-v3/project.bin")!!.use { it.readBytes() }
        val before = bytes.copyOf()
        assertEquals(3, java.nio.ByteBuffer.wrap(bytes).getInt(4))
        val project = codec.decode(bytes)
        assertEquals(expected, project)
        assertNull(project.selectedVideos)
        assertTrue(project.assets.all { it.videoMetadata == null })
        assertSame(project.original, project.undo.first())
        for (revision in project.undo + project.current) {
            val old = javaClass.getResourceAsStream("/hybrid-v3/revision-${revision.id}.bin")!!.use { it.readBytes() }
            assertEquals(revision, codec.decodeRevision(old))
        }
        assertTrue(project.current.restoresAutomaticSources)
        assertFalse(HybridEditCommands.setAuthoredText(project, false).current.restoresAutomaticSources)
        assertArrayEquals(before, bytes)
    }

    @Test fun duplicateSelectionsKeepOrderIndependentFramingAndCaptureMetadata() {
        val original = selectedProject()
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        val clips = original.current.clips.reversed().mapIndexed { index, clip ->
            clip.copy(span = FrameSpan(index * 30, (index + 1) * 30))
        }
        val reordered = HybridEditCommands.commitRevision(original, original.current.copy(clips = clips,
            visualSettings = visuals()))
        val reopened = codec.decode(codec.encode(reordered))
        assertEquals(listOf("first", "second", "unused"), reopened.selectedVideos!!.map { it.id.value })
        assertEquals(listOf(1, 1, 0), reopened.current.clips.map { it.original.sourceIndex })
        assertEquals(listOf("video", "video", "video"), reopened.selectedVideos!!.map { it.assetId })
        assertEquals(listOf("First label", "Second label", "Unused"), reopened.selectedVideos!!.map { it.displayName })
        assertEquals(CaptureOrigin("session", 2, true), reopened.selectedVideos!![1].captureOrigin)
        assertEquals(SourceOwnership.CAPTURE, reopened.selectedVideos!![1].ownership)
        assertEquals(SourceGeometry(1920, 1080, 90, 1.2f), reopened.assets.first().videoMetadata!!.geometry)
        assertEquals(54321L, reopened.assets.first().videoMetadata!!.sizeBytes)
        assertEquals(6, reopened.assets.first().videoMetadata!!.colorTransfer)
        assertTrue(reopened.assets.first().videoMetadata!!.hasAudio)
        assertEquals("video/avc", reopened.assets.first().videoMetadata!!.mime)
        assertEquals(original.original, reopened.original)
        assertEquals(visuals(), reopened.current.visualSettings)
        assertEquals(.2f, reopened.current.visualSettings.framings.getValue(FramingKey(SourceId("first"), ProjectAspect.SQUARE_1_1)).manual.centerX, 0f)
        assertEquals(.8f, reopened.current.visualSettings.framings.getValue(FramingKey(SourceId("second"), ProjectAspect.SQUARE_1_1)).manual.centerX, 0f)
        val draft = DraftProject(reopened)
        assertEquals(listOf("first", "second", "unused"), draft.sourceOrder.map { it.value })
        assertSame(reopened.current.visualSettings, draft.visualSettings)
        assertEquals(3, draft.sources.size)
    }

    @Test fun fullTextAndVisualsSurviveCoreCommitStoreHistoryAndExportSnapshot() {
        val store = HybridProjectStore(temporary.newFolder())
        val project = selectedProject()
        materialize(store, project)
        store.create(project)
        val text = richText()
        val edited = HybridEditCommands.commitRevision(project, project.current.copy(textState = text, visualSettings = visuals()))
        assertEquals(1L, edited.current.id)
        assertEquals(0L, edited.current.parentId)
        assertEquals(2L, edited.nextRevisionId)
        assertSame(edited, HybridEditCommands.commitRevision(edited, edited.current.copy()))
        assertThrows(HybridEditRejected::class.java) {
            HybridEditCommands.commitRevision(edited, edited.current.copy(id = 20))
        }
        val exported = edited.copy(exports = listOf(ProjectExportRef(1, "out.mp4", ProjectExportSettings(1080, 1920, 30, 1, 1))))
        store.save(exported, 0)
        val reopened = HybridProjectStore(store.directory("project").parentFile!!.parentFile!!).load("project")
        assertEquals(exported, reopened)
        val undone = HybridEditCommands.undo(reopened)
        store.save(undone, 1)
        assertEquals(HybridTextState(), store.load("project").current.textState)
        assertEquals(text, store.load("project").redo.single().textState)
        val redone = HybridEditCommands.redo(store.load("project"))
        store.save(redone, 0)
        val snapshot = store.loadRevision("project", store.load("project").exports.single().revisionId)
        assertEquals(text, snapshot.textState)
        assertEquals(visuals(), snapshot.visualSettings)
        assertEquals(project.current.texts, snapshot.texts)
        assertEquals("Надпись", snapshot.textState.activeLayers(2_100_000).first().text)
        assertEquals(2, snapshot.textState.activeLayers(2_100_000).size)
        assertTrue(snapshot.textState.activeLayers(2_800_000).isEmpty())
    }

    @Test fun allAspectsAndLanguagesRoundTripWithoutChangingSavedModeParameters() {
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        for (aspect in ProjectAspect.entries) for (language in listOf("auto", "ru", "en")) {
            val base = selectedProject()
            val updated = HybridEditCommands.commitRevision(base, base.current.copy(
                visualSettings = visuals().withAspect(aspect), textState = richText().copy(language = language)))
            val loaded = codec.decode(codec.encode(updated))
            assertEquals(aspect, loaded.current.visualSettings.aspect)
            assertTrue(loaded.current.visualSettings.explicitlySelected)
            assertEquals(language, loaded.current.textState.language)
            assertEquals(visuals().framings, loaded.current.visualSettings.framings)
            assertEquals(ProjectAspect.PORTRAIT_9_16, loaded.original.visualSettings.aspect)
        }
    }

    @Test fun selectedBindingsMetadataAndPublishedTableCannotBeRebound() {
        val project = selectedProject()
        val store = HybridProjectStore(temporary.newFolder())
        materialize(store, project); store.create(project)
        val changed = project.selectedVideos!!.toMutableList().also { it[0] = it[0].copy(id = SourceId("changed")) }
        assertThrows(IllegalArgumentException::class.java) { project.copy(selectedVideos = changed) }
        assertThrows(IllegalArgumentException::class.java) { project.copy(selectedVideos = null) }
        val fork = project.copy(id = "fork", selectedVideos = changed)
        val forged = HybridProject(project.id, 1, 30, 1, fork.assets, fork.original, fork.current,
            emptyList(), emptyList(), emptyList(), changed)
        assertThrows(IllegalArgumentException::class.java) { store.save(forged, 0) }
        val changedAssets = project.assets.map { if (it.kind == ProjectAsset.Kind.VIDEO)
            it.copy(videoMetadata = it.videoMetadata!!.copy(geometry = SourceGeometry(720, 1280, 0, 1f))) else it }
        assertThrows(IllegalArgumentException::class.java) { store.save(project.copy(assets = changedAssets), 0) }
        assertEquals(project, store.load("project"))
        assertThrows(IllegalArgumentException::class.java) {
            project.copy(id = "fork", selectedVideos = listOf(SelectedVideo(SourceId("x"), "music", SourceOwnership.IMPORTED, displayName = "Music")))
        }
        val badClip = project.current.clips.first().copy(assetId = "other")
        assertThrows(HybridEditRejected::class.java) {
            HybridEditCommands.commitRevision(project.copy(assets = project.assets + project.assets.first().copy(id = "other")),
                project.current.copy(clips = listOf(badClip) + project.current.clips.drop(1)))
        }
    }

    @Test fun modelCopiesOwnContainersAndIncludeTheirValuesInEquality() {
        val layers = richText().layers.toMutableList()
        val captions = richText().captions.toMutableList()
        val framings = visuals().framings.toMutableMap()
        val selections = selectedProject().selectedVideos!!.toMutableList()
        val state = richText().copy(layers = layers, captions = captions)
        val visual = visuals().copy(framings = framings)
        val project = selectedProject().copy(id = "copy", selectedVideos = selections)
        val revision = project.current.copy(textState = state, visualSettings = visual)
        val hash = revision.hashCode()
        layers.clear(); captions.clear(); framings.clear(); selections.clear()
        assertEquals(1, state.layers.size); assertEquals(1, state.captions.size)
        assertEquals(8, visual.framings.size); assertEquals(3, project.selectedVideos!!.size)
        assertEquals(hash, revision.hashCode()); assertEquals(revision, revision.copy())
        assertNotEquals(revision, revision.copy(textState = state.copy(captionsEdited = false)))
        assertThrows(UnsupportedOperationException::class.java) { (state.layers as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (visual.framings as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (project.selectedVideos as MutableList).clear() }
    }

    @Test fun validationRejectsUnknownBindingsMissingMetadataAndInvalidAbsoluteText() {
        val base = editProject()
        assertThrows(IllegalArgumentException::class.java) { DraftProject(base) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(selectedVideos = listOf(
            SelectedVideo(SourceId("x"), "video", SourceOwnership.IMPORTED, displayName = "Video"))) }
        val project = selectedProject()
        assertThrows(HybridEditRejected::class.java) { HybridEditCommands.commitRevision(project,
            project.current.copy(visualSettings = visuals().withFraming(FramingKey(SourceId("missing"), ProjectAspect.SQUARE_1_1), SourceFramingSettings()))) }
        assertThrows(HybridEditRejected::class.java) { HybridEditCommands.commitRevision(project,
            project.current.copy(textState = HybridTextState(layers = listOf(TextLayer("past", "Text", 0, 3_000_001))))) }
        assertThrows(IllegalArgumentException::class.java) { richText().copy(language = "de") }
        assertThrows(IllegalArgumentException::class.java) { richText().copy(layers = richText().layers + richText().layers) }
        assertThrows(IllegalArgumentException::class.java) { richText().copy(captions = richText().captions + richText().captions) }
        assertThrows(IllegalArgumentException::class.java) { TextStyle(sizeRatio = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { TextLayer("x", "x".repeat(2001), 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { CaptionCue("x", "x", 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { SourceGeometry(1, 2, 45, 1f) }
        assertThrows(IllegalArgumentException::class.java) { SelectedVideo(SourceId("x"), "video", SourceOwnership.CAPTURE, displayName = "Capture") }
        assertThrows(IllegalArgumentException::class.java) { ProjectMusic("music", 0, 2.01f, 0, 0, false) }
    }

    @Test fun unknownVersionStoreAndLegacySourceTableCannotBeRewritten() {
        val store = installVersion3Store()
        val dir = store.directory("project")
        val pointer = File(dir, "CURRENT")
        val manifest = File(File(dir, "manifests"), pointer.readText())
        val before = manifest.readBytes()
        val unknown = before.copyOf()
        // State header + length occupy 12 bytes, then project magic + physical format.
        java.nio.ByteBuffer.wrap(unknown).putInt(16, 99)
        manifest.writeBytes(unknown)
        assertThrows(IllegalArgumentException::class.java) { store.load("project") }
        assertArrayEquals(unknown, manifest.readBytes())
        manifest.writeBytes(before)
        val loaded = store.load("project")
        val known = selectedProject()
        val forged = loaded.copy(assets = known.assets, selectedVideos = known.selectedVideos)
        assertThrows(IllegalArgumentException::class.java) { store.save(forged, loaded.current.id) }
        assertArrayEquals(before, manifest.readBytes())
    }

    @Test fun openingV3StoreDoesNotRewriteAndV4EditRetainsOldResetBlobs() {
        val store = installVersion3Store()
        val dir = store.directory("project")
        val pointer = File(dir, "CURRENT")
        val name = pointer.readText()
        val manifest = File(File(dir, "manifests"), name)
        val before = manifest.readBytes()
        val oldBlobs = File(dir, "revisions").listFiles()!!.associate { it to it.readBytes() }
        val loaded = store.load("project")
        assertEquals(name, pointer.readText()); assertArrayEquals(before, manifest.readBytes())
        assertNull(loaded.selectedVideos)
        assertTrue(loaded.assets.all { it.videoMetadata == null })
        val edited = HybridEditCommands.commitRevision(loaded, loaded.current.copy(textState = richText()))
        assertFalse(edited.current.restoresAutomaticSources)
        store.save(edited, loaded.current.id)
        assertEquals(edited, store.load("project"))
        assertTrue(store.loadRevision("project", 2).restoresAutomaticSources)
        assertEquals(richText(), store.loadRevision("project", 3).textState)
        assertArrayEquals(before, manifest.readBytes())
        oldBlobs.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        assertNull(store.load("project").selectedVideos)
    }

    @Test fun originalRichPayloadSurvivesDistinctCurrentAndAutomaticReset() {
        val base = selectedProject()
        val original = base.original.copy(visualSettings = visuals(), textState = richText())
        val initial = base.copy(original = original, current = original)
        val moved = HybridEditCommands.slipClip(initial, "right", 10_000)
        val edited = HybridEditCommands.commitRevision(moved, moved.current.copy(
            visualSettings = visuals().withAspect(ProjectAspect.LANDSCAPE_16_9),
            textState = richText().copy(captions = emptyList(), language = "en")))
        val reset = HybridEditCommands.restoreAutomatic(edited)
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        val loaded = codec.decode(codec.encode(reset))
        assertEquals(initial.selectedVideos, loaded.selectedVideos)
        assertEquals(original, loaded.original)
        assertEquals(original.textState, loaded.current.textState)
        assertEquals(original.visualSettings, loaded.current.visualSettings)
        assertEquals(original.clips, loaded.current.clips)
        assertTrue(loaded.current.restoresAutomaticSources)
        assertEquals("en", loaded.undo.last().textState.language)
        assertEquals(ProjectAspect.LANDSCAPE_16_9, loaded.undo.last().visualSettings.aspect)
        assertSame(loaded.original, loaded.undo.first())
    }

    private fun installVersion3Store(): HybridProjectStore {
        val store = HybridProjectStore(temporary.newFolder())
        val dir = store.directory("project").apply { mkdirs() }
        val bytes = javaClass.getResourceAsStream("/hybrid-v3/project.bin")!!.use { it.readBytes() }
        val revisions = (0L..2L).associateWith { id ->
            javaClass.getResourceAsStream("/hybrid-v3/revision-$id.bin")!!.use { it.readBytes() }
        }
        val revisionDir = File(dir, "revisions").apply { mkdirs() }
        revisions.values.forEach { File(revisionDir, "${contentHash(it)}.bin").writeBytes(it) }
        val name = "00000000-0000-0000-0000-000000000003.bin"
        val manifest = File(File(dir, "manifests").apply { mkdirs() }, name)
        java.io.DataOutputStream(manifest.outputStream()).use { out ->
            out.writeInt(0x56485354); out.writeInt(1); out.writeInt(bytes.size); out.write(bytes)
            out.writeInt(revisions.size)
            revisions.forEach { (id, data) -> out.writeLong(id); out.write(contentHash(data).toByteArray(Charsets.US_ASCII)) }
            out.writeInt(0)
        }
        File(dir, "CURRENT").writeText(name)
        materialize(store, editProject())
        return store
    }

    private fun materialize(store: HybridProjectStore, project: HybridProject) {
        val sources = File(store.directory(project.id), "sources").apply { mkdirs() }
        project.assets.forEach { File(sources, it.fileName).writeText("synthetic test source") }
    }

    private fun selectedProject(): HybridProject {
        val base = editProject()
        val clips = base.current.clips.mapIndexed { index, clip ->
            clip.copy(original = clip.original.copy(sourceIndex = if (index == 0) 0 else 1))
        }
        val revision = base.current.copy(clips = clips, graph = base.current.graph.copy(clips = clips.map { it.original }),
            texts = listOf(TextItem("old", "Legacy", FrameSpan(1, 29), .5f, .5f, .8f, .1f, -1, TextItem.Appearance.PLAIN, 0)))
        return base.copy(assets = base.assets.map { if (it.kind == ProjectAsset.Kind.VIDEO) it.copy(
            contentHash = "a".repeat(64), videoMetadata = VideoSourceMetadata(SourceGeometry(1920, 1080, 90, 1.2f), 54321, "video/avc", 6, true)) else it },
            original = revision, current = revision, selectedVideos = listOf(
                SelectedVideo(SourceId("first"), "video", SourceOwnership.IMPORTED, displayName = "First label"),
                SelectedVideo(SourceId("second"), "video", SourceOwnership.CAPTURE, CaptureOrigin("session", 2, true), "Second label"),
                SelectedVideo(SourceId("unused"), "video", SourceOwnership.IMPORTED, displayName = "Unused")))
    }

    private fun visuals(): ProjectVisualSettings = ProjectVisualSettings(ProjectAspect.FEED_4_5, true,
        ProjectAspect.entries.flatMap { aspect -> listOf("first", "second").map { id ->
            FramingKey(SourceId(id), aspect) to SourceFramingSettings(FramingMode.SMART_PERSON,
                FramingSettings(FramingMode.MANUAL, if (id == "first") .2f else .8f, .3f, 2f),
                FramingSettings(FramingMode.SMART_PERSON, .4f, .6f, 1.5f),
                FramingSettings(FramingMode.BLURRED_FIT, .1f, .9f, 3f))
        } }.toMap())

    private fun richText() = HybridTextState(
        layers = listOf(TextLayer("layer", "Надпись", 2_000_000, 2_800_000,
            TextStyle(TextPosition.CENTER, TextFont.SERIF, .12f, 0xffaabbcc.toInt(), false, TextAnimation.SLIDE))),
        captions = listOf(CaptionCue("cue", "Hello, мир", 2_100_000, 2_700_000)),
        captionStyle = TextStyle(TextPosition.BOTTOM, TextFont.SANS, .025f, 0xffaaffbb.toInt(), true, TextAnimation.FADE),
        captionsEdited = true, language = "ru")
}
