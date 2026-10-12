package com.veycad.app

import java.io.File
import java.io.IOException
import java.io.DataOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HybridAuthoredMapsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun externalHiddenCurveSurvivesSlipPruningAndRealStoreReopen() {
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        val base = editProject()
        materialize(store, base)
        store.create(base)
        val curve = SourceTimeMap(listOf(SourceTimeMap.Point(0, 300000), SourceTimeMap.Point(10, 600000),
            SourceTimeMap.Point(20, 1200000), SourceTimeMap.Point(30, 1500000)))
        var project = HybridEditCommands.commitRevision(base, base.current.copy(clips = base.current.clips.map {
            if (it.id == "right") it.copy(id = "external", original = it.original.copy(id = "external"), sourceMap = curve) else it
        }))
        store.save(project, 0)
        val exported = project.current
        project = HybridEditCommands.moveCut(project, "external", 42)
        project = HybridEditCommands.slipClip(project, "external", 123456)
        repeat(51) { project = HybridEditCommands.commitRevision(project, project.current.copy(textState =
            project.current.textState.copy(captionsEdited = !project.current.textState.captionsEdited))) }
        project = project.copy(exports = listOf(ProjectExportRef(1, "selected.mp4", ProjectExportSettings(1080, 1920, 30, 1, 1))))
        store.save(project, 1)
        val loaded = HybridProjectStore(files).load(base.id)
        val restored = HybridEditCommands.moveCut(loaded, "external", 30)
        assertEquals(50, loaded.undo.size)
        assertEquals(0, restored.current.clips[1].originalFrameOffset)
        assertEquals(listOf(423456L, 573456L, 723456L, 1323456L, 1623456L),
            listOf(0, 5, 10, 20, 30).map { restored.current.clips[1].sourceMap.sample(it) })
        assertEquals(loaded.current, HybridEditCommands.undo(restored).current)
        assertEquals(restored.current, HybridEditCommands.redo(HybridEditCommands.undo(restored)).current)
        assertEquals(exported, store.loadRevision(base.id, loaded.exports.single().revisionId))
        assertEquals(300000L, store.loadRevision(base.id, 1).clips[1].sourceMap.sample(0))
        assertEquals(base.original, store.loadRevision(base.id, 0))
    }

    @Test fun fiftyLongRepeatedMapsFitSmallManifestAndShareDecodedMaps() {
        val base = editProject(length = 30000)
        val map = SourceTimeMap((0..30000).map { SourceTimeMap.Point(it, 500000L + it * 50L) })
        var project = base.withInitialClips(base.current.clips.map { it.copy(sourceMap = map) })
        repeat(50) { project = HybridEditCommands.commitRevision(project, project.current.copy(textState =
            project.current.textState.copy(captionsEdited = !project.current.textState.captionsEdited))) }
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        val bytes = codec.encode(project)
        assertTrue(bytes.size < 200000)
        val reopened = codec.decode(bytes)
        assertEquals(50, reopened.undo.size)
        assertSame(reopened.original.clips.first().sourceMap, reopened.current.clips.first().sourceMap)
        assertEquals(1999950L, reopened.undo[25].clips[1].sourceMap.sample(29999))
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        materialize(store, project)
        store.create(project)
        val directory = store.directory(project.id)
        assertEquals(1, File(directory, "authored-maps").listFiles()!!.size)
        assertTrue(File(directory, "revisions").listFiles()!!.all { it.length() < 10000 })
        assertTrue(File(directory, "manifests").listFiles()!!.all { it.length() < 200000 })
        val stored = HybridProjectStore(files).load(project.id)
        assertSame(stored.original.clips[0].sourceMap, stored.undo.last().clips[2].sourceMap)
        assertSame(stored.original.clips[0].sourceMap, stored.current.sourceBackings.getValue("right").map)
        assertEquals(50, stored.undo.size)
    }

    @Test fun exactExternalSubsetRetainsSelectedNewerCurveAndGenuineMismatchDropsHiddenCurve() {
        val base = editProject()
        val newer = SourceTimeMap(listOf(SourceTimeMap.Point(0, 300000), SourceTimeMap.Point(10, 600000),
            SourceTimeMap.Point(20, 1200000), SourceTimeMap.Point(30, 1500000)))
        val selected = HybridEditCommands.commitRevision(base, base.current.copy(clips = base.current.clips.map {
            if (it.id == "right") it.copy(sourceMap = newer) else it
        }))
        val subset = selected.current.clips.map { when (it.id) {
            "left" -> it.copy(span = FrameSpan(0, 42), sourceMap = SourceTimeMap(listOf(
                SourceTimeMap.Point(0, 500000), SourceTimeMap.Point(42, 1900000))))
            "right" -> it.copy(span = FrameSpan(42, 60), originalFrameOffset = 12,
                sourceMap = SourceTimeMap((12..30).map { f -> SourceTimeMap.Point(f - 12, newer.sample(f)) }))
            else -> it
        } }
        var trimmed = HybridEditCommands.commitRevision(selected, selected.current.copy(clips = subset))
        assertEquals(selected.current.sourceBackings["right"], trimmed.current.sourceBackings["right"])
        repeat(51) { trimmed = HybridEditCommands.setAuthoredText(trimmed, !trimmed.current.style.showAuthoredText) }
        val restored = HybridEditCommands.moveCut(trimmed, "right", 30)
        assertEquals(300000L, restored.current.clips[1].sourceMap.sample(0))
        assertEquals(450000L, restored.current.clips[1].sourceMap.sample(5))
        // A real replacement agrees at both ends but differs in the middle: inspect ALL samples.
        val right = trimmed.current.clips[1]
        val changed = SourceTimeMap(right.sourceMap.points.map { if (it.localFrame == 5)
            it.copy(sourceTimeUs = it.sourceTimeUs + 1) else it })
        val replacement = HybridEditCommands.commitRevision(trimmed, trimmed.current.copy(clips =
            listOf(trimmed.current.clips[0], right.copy(sourceMap = changed), trimmed.current.clips[2])))
        assertEquals(replacement.current.id, replacement.current.sourceBackings.getValue("right").selectedRevisionId)
        assertThrows(HybridEditRejected::class.java) { HybridEditCommands.moveCut(replacement, "right", 30) }
    }

    @Test fun coincidentExternalSubsetNeverSelectsOldOriginalAfterPruningAndReopen() {
        val base = editProject()
        val right = base.current.clips[1].copy(span = FrameSpan(30, 80), sourceMap = SourceTimeMap(listOf(
            SourceTimeMap.Point(0, 500000), SourceTimeMap.Point(30, 1500000), SourceTimeMap.Point(50, 2600000))))
        val last = base.current.clips[2].copy(span = FrameSpan(80, 90), originalFrameOffset = 20,
            sourceMap = SourceTimeMap((20..30).map { SourceTimeMap.Point(it - 20, base.current.clips[2].sourceMap.sample(it)) }))
        val selected = HybridEditCommands.commitRevision(base, base.current.copy(clips = listOf(base.current.clips[0], right, last)))
        var trimmed = HybridEditCommands.commitRevision(selected, selected.current.copy(clips = base.original.clips))
        assertEquals(base.original.clips, trimmed.current.clips)
        assertEquals(selected.current.sourceBackings["right"], trimmed.current.sourceBackings["right"])
        repeat(51) { trimmed = HybridEditCommands.setAuthoredText(trimmed, !trimmed.current.style.showAuthoredText) }
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        materialize(store, trimmed)
        store.create(trimmed)
        val loaded = HybridProjectStore(files).load(base.id)
        val extended = HybridEditCommands.moveCut(loaded, "last", 80)
        assertEquals(2600000L, extended.current.clips[1].sourceMap.sample(50))
        assertEquals(selected.current.id, extended.current.sourceBackings.getValue("right").selectedRevisionId)
        assertFalse(extended.current.sourceBackings.getValue("right").automatic)
    }

    @Test fun forgedBackingIsRejectedAndCallerContainersCannotMutateSelection() {
        val base = HybridEditCommands.moveCut(editProject(), "right", 42)
        val forged = base.current.sourceBackings.toMutableMap()
        forged["right"] = forged.getValue("right").copy(slipUs = 1)
        val candidate = base.current.copy(sourceBackings = forged)
        forged.clear()
        assertEquals(1L, candidate.sourceBackings.getValue("right").slipUs)
        assertThrows(HybridEditRejected::class.java) { HybridEditCommands.commitRevision(base, candidate) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(current = candidate) }
        assertEquals(0L, base.current.sourceBackings.getValue("right").slipUs)
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (base.current.sourceBackings as MutableMap<String, HybridSourceBacking>).clear()
        }
    }

    @Test fun reorderAndTransitionOnlyChangesPreserveSelectedBackingAndSlipsPreserveHiddenHolds() {
        val base = editProject()
        val curve = SourceTimeMap(listOf(SourceTimeMap.Point(0, 500000), SourceTimeMap.Point(5, 500000),
            SourceTimeMap.Point(30, 1500000)))
        val selected = HybridEditCommands.commitRevision(base, base.current.copy(clips = base.current.clips.map {
            if (it.id == "right") it.copy(sourceMap = curve) else it
        }))
        val reordered = HybridEditCommands.commitRevision(selected, selected.current.copy(clips =
            selected.current.clips.reversed().mapIndexed { i, clip -> clip.copy(span = FrameSpan(i * 30, (i + 1) * 30)) }))
        assertEquals(selected.current.sourceBackings, reordered.current.sourceBackings)
        val transition = HybridEditCommands.commitRevision(reordered, reordered.current.copy(clips =
            reordered.current.clips.map { if (it.id == "right") it.copy(original = it.original.copy(
                transitionIn = MontageGraph.Transition.WHIP, transitionDurationMs = 100)) else it }))
        assertEquals(selected.current.sourceBackings, transition.current.sourceBackings)
        val trimmed = HybridEditCommands.moveCut(selected, "right", 32)
        val slipped = HybridEditCommands.slipClip(trimmed, "right", 123456)
        val restored = HybridEditCommands.moveCut(slipped.copy(undo = emptyList()), "right", 30)
        assertEquals(623456L, restored.current.clips[1].sourceMap.sample(0))
        assertEquals(623456L, restored.current.clips[1].sourceMap.sample(5))
        assertThrows(HybridEditRejected::class.java) { HybridEditCommands.moveCut(restored, "right", 29) }
    }

    @Test fun standaloneRevisionReaderRejectsMismatchedVisibleBacking() {
        val project = HybridEditCommands.moveCut(editProject(), "right", 42)
        val revision = project.current.copy(sourceBackings = project.current.sourceBackings +
            ("right" to project.current.sourceBackings.getValue("right").copy(slipUs = 1)))
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        val bytes = codec.encodeRevisions(listOf(revision)).getValue(revision.id)
        assertThrows(IllegalArgumentException::class.java) { codec.decodeRevision(bytes) }
    }

    @Test fun retainedAuthorCurveCannotHideRequestsPastThePhysicalSourceEnd() {
        val project = HybridEditCommands.setAuthoredText(editProject(), false)
        val hiddenPastEnd = HybridSourceBacking("video", SourceTimeMap(listOf(SourceTimeMap.Point(0, 500000),
            SourceTimeMap.Point(30, 1500000), SourceTimeMap.Point(60, 4000000))), 0, 0, 1, false)
        val invalid = project.current.copy(sourceBackings = project.current.sourceBackings + ("right" to hiddenPastEnd))
        assertThrows(IllegalArgumentException::class.java) { project.copy(current = invalid) }
    }

    @Test fun sharedMapValidationDoesNotLetAnotherAssetBorrowBackingIdentity() {
        val base = editProject()
        val shared = base.current.clips[0].sourceMap
        val backing = HybridSourceBacking("video", shared, 0, 0, 1, false)
        val revision = base.current.copy(id = 1, parentId = 0, clips = base.current.clips.map {
            it.copy(sourceMap = shared, assetId = if (it.id == "right") "other-video" else "video")
        }, sourceBackings = mapOf("left" to backing, "right" to backing))
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(current = revision, nextRevisionId = 2, assets = base.assets + base.assets.first().copy(
                id = "other-video", fileName = "other.mp4"))
        }
    }

    @Test fun resetEventCannotCarryAContradictoryDurableSourceSelection() {
        val reset = HybridEditCommands.restoreAutomatic(HybridEditCommands.slipClip(editProject(), "right", 123456))
        val changed = reset.current.sourceBackings +
            ("right" to reset.current.sourceBackings.getValue("right").copy(automatic = false))
        assertThrows(IllegalArgumentException::class.java) { reset.copy(current = reset.current.copy(sourceBackings = changed)) }
    }

    @Test(timeout = 5000) fun hugeExternalEquivalentWindowRejectsBeforeEnumeration() {
        val length = 715827882
        val base = editProject(length = length)
        val candidate = base.current.copy(clips = base.current.clips.map { if (it.id == "right")
            it.copy(sourceMap = SourceTimeMap(listOf(SourceTimeMap.Point(0, 500000),
                SourceTimeMap.Point(length / 2, 1000000), SourceTimeMap.Point(length, 1500000)))) else it })
        val error = assertThrows(HybridEditRejected::class.java) { HybridEditCommands.commitRevision(base, candidate) }
        assertTrue(error.reason.contains("сравнений"))
    }

    @Test fun resetSelectionSurvivesHistoryPruningReopenAndKeepsAuthoredPayload() {
        for (scoped in listOf(false, true)) {
            val files = temporary.newFolder()
            val store = HybridProjectStore(files)
            val base = editProject()
            materialize(store, base)
            store.create(base)
            var edited = HybridEditCommands.commitRevision(base, base.current.copy(clips = base.current.clips.map {
                if (it.id == "right") it.copy(sourceMap = SourceTimeMap(listOf(SourceTimeMap.Point(0, 300000),
                    SourceTimeMap.Point(30, 1500000)))) else it
            }, textState = HybridTextState(captions = listOf(CaptionCue("cue", "Author", 0, 100000)))))
            edited = if (scoped) HybridEditCommands.restoreMontage(edited) else HybridEditCommands.restoreAutomatic(edited)
            val resetId = edited.current.id
            assertEquals(if (scoped) "Author" else null, edited.current.textState.captions.singleOrNull()?.text)
            repeat(51) { edited = HybridEditCommands.setAuthoredText(edited, !edited.current.style.showAuthoredText) }
            assertFalse(edited.undo.any { it.id == resetId })
            edited = HybridEditCommands.moveCut(edited, "right", 42)
            store.save(edited, 0)
            val loaded = HybridProjectStore(files).load(base.id)
            val extended = HybridEditCommands.moveCut(loaded, "right", 28)
            assertEquals(-2, extended.current.clips[1].originalFrameOffset)
            assertEquals(433333L, extended.current.clips[1].sourceMap.sample(0))
            assertEquals(500000L, extended.current.clips[1].sourceMap.sample(2))
            assertTrue(extended.current.sourceBackings.getValue("right").automatic)
            assertEquals(resetId, extended.current.sourceBackings.getValue("right").selectedRevisionId)
            assertEquals(loaded.current, HybridEditCommands.undo(extended).current)
        }
    }

    @Test fun pointerFailureLeavesPublishedMapsAndRevisionBlobsUntouchedAndRetryResolvesExports() {
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        val base = editProject()
        materialize(store, base)
        store.create(base)
        val directory = store.directory(base.id)
        val before = directory.walkTopDown().filter { it.isFile }.associate { it.relativeTo(directory).path to it.readBytes() }
        val changed = HybridEditCommands.slipClip(base, "right", 123456).let { it.copy(exports = listOf(
            ProjectExportRef(0, "original.mp4", ProjectExportSettings(1080, 1920, 30, 1, 1)))) }
        val failed = HybridProjectStore(files, beforePointerReplace = {
            assertTrue(File(directory, "authored-maps").listFiles()!!.size > 1)
            assertTrue(File(directory, "revisions").listFiles()!!.size > 1)
            throw IOException("injected before CURRENT")
        })
        assertThrows(IOException::class.java) { failed.save(changed, 0) }
        val cancelled = HybridProjectStore(files, beforePointerReplace = { throw java.util.concurrent.CancellationException("cancel") })
        assertThrows(java.util.concurrent.CancellationException::class.java) { cancelled.save(changed, 0) }
        before.forEach { (path, bytes) -> assertArrayEquals(bytes, File(directory, path).readBytes()) }
        assertEquals(base, HybridProjectStore(files).load(base.id))
        store.save(changed, 0)
        assertEquals(changed.current, store.loadRevision(base.id, 1))
        assertEquals(base.original, store.loadRevision(base.id, changed.exports.single().revisionId))
        before.filterKeys { it.startsWith("authored-maps") || it.startsWith("revisions") }
            .forEach { (path, bytes) -> assertArrayEquals(bytes, File(directory, path).readBytes()) }
    }

    @Test fun missingCorruptAndOversizedAuthorMapsFailWithoutRepairOrPointerChange() {
        for (damage in listOf("missing", "hash", "count", "size")) {
            val files = temporary.newFolder()
            val store = HybridProjectStore(files)
            val base = editProject()
            materialize(store, base)
            store.create(base)
            val directory = store.directory(base.id)
            val pointer = File(directory, "CURRENT").readBytes()
            val revisions = File(directory, "revisions").listFiles()!!.associateWith { it.readBytes() }
            val map = File(directory, "authored-maps").listFiles()!!.single()
            when (damage) {
                "missing" -> assertTrue(map.delete())
                "hash" -> map.writeBytes(map.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() })
                "count" -> map.writeBytes(map.readBytes().also { ByteBuffer.wrap(it).putInt(8, Int.MAX_VALUE) })
                "size" -> java.io.RandomAccessFile(map, "rw").use { it.setLength(AuthoredSourceMapStore.MAX_BYTES + 1) }
            }
            val damagedLength = map.length()
            assertThrows(IllegalArgumentException::class.java) { store.loadWithAnalysisStatus(base.id) }
            assertThrows(IllegalArgumentException::class.java) { store.save(base, 0) }
            assertArrayEquals(pointer, File(directory, "CURRENT").readBytes())
            assertEquals(damagedLength, map.length())
            revisions.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        }
    }

    @Test fun missingHiddenBackingFailsEvenWhenEveryVisibleMapStillExists() {
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        val base = editProject()
        materialize(store, base)
        val selected = HybridEditCommands.commitRevision(base, base.current.copy(clips = base.current.clips.map {
            if (it.id == "right") it.copy(sourceMap = SourceTimeMap(listOf(SourceTimeMap.Point(0, 300000),
                SourceTimeMap.Point(10, 600000), SourceTimeMap.Point(30, 1500000)))) else it
        }))
        val trimmed = HybridEditCommands.moveCut(selected, "right", 42).copy(undo = emptyList())
        store.create(trimmed)
        val directory = store.directory(base.id)
        val hidden = File(directory, "authored-maps").listFiles()!!.single { ByteBuffer.wrap(it.readBytes()).getLong(16) == 300000L }
        val pointer = File(directory, "CURRENT").readBytes()
        assertTrue(hidden.delete())
        assertThrows(IllegalArgumentException::class.java) { store.load(base.id) }
        assertThrows(IllegalArgumentException::class.java) { store.save(trimmed, trimmed.current.id) }
        assertArrayEquals(pointer, File(directory, "CURRENT").readBytes())
    }

    @Test fun analysisDeletionAndRepairNeverTouchAuthorMaps() {
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        val base = storageProject()
        materialize(store, base)
        store.create(base)
        val directory = store.directory(base.id)
        val maps = File(directory, "authored-maps").listFiles()!!.associateWith { it.readBytes() }
        val caches = File(directory, "analysis").listFiles()!!.associateWith { it.readBytes() }
        caches.keys.forEach { assertTrue(it.delete()) }
        val degraded = store.loadWithAnalysisStatus(base.id)
        assertTrue(degraded.analysisRegenerationRequired)
        assertEquals(400000L, degraded.project.current.clips[0].sourceMap.sample(15))
        val edited = HybridEditCommands.setAuthoredText(degraded.project, true)
        store.save(edited, 0)
        caches.values.forEach { AnalysisSidecarStore(File(directory, "analysis")).write("plane", it) }
        assertFalse(store.loadWithAnalysisStatus(base.id).analysisRegenerationRequired)
        maps.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        assertEquals(base.original, store.loadRevision(base.id, 0))
    }

    @Test fun genuineV4StoreOpensWithoutRewritingAndKeepsOldExportBlobsOnV5Save() {
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        val directory = store.directory("project").apply { mkdirs() }
        val old = javaClass.getResourceAsStream("/hybrid-v4/project.bin")!!.use { it.readBytes() }
        assertEquals(4, ByteBuffer.wrap(old).getInt(4))
        val blobs = (0L..2L).associateWith { id -> javaClass.getResourceAsStream("/hybrid-v4/revision-$id.bin")!!.use { it.readBytes() } }
        blobs.values.forEach { assertEquals(4, ByteBuffer.wrap(it).getInt(4)) }
        val revisions = File(directory, "revisions").apply { mkdirs() }
        blobs.values.forEach { File(revisions, "${contentHash(it)}.bin").writeBytes(it) }
        val name = "00000000-0000-0000-0000-000000000004.bin"
        val manifest = File(File(directory, "manifests").apply { mkdirs() }, name)
        DataOutputStream(manifest.outputStream()).use { out ->
            out.writeInt(0x56485354); out.writeInt(1); out.writeInt(old.size); out.write(old)
            out.writeInt(blobs.size); blobs.forEach { (id, bytes) -> out.writeLong(id); out.write(contentHash(bytes).toByteArray()) }
            out.writeInt(0)
        }
        File(directory, "CURRENT").writeText(name)
        val before = manifest.readBytes()
        val loaded = store.load("project")
        assertTrue(loaded.current.sourceBackings.isEmpty())
        assertTrue(loaded.current.restoresAutomaticSources)
        assertEquals(1L, loaded.exports.single().revisionId)
        assertEquals(-2, store.loadRevision("project", 1).clips[1].originalFrameOffset)
        assertEquals(name, File(directory, "CURRENT").readText())
        assertArrayEquals(before, manifest.readBytes())
        materialize(store, loaded)
        val edited = HybridEditCommands.slipClip(loaded, "right", 123456)
        store.save(edited, 2)
        assertEquals(edited, HybridProjectStore(files).load("project"))
        assertEquals(556789L, store.loadRevision("project", 1).clips[1].sourceMap.sample(0) + 123456)
        blobs.values.forEach { assertArrayEquals(it, File(revisions, "${contentHash(it)}.bin").readBytes()) }
        assertArrayEquals(before, manifest.readBytes())
    }

    private fun materialize(store: HybridProjectStore, project: HybridProject) {
        val sources = File(store.directory(project.id), "sources").apply { mkdirs() }
        project.assets.forEach { File(sources, it.fileName).writeText("synthetic source") }
    }
}
