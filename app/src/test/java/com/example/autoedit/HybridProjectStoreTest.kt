package com.veycad.app

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HybridProjectStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun initial(store: HybridProjectStore): HybridProject {
        val assets = ProjectAssetStore(store.directory("project"))
        val video = temporary.newFile().apply { writeText("video") }
        val music = temporary.newFile().apply { writeText("music") }
        return storageProject(listOf(assets.import(video, ProjectAsset.Kind.VIDEO, 2_000_000),
            assets.import(music, ProjectAsset.Kind.AUDIO, 2_000_000)))
    }
    @Test fun richTextSurvivesReopenHistoryResetsAndPrunedExportWithoutRewritingBlobs() {
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        val base = initial(store)
        val originalText = persistenceText()
        val original = base.original.copy(textState = originalText)
        val project = base.copy(original = original, current = original)
        store.create(project)
        val authorText = originalText.copy(language = "ru", captionsEdited = true,
            captions = originalText.captions.map { it.copy(manuallyReviewed = true) })
        val edited = HybridEditCommands.commitRevision(project, project.current.copy(textState = authorText,
            graph = project.current.graph.copy(audioTrack = MontageGraph.AudioTrack("music", .7f, 1_234_567L))))
            .copy(exports = listOf(ProjectExportRef(1, "text.mp4", ProjectExportSettings(720, 1280, 30, 1000, 100))))
        store.save(edited, 0)
        val revisionDir = File(store.directory(project.id), "revisions")
        val retainedBytes = revisionDir.listFiles()!!.associate { it.name to it.readBytes() }
        val later = HybridEditCommands.commitRevision(edited, edited.current.copy(
            textState = authorText.copy(language = "en")))
        store.save(later, edited.current.id)
        val undone = HybridEditCommands.undo(later)
        store.save(undone, later.current.id)
        val reopened = HybridProjectStore(files).load(project.id)
        assertEquals(undone, reopened)
        assertEquals(originalText, reopened.original.textState)
        assertEquals(authorText, reopened.current.textState)
        assertEquals(later.current.textState, reopened.redo.single().textState)
        assertEquals(originalText, reopened.undo.single().textState)
        assertEquals(listOf(true, false) + List(2) { listOf(true, false, true, false) }.flatten() +
            List(2) { listOf(true, false, false, false) }.flatten(),
            reopened.original.textState.captions.map { it.needsReview })
        assertTrue(reopened.current.textState.captions.none { it.needsReview })
        assertTrue(reopened.current.textState.captions.any { !it.confidenceCalibrated })
        val nullPlate = reopened.original.textState.layers.first().style
        assertNull(nullPlate.plate)
        assertEquals(TextPlate.NONE, nullPlate.resolvedPlate)
        assertEquals(TextPlate.DARK, nullPlate.copy(darkPlate = true).resolvedPlate)
        val explicitNone = reopened.original.textState.layers.first { it.style.plate == TextPlate.NONE && it.style.darkPlate }.style
        assertEquals(TextPlate.NONE, explicitNone.resolvedPlate)
        assertEquals(TextPlate.NONE, explicitNone.copy(darkPlate = false).resolvedPlate)
        assertEquals(TextLayerKind.LEGACY, reopened.original.textState.layers.first().kind)
        assertEquals(2000, reopened.original.textState.layers.first().text.length)
        assertEquals(1_234_567L, reopened.current.graph.audioTrack!!.sourceStartUs)
        assertEquals(1_234_567L, reopened.redo.single().graph.audioTrack!!.sourceStartUs)
        assertEquals(0L, reopened.original.graph.audioTrack!!.sourceStartUs)
        val redone = HybridEditCommands.redo(reopened)
        store.save(redone, reopened.current.id)
        val pointer = File(store.directory(project.id), "CURRENT")
        val beforeStaleSave = pointer.readBytes()
        assertThrows(IllegalStateException::class.java) { store.save(edited, 0) }
        assertArrayEquals(beforeStaleSave, pointer.readBytes())
        val slipped = HybridEditCommands.slipClip(redone, "c", 10_000)
        store.save(slipped, redone.current.id)
        val montageReset = HybridEditCommands.restoreMontage(slipped)
        store.save(montageReset, slipped.current.id)
        assertEquals(redone.current.textState, HybridProjectStore(files).load(project.id).current.textState)
        val automaticReset = HybridEditCommands.restoreAutomatic(montageReset)
        store.save(automaticReset, montageReset.current.id)
        assertEquals(originalText, HybridProjectStore(files).load(project.id).current.textState)
        var pruned = automaticReset
        repeat(55) { index ->
            pruned = HybridEditCommands.commitRevision(pruned, pruned.current.copy(
                textState = originalText.copy(language = if (index % 2 == 0) "en" else "ru")))
        }
        store.save(pruned, automaticReset.current.id)
        val finalProject = HybridProjectStore(files).load(project.id)
        assertFalse(finalProject.undo.any { it.id == edited.current.id })
        assertEquals(edited.current, HybridProjectStore(files).loadRevision(project.id, finalProject.exports.single().revisionId))
        retainedBytes.forEach { (name, bytes) -> assertArrayEquals(bytes, File(revisionDir, name).readBytes()) }
    }

    private fun persistenceText(): HybridTextState {
        val styles = listOf<TextPlate?>(null, TextPlate.NONE, TextPlate.DARK, TextPlate.ACCENT).flatMap { plate ->
            listOf(false, true).map { dark -> TextStyle(font = TextFont.MONO, animation = TextAnimation.SCALE,
                darkPlate = dark, plate = plate) }
        }
        val layers = styles.mapIndexed { index, style ->
            val kind = TextLayerKind.entries[index % TextLayerKind.entries.size]
            TextLayer("layer-$index", if (index == 0) "Я".repeat(2000) else "Текст 😀", 100, 900_000, style, kind)
        }
        val cues = listOf(false, true).map { manual -> CaptionCue("unknown-$manual", "Речь", 100, 900_000,
            manuallyReviewed = manual) } + listOf(0.0, .74, .75, 1.0).flatMap { confidence ->
            listOf(false, true).flatMap { calibrated -> listOf(false, true).map { manual ->
                CaptionCue("cue-$confidence-$calibrated-$manual", "Речь", 100, 900_000,
                    confidence, manual, calibrated)
            } }
        }
        return HybridTextState(layers, cues, styles.last().copy(darkPlate = false))
    }

    @Test fun interruptedSaveKeepsPreviousRevision() {
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        val project = initial(store)
        store.create(project)
        val pointer = File(store.directory("project"), "CURRENT")
        val before = pointer.readBytes()
        val changed = project.copy(current = project.current.copy(id = 1, parentId = 0), nextRevisionId = 2)
        val interrupted = HybridProjectStore(files, beforePointerReplace = { throw IOException("power loss") })
        assertThrows(IOException::class.java) { interrupted.save(changed, 0) }
        assertArrayEquals(before, pointer.readBytes())
        assertEquals(project, HybridProjectStore(files).load(project.id))
        store.save(changed, 0)
        assertFalse(before.contentEquals(pointer.readBytes()))
        println("Atomic pointer evidence: injected before replacement; previous pointer bytes unchanged; reopened revision=0; retry reopened revision=1")
        assertEquals(changed, HybridProjectStore(files).load(project.id))
        assertThrows(IllegalStateException::class.java) { store.save(changed, 0) }
    }
    @Test fun interruptedSaveDoesNotReserveAnUnpublishedRevisionId() {
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        val project = initial(store)
        store.create(project)
        val changed = project.copy(current = project.current.copy(id = 1, parentId = 0), nextRevisionId = 2)
        val interrupted = HybridProjectStore(files, beforePointerReplace = { throw IOException("power loss") })
        assertThrows(IOException::class.java) { interrupted.save(changed, 0) }
        val reopened = store.load("project")
        val different = reopened.copy(nextRevisionId = 2, current = reopened.current.copy(id = 1, parentId = 0,
            style = reopened.current.style.copy(showAuthoredText = true)))
        store.save(different, 0)
        assertEquals(different, store.load("project"))
    }
    @Test fun rejectsCounterRollbackAndMissingSources() {
        val store = HybridProjectStore(temporary.newFolder())
        val project = initial(store)
        store.create(project.copy(nextRevisionId = 10))
        assertThrows(IllegalArgumentException::class.java) { store.save(project, 0) }
        assertThrows(IllegalArgumentException::class.java) { store.create(project.copy(id = "other")) }
        assertEquals(10L, store.load("project").nextRevisionId)
        val reuse = project.copy(nextRevisionId = 10, current = project.current.copy(id = 1, parentId = 0))
        assertThrows(IllegalArgumentException::class.java) { store.save(reuse, 0) }
    }
    @Test fun futureSchemaIsRejectedWithoutRewritingAndCorruptCacheKeepsSources() {
        val store = HybridProjectStore(temporary.newFolder())
        val project = initial(store)
        store.create(project)
        val dir = store.directory("project")
        File(dir, "analysis").listFiles()!!.forEach { it.writeBytes(byteArrayOf(0)) }
        val result = store.loadWithAnalysisStatus("project")
        assertTrue(result.analysisRegenerationRequired)
        val sources = ProjectAssetStore(dir)
        assertEquals("video", sources.resolve(result.project.assets.first()).readText())
        assertEquals("music", sources.resolve(result.project.assets.last()).readText())
        val pointer = File(dir, "CURRENT").readText()
        val manifest = File(File(dir, "manifests"), pointer)
        val bytes = manifest.readBytes()
        java.nio.ByteBuffer.wrap(bytes).putInt(4, 99)
        manifest.writeBytes(bytes)
        assertThrows(IllegalArgumentException::class.java) { store.load("project") }
        assertThrows(IllegalArgumentException::class.java) { store.save(project, 0) }
        assertArrayEquals(bytes, manifest.readBytes())
        assertEquals(pointer, File(dir, "CURRENT").readText())
    }
    @Test fun retainedExportsKeepTheirRevisions() {
        val store = HybridProjectStore(temporary.newFolder())
        var project = initial(store)
        store.create(project)
        for (id in 1L..55L) {
            val old = project.current.id
            project = project.copy(current = project.current.copy(id = id, parentId = old), nextRevisionId = id + 1,
                undo = (project.undo + project.current).takeLast(50), exports = listOf(ProjectExportRef(1, "out.mp4",
                    ProjectExportSettings(1080, 1920, 30, 1000, 100))))
            store.save(project, old)
        }
        val reopened = store.load("project")
        assertEquals(50, reopened.undo.size)
        assertEquals(1L, store.loadRevision("project", reopened.exports.single().revisionId).id)
        assertEquals(listOf(reopened), store.list())
    }
    @Test fun missingAnalysisRemainsObservableAfterSavingAnotherEdit() {
        val store = HybridProjectStore(temporary.newFolder())
        val project = initial(store)
        store.create(project)
        File(store.directory("project"), "analysis").listFiles()!!.forEach { it.delete() }
        val opened = store.loadWithAnalysisStatus("project")
        val changed = opened.project.copy(current = opened.project.current.copy(id = 1, parentId = 0), nextRevisionId = 2)
        store.save(changed, 0)
        assertEquals(opened.missingAnalysisHashes, store.loadWithAnalysisStatus("project").missingAnalysisHashes)
    }
    @Test fun publishedSourceBindingCannotBeReplacedWithoutChangingRevision() {
        val store = HybridProjectStore(temporary.newFolder())
        val project = initial(store)
        store.create(project)
        val assets = ProjectAssetStore(store.directory(project.id))
        val replacement = assets.import(temporary.newFile().apply { writeText("different footage") },
            ProjectAsset.Kind.VIDEO, 2_000_000)
        val original = project.assets.first()
        val changes = listOf(
            original.copy(fileName = replacement.fileName, contentHash = replacement.contentHash),
            original.copy(durationUs = 3_000_000),
            original.copy(displayName = "A different source label"))
        val pointer = File(store.directory(project.id), "CURRENT").readBytes()
        for (changed in changes) {
            val rebound = project.copy(assets = project.assets.map { if (it.id == original.id) changed else it })
            assertThrows(IllegalArgumentException::class.java) { store.save(rebound, project.current.id) }
            assertArrayEquals(pointer, File(store.directory(project.id), "CURRENT").readBytes())
        }
        val reopened = store.load(project.id)
        assertEquals(project, reopened)
        assertEquals("video", assets.resolve(reopened.assets.first()).readText())
    }
    @Test fun prunedExportKeepsItsSourceRecordsAndCommittedAssetOrder() {
        val store = HybridProjectStore(temporary.newFolder())
        val initial = initial(store)
        store.create(initial)
        val sources = ProjectAssetStore(store.directory(initial.id))
        val historicalVideo = sources.import(temporary.newFile().apply { writeText("historical video") },
            ProjectAsset.Kind.VIDEO, 2_000_000).copy(displayName = "Historical footage")
        val historicalMusic = sources.import(temporary.newFile().apply { writeText("historical music") },
            ProjectAsset.Kind.AUDIO, 2_000_000).copy(displayName = "Historical soundtrack")
        val historical = initial.current.copy(id = 1, parentId = 0,
            clips = initial.current.clips.map { it.copy(assetId = historicalVideo.id) },
            music = initial.current.music.copy(assetId = historicalMusic.id))
        var project = initial.copy(current = historical, nextRevisionId = 2,
            assets = initial.assets + listOf(historicalVideo, historicalMusic),
            undo = listOf(initial.current), exports = listOf(ProjectExportRef(1, "historical.mp4",
                ProjectExportSettings(1080, 1920, 30, 1000, 100))))
        store.save(project, 0)
        assertEquals(initial.assets + listOf(historicalVideo, historicalMusic), store.load(project.id).assets)
        // Apply more than 50 commands; revision 1 falls out of the editable history.
        for (id in 2L..55L) {
            project = project.copy(current = initial.current.copy(id = id, parentId = id - 1),
                nextRevisionId = id + 1, undo = (project.undo + project.current).takeLast(50))
        }
        assertFalse(project.undo.any { it.id == historical.id })
        val newest = sources.import(temporary.newFile().apply { writeText("new unused import") },
            ProjectAsset.Kind.VIDEO, 2_000_000)
        // The caller no longer carries historical assets, and supplies existing records out of order.
        store.save(project.copy(assets = listOf(newest) + initial.assets.reversed()), 1)
        val reopened = store.load(project.id)
        val exported = store.loadRevision(project.id, reopened.exports.single().revisionId)
        val byId = reopened.assets.associateBy { it.id }
        assertEquals(historicalVideo, byId[exported.clips.single().assetId])
        assertEquals(historicalMusic, byId[exported.music.assetId])
        assertEquals("historical video", sources.resolve(byId.getValue(exported.clips.single().assetId)).readText())
        assertEquals("historical music", sources.resolve(byId.getValue(exported.music.assetId)).readText())
        assertEquals(initial.assets + listOf(historicalVideo, historicalMusic, newest), reopened.assets)
    }
}
