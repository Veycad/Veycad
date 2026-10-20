package com.veycad.app

import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Mutated current-writer negatives only: none of these bytes are genuine legacy fixtures. */
class HybridRichTextRejectionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun malformedTextInProjectAndRevisionCannotDamagePublishedStore() {
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        val base = project(store)
        store.create(base)
        val directory = store.directory(base.id)
        val codec = HybridProjectCodec(AnalysisSidecarStore(File(directory, "analysis")),
            AuthoredSourceMapStore(File(directory, "authored-maps")))
        val before = inventory(files)
        val projectBytes = codec.encode(base)
        val revisionBytes = codec.encodeRevisions(listOf(base.original)).getValue(0)
        listOf(projectBytes to false, revisionBytes to true).forEach { (valid, revision) ->
            fun reject(label: String, bad: ByteArray) {
                val failure = assertThrows("Synthetic $label, revision=$revision", Exception::class.java) {
                    if (revision) codec.decodeRevision(bad) else codec.decode(bad)
                }
                assertTrue("Bounded rejection for $label: $failure", failure is IllegalArgumentException || failure is EOFException)
                assertInventory(before, files)
                assertEquals(base, HybridProjectStore(files).load(base.id))
                assertEquals(base.original, HybridProjectStore(files).loadRevision(base.id, 0))
            }
            listOf("MONO" to "LOST", "SCALE" to "WRONG", "ACCENT" to "BADTAG", "TITLE" to "OTHER").forEach { (tag, bad) ->
                reject("unknown enum $tag", replaceString(valid, tag, bad))
            }
            reject("blank layer ID", replaceString(valid, "negative-layer-a", " ".repeat(16)))
            reject("duplicate layer ID", replaceString(valid, "negative-layer-b", "negative-layer-a"))
            reject("blank cue ID", replaceString(valid, "negative-cue", " ".repeat(12)))
            reject("TITLE 181 codepoints", replaceString(valid, "Negative title", "Ж😀".repeat(90) + "Ж"))
            val layerTime = unique(valid, wireString("negative-layer-a")) + wireString("negative-layer-a").size + wireString("Negative title").size
            reject("negative layer start", valid.copyOf().also { ByteBuffer.wrap(it).putLong(layerTime, -1) })
            reject("empty layer interval", valid.copyOf().also { ByteBuffer.wrap(it).putLong(layerTime + 8, 0) })
            val cueTime = unique(valid, wireString("negative-cue")) + wireString("negative-cue").size + wireString("Negative evidence").size
            reject("empty cue interval", valid.copyOf().also { ByteBuffer.wrap(it).putLong(cueTime + 8, 0) })
            val confidenceOffset = cueTime + 16 + 1 // start/end, then nullable presence flag.
            listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -.01, 1.01).forEach { confidence ->
                reject("invalid confidence $confidence", valid.copyOf().also { ByteBuffer.wrap(it).putDouble(confidenceOffset, confidence) })
            }
            val calibratedNull = valid.copyOfRange(0, confidenceOffset) + valid.copyOfRange(confidenceOffset + 8, valid.size)
            calibratedNull[confidenceOffset - 1] = 0
            reject("calibrated null confidence", calibratedNull)
            val layerCount = unique(valid, wireString("negative-layer-a")) - 4
            reject("overbudget layer count", valid.copyOf().also { ByteBuffer.wrap(it).putInt(layerCount, 10_001) })
            reject("truncated confidence", valid.copyOf(confidenceOffset + 3))
            reject("trailing bytes", valid + byteArrayOf(0))
        }
        assertInventory(before, files)
    }

    @Test fun overbudgetTextSaveCannotMoveCurrentOrAlterRetainedFiles() {
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        val base = project(store)
        store.create(base)
        val before = inventory(files)
        val tooMany = List(10_001) { TextLayer("over-$it", "x", 0, 1_000_000) }
        val candidate = base.copy(current = base.current.copy(id = 1, parentId = 0,
            textState = base.current.textState.copy(layers = tooMany)), nextRevisionId = 2)
        assertThrows(IllegalArgumentException::class.java) { store.save(candidate, 0) }
        assertInventory(before, files)
        assertEquals(base, HybridProjectStore(files).load(base.id))
    }

    private fun project(store: HybridProjectStore): HybridProject {
        val assets = ProjectAssetStore(store.directory("project"))
        val imported = listOf(ProjectAsset.Kind.VIDEO, ProjectAsset.Kind.AUDIO).map { kind ->
            assets.import(temporary.newFile().apply { writeText("A2x negative synthetic $kind") }, kind, 2_000_000)
        }
        val helper = storageProject(imported)
        val text = HybridTextState(layers = listOf(
            TextLayer("negative-layer-a", "Negative title", 0, 1_000_000,
                TextStyle(font = TextFont.MONO, animation = TextAnimation.SCALE, plate = TextPlate.ACCENT), TextLayerKind.TITLE),
            TextLayer("negative-layer-b", "Control", 0, 900_000)),
            captions = listOf(CaptionCue("negative-cue", "Negative evidence", 0, 1_000_000, .74, false, true)))
        val original = helper.original.copy(graph = helper.original.graph.copy(frameAttachments = FrameAttachmentTimeline()), textState = text)
        return helper.copy(original = original, current = original)
    }

    private fun wireString(text: String): ByteArray {
        val encoded = text.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(4 + encoded.size).putInt(encoded.size).put(encoded).array()
    }

    private fun unique(bytes: ByteArray, needle: ByteArray): Int = (0..bytes.size - needle.size).single { start ->
        needle.indices.all { bytes[start + it] == needle[it] }
    }

    private fun replaceString(bytes: ByteArray, old: String, replacement: String): ByteArray {
        val needle = wireString(old)
        val index = unique(bytes, needle)
        return bytes.copyOfRange(0, index) + wireString(replacement) + bytes.copyOfRange(index + needle.size, bytes.size)
    }

    private fun inventory(root: File): Map<String, ByteArray> = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).invariantSeparatorsPath to it.readBytes()
    }

    private fun assertInventory(expected: Map<String, ByteArray>, root: File) {
        val actual = inventory(root)
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (path, bytes) -> assertArrayEquals(path, bytes, actual.getValue(path)) }
    }
}
