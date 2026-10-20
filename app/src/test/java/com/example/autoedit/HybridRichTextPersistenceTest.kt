package com.veycad.app

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Permanent A2x regressions: real store bytes, independent of temporary capture producers. */
class HybridRichTextPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun richStylesKindsAndConfidenceSurviveStoreHistoryExportAndReopen() {
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        val base = project(store, richText(0))
        store.create(base)
        val one = HybridEditCommands.commitRevision(base, base.current.copy(textState = richText(1)))
            .let { it.copy(exports = listOf(ProjectExportRef(it.current.id, "retained-text.mp4",
                ProjectExportSettings(1080, 1920, 30, 8_000_000, 192_000)))) }
        store.save(one, 0)
        val two = HybridEditCommands.commitRevision(one, one.current.copy(textState = richText(2)))
        store.save(two, one.current.id)
        val history = HybridEditCommands.undo(two)
        store.save(history, two.current.id)

        val dir = store.directory(base.id)
        val before = bytes(dir)
        val reopened = HybridProjectStore(files).load(base.id)
        assertEquals("Original rich fields must survive the production storage boundary",
            base.original.textState, reopened.original.textState)
        assertEquals(history, reopened)
        (listOf(reopened.original, reopened.current) + reopened.undo + reopened.redo).forEach { revision ->
            assertEquals(richText(revision.id.toInt()), revision.textState)
            assertEquals(revision, HybridProjectStore(files).loadRevision(base.id, revision.id))
            revision.textState.layers.forEach { layer ->
                val style = layer.style
                listOf(false, true).forEach { dark ->
                    assertEquals(style.plate ?: if (dark) TextPlate.DARK else TextPlate.NONE,
                        style.copy(darkPlate = dark).resolvedPlate)
                }
            }
            revision.textState.captions.forEach { cue ->
                val confidence = cue.confidence
                assertEquals(!cue.manuallyReviewed && (!cue.confidenceCalibrated ||
                    confidence == null || confidence < .75), cue.needsReview)
            }
        }
        assertEquals(one.current, HybridProjectStore(files).loadRevision(base.id, one.current.id))
        assertBytes(before, dir)
        assertThrows(UnsupportedOperationException::class.java) {
            (reopened.current.textState.layers as MutableList<TextLayer>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (reopened.current.textState.captions as MutableList<CaptionCue>).clear()
        }

        val three = HybridEditCommands.commitRevision(reopened, reopened.current.copy(textState = richText(3)))
            .let { it.copy(undo = listOf(it.original), redo = emptyList()) }
        store.save(three, reopened.current.id)
        val pruned = HybridProjectStore(files).load(base.id)
        assertEquals(three, pruned)
        assertEquals(listOf(0L), pruned.undo.map { it.id })
        assertTrue(pruned.redo.isEmpty())
        assertEquals(4L, pruned.nextRevisionId)
        assertEquals(one.current, HybridProjectStore(files).loadRevision(base.id, pruned.exports.single().revisionId))
        assertBytes(before.filterKeys { it.startsWith("revisions/") || it.startsWith("authored-maps/") }, dir)
    }

    @Test fun captionProjectionUsesSubtitleAndEndExclusiveBeforeAndAfterReopen() {
        val state = HybridTextState(
            layers = listOf(TextLayer("layer", "Привет", 0, 900_000)),
            captions = listOf(CaptionCue("first", "Первый", 0, 500_000),
                CaptionCue("second", "Second", 500_000, 1_000_000)),
            captionStyle = TextStyle(TextPosition.BOTTOM, TextFont.MONO, .12f,
                0xffeab321.toInt(), false, TextAnimation.SCALE), captionsEdited = true, language = "ru")
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        val base = project(store, state)
        store.create(base)
        val reopened = HybridProjectStore(files).load(base.id).current.textState
        listOf(state, reopened).forEach { text ->
            val owner = TextEditProject("synthetic-video", 1_000_000, 1080, 1920,
                text.layers, text.captions, text.captionStyle, text.captionsEdited, text.language)
            listOf(0L, 499_999L, 500_000L, 899_999L, 900_000L, 999_999L, 1_000_000L).forEach { time ->
                assertEquals("Shared projection must match the sole text owner at $time",
                    owner.activeLayers(time), text.activeLayers(time))
            }
            assertEquals(listOf("second"), text.activeLayers(900_000).map { it.id })
            assertEquals(TextLayerKind.SUBTITLE, text.activeLayers(900_000).single().kind)
            assertTrue(text.activeLayers(1_000_000).isEmpty())
        }
    }

    @Test fun richAuthorEditsCasUndoRedoAndBothResetsRetainTheCorrectText() {
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        val base = project(store, richText(0))
        store.create(base)
        val slipped = HybridEditCommands.slipClip(base, "c", 12_345)
        val authored = HybridEditCommands.commitRevision(slipped, slipped.current.copy(textState = richText(1)))
            .let { it.copy(exports = listOf(ProjectExportRef(it.current.id, "author-export.mp4",
                ProjectExportSettings(1080, 1920, 30, 8_000_000, 192_000)))) }
        store.save(authored, 0)
        val reopened = HybridProjectStore(files).load(base.id)
        assertEquals(authored, reopened)
        assertEquals(richText(1), reopened.current.textState)
        val beforeStale = bytes(store.directory(base.id))
        val stale = HybridEditCommands.commitRevision(base, base.current.copy(textState = richText(2)))
        assertThrows(IllegalStateException::class.java) { store.save(stale, 0) }
        assertBytes(beforeStale, store.directory(base.id))

        val undone = HybridEditCommands.undo(reopened)
        store.save(undone, reopened.current.id)
        val undoLoad = HybridProjectStore(files).load(base.id)
        assertEquals(undone, undoLoad)
        assertEquals(richText(0), undoLoad.current.textState)
        assertEquals(richText(1), undoLoad.redo.single().textState)
        val redone = HybridEditCommands.redo(undoLoad)
        store.save(redone, undoLoad.current.id)
        assertEquals(authored.current, HybridProjectStore(files).load(base.id).current)

        val montage = HybridEditCommands.restoreMontage(HybridProjectStore(files).load(base.id))
        store.save(montage, redone.current.id)
        val montageLoad = HybridProjectStore(files).load(base.id)
        assertEquals(montage, montageLoad)
        assertEquals(richText(1), montageLoad.current.textState)
        assertEquals(base.original.clips, montageLoad.current.clips)
        assertTrue(montageLoad.current.restoresAutomaticSources)
        val automatic = HybridEditCommands.restoreAutomatic(montageLoad)
        store.save(automatic, montageLoad.current.id)
        val resetLoad = HybridProjectStore(files).load(base.id)
        assertEquals(automatic, resetLoad)
        assertEquals(richText(0), resetLoad.current.textState)
        assertEquals(richText(1), HybridProjectStore(files).loadRevision(base.id, authored.current.id).textState)
        assertEquals(5L, resetLoad.nextRevisionId)
    }

    private fun project(store: HybridProjectStore, text: HybridTextState): HybridProject {
        val imports = ProjectAssetStore(store.directory("project"))
        val assets = listOf(ProjectAsset.Kind.VIDEO, ProjectAsset.Kind.AUDIO).map { kind ->
            val file = temporary.newFile().apply { writeText("A2x synthetic $kind") }
            imports.import(file, kind, 2_000_000)
        }
        val helper = storageProject(assets)
        val original = helper.original.copy(
            graph = helper.original.graph.copy(frameAttachments = FrameAttachmentTimeline()), textState = text)
        return helper.copy(original = original, current = original)
    }

    private fun richText(revision: Int): HybridTextState {
        val styles = listOf(
            TextStyle(TextPosition.TOP, TextFont.SANS, .025f, 0xff124578.toInt(), false, TextAnimation.NONE),
            TextStyle(TextPosition.CENTER, TextFont.BOLD, .12f, 0xff987654.toInt(), true, TextAnimation.FADE),
            TextStyle(TextPosition.BOTTOM, TextFont.SERIF, .025f, 0xff246813.toInt(), true, TextAnimation.SLIDE, TextPlate.NONE),
            TextStyle(TextPosition.TOP, TextFont.MONO, .12f, 0xffabcdef.toInt(), false, TextAnimation.SCALE, TextPlate.DARK),
            TextStyle(darkPlate = false, plate = TextPlate.NONE),
            TextStyle(darkPlate = true, plate = TextPlate.DARK),
            TextStyle(darkPlate = false, plate = TextPlate.ACCENT),
            TextStyle(darkPlate = true, plate = TextPlate.ACCENT))
        val kinds = TextLayerKind.entries
        val layers = styles.mapIndexed { index, style ->
            TextLayer("r$revision-style-$index", "R$revision Кириллица 😀 $index", 0, 1_000_000,
                style, kinds[index % kinds.size])
        } + listOf(
            TextLayer("r$revision-title180", "Ж😀".repeat(90), 0, 900_000, styles[3], TextLayerKind.TITLE),
            TextLayer("r$revision-legacy2000", "Ж".repeat(1998) + "😀", 100_000, 1_000_000,
                styles[2], TextLayerKind.LEGACY))
        val cues = mutableListOf<CaptionCue>()
        listOf(false, true).forEach { manual ->
            cues += CaptionCue("r$revision-null-$manual", "R$revision Unknown", 0, 500_000,
                confidence = null, manuallyReviewed = manual, confidenceCalibrated = false)
            listOf(0.0, .74, .75, 1.0).forEachIndexed { index, confidence ->
                listOf(false, true).forEach { calibrated ->
                    cues += CaptionCue("r$revision-$manual-$index-$calibrated", "R$revision Evidence", 500_000, 1_000_000,
                        confidence, manual, calibrated)
                }
            }
        }
        assertEquals(180, TextLimits.codePoints(layers[layers.lastIndex - 1].text))
        assertEquals(2000, layers.last().text.length)
        assertEquals(TextPlate.DARK, styles[0].copy(darkPlate = true).resolvedPlate)
        assertEquals(TextPlate.NONE, styles[2].copy(darkPlate = false).resolvedPlate)
        return HybridTextState(layers, cues, styles[revision % 4], captionsEdited = revision % 2 != 0,
            language = listOf("auto", "ru", "en")[revision % 3])
    }

    private fun bytes(root: File): Map<String, ByteArray> = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).invariantSeparatorsPath to it.readBytes()
    }

    private fun assertBytes(expected: Map<String, ByteArray>, root: File) {
        expected.forEach { (path, value) -> assertArrayEquals(path, value, File(root, path).readBytes()) }
    }
}
