package com.veycad.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HybridProjectBackupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun portableBackupRestoresFreshProjectWithExactSourcesSidecarsAndExportOnlyRevisions() {
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        val original = populate(files)
        val before = inventory(files)
        val backup = archive(files)
        assertEquals(before, inventory(files))
        val target = temporary.newFolder()
        // An occupied original ID is preserved; restore must choose a genuinely fresh ID.
        populate(target)
        val existing = inventory(target)
        val result = HybridProjectBackup(target).restore(backup.inputStream())
        assertNotEquals(original.id, result.projectId)
        assertFalse(result.cleanupIncomplete)
        assertEquals(0, result.missingOutputs)
        val restoredStore = HybridProjectStore(target)
        val restored = restoredStore.load(result.projectId)
        assertEquals(original.copy(id = result.projectId, exports = restored.exports), restored)
        assertEquals(original.current.audioMix, restored.current.audioMix)
        assertEquals(AudioMixMode.SPEECH_AND_MUSIC, restored.current.audioMix.mode)
        assertEquals(1_234_567L, restored.current.graph.audioTrack!!.sourceStartUs)
        assertTrue(original.undo.none { it.id == 1L })
        assertEquals(store.loadRevision(original.id, 1), restoredStore.loadRevision(restored.id, 1))
        val oldDirectory = store.directory(original.id)
        val newDirectory = restoredStore.directory(restored.id)
        inventory(oldDirectory).filterKeys { it != "CURRENT" && !it.startsWith("manifests/") }.forEach { (name, hash) ->
            assertEquals("Raw source/sidecar/revision bytes: $name", hash, contentHash(File(newDirectory, name).readBytes()))
        }
        existing.forEach { (name, hash) -> assertEquals("Existing data preserved: $name", hash, contentHash(File(target, name).readBytes())) }
        val output = restored.exports.single()
        assertNotEquals(original.exports.single().fileName, output.fileName)
        assertEquals("published movie bytes", File(target, "completed-renders/${output.fileName}").readText())
        assertEquals(restored.id, CompletedRenderStore.list(target).single { it.file.name == output.fileName }.projectId)
        // A recovered project is itself portable; no hidden dependency on the original directory.
        val again = ByteArrayOutputStream().also { HybridProjectBackup(target).export(restored.id, it) }.toByteArray()
        val third = temporary.newFolder()
        val thirdResult = HybridProjectBackup(third).restore(again.inputStream())
        assertEquals(restored.current, HybridProjectStore(third).load(thirdResult.projectId).current)
    }

    @Test fun genuineBothV7LayoutsRestoreWithoutChangingCapturedStoreAndWriteCurrentFormat() {
        for (layout in listOf("music", "rich-text")) {
            val files = temporary.newFolder()
            val capture = requireNotNull(javaClass.getResourceAsStream("/hybrid-v7-$layout/store.zip"))
            ZipInputStream(capture).use { zip -> while (true) {
                val entry = zip.nextEntry ?: break
                val file = File(files, entry.name).canonicalFile
                require(file.toPath().startsWith(files.canonicalFile.toPath()))
                file.parentFile.mkdirs(); file.writeBytes(zip.readBytes())
            } }
            val before = inventory(files)
            val loaded = HybridProjectStore(files).load("project")
            val target = temporary.newFolder()
            val bytes = ByteArrayOutputStream()
            val saved = HybridProjectBackup(files).export("project", bytes)
            val result = HybridProjectBackup(target).restore(bytes.toByteArray().inputStream())
            val restored = HybridProjectStore(target).load(result.projectId)
            assertEquals(loaded.copy(id = result.projectId, exports = restored.exports), restored)
            assertEquals(before, inventory(files))
            assertEquals(loaded.assets.count { !it.contentHash.matches(Regex("[a-fA-F0-9]{64}")) }, result.legacyUnverifiedSources)
            assertTrue(result.legacyUnverifiedSources > 0)
            assertEquals(saved.legacyUnverifiedSources, result.legacyUnverifiedSources)
            assertEquals(loaded.assets, restored.assets)
            inventory(HybridProjectStore(files).directory("project"))
                .filterKeys { it != "CURRENT" && !it.startsWith("manifests/") }.forEach { (name, hash) ->
                    assertEquals("Genuine legacy sources/sidecars/revision bytes remain exact: $name",
                        hash, contentHash(File(HybridProjectStore(target).directory(restored.id), name).readBytes()))
                }
            val dir = HybridProjectStore(target).directory(result.projectId)
            val payload = File(dir, "manifests/${File(dir, "CURRENT").readText()}").readBytes()
            assertEquals(9, ByteBuffer.wrap(payload).getInt(16))
        }
    }

    @Test fun corruptTraversalDuplicateLinkedTruncatedAndUnknownSchemaBackupsPublishNothing() {
        val files = temporary.newFolder(); populate(files)
        val before = inventory(files)
        val valid = archive(files)
        val members = unzip(valid)
        val sourcePath = members.keys.first { it.startsWith("project/sources/") }
        val corrupt = members.mapValues { it.value.copyOf() }.toMutableMap().apply {
            getValue(sourcePath)[0] = (getValue(sourcePath)[0].toInt() xor 1).toByte()
        }
        val changedWithNewHash = corrupt.toMutableMap().apply { updateHash(this, sourcePath) }
        val traversal = members.toMutableMap().apply {
            val data = remove(sourcePath)!!; put("project/../escape", data)
            val manifest = JSONObject(getValue("manifest.json").toString(Charsets.UTF_8))
            val rows = manifest.getJSONArray("files")
            for (index in 0 until rows.length()) if (rows.getJSONObject(index).getString("path") == sourcePath)
                rows.getJSONObject(index).put("path", "project/../escape")
            put("manifest.json", manifest.toString().toByteArray())
        }
        val duplicateInventory = members.toMutableMap().apply {
            val manifest = JSONObject(getValue("manifest.json").toString(Charsets.UTF_8))
            manifest.getJSONArray("files").put(manifest.getJSONArray("files").getJSONObject(0))
            put("manifest.json", manifest.toString().toByteArray())
        }
        val unknownSchema = members.mapValues { it.value.copyOf() }.toMutableMap().apply {
            val pointer = getValue("project/CURRENT").toString(Charsets.US_ASCII)
            val path = "project/manifests/$pointer"
            ByteBuffer.wrap(getValue(path)).putInt(16, 999)
            updateHash(this, path)
        }
        val linked = valid.copyOf().apply {
            val at = signature(this, 0x02014b50)
            ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(at + 38, 0xa1ff0000.toInt())
        }
        val revisionPaths = members.keys.filter { it.startsWith("project/revisions/") }
        val duplicateZip = replaceAscii(valid, revisionPaths[0], revisionPaths[1])
        val inflatedInventory = members.toMutableMap().apply {
            val manifest = JSONObject(getValue("manifest.json").toString(Charsets.UTF_8))
            manifest.getJSONArray("files").getJSONObject(0).put("bytes", Long.MAX_VALUE)
            put("manifest.json", manifest.toString().toByteArray())
        }
        for (bad in listOf(zip(corrupt), zip(changedWithNewHash), zip(traversal), zip(duplicateInventory),
            zip(unknownSchema), linked, duplicateZip, zip(inflatedInventory), valid.copyOf(valid.size - 20))) {
            assertThrows(Exception::class.java) { HybridProjectBackup(files).restore(bad.inputStream()) }
            assertEquals("Rejected archive must not change current/source/result bytes", before, inventory(files))
            assertTrue(files.listFiles().orEmpty().none { it.name.startsWith(".project-restore-") })
        }
    }

    @Test fun latePublishFailureRollsBackOnlyFreshOutputsAndKeepsAllExistingBytes() {
        val files = temporary.newFolder(); populate(files)
        val backup = archive(files); val before = inventory(files)
        val failure = assertThrows(IllegalStateException::class.java) {
            HybridProjectBackup(files) { error("Injected before atomic project install") }.restore(backup.inputStream())
        }
        assertEquals("Injected before atomic project install", failure.message)
        assertEquals(before, inventory(files))
    }

    @Test fun backupRejectsChangedSourceBeforeWritingAnArchive() {
        val files = temporary.newFolder(); val project = populate(files)
        val source = ProjectAssetStore(HybridProjectStore(files).directory(project.id)).resolve(project.assets.first())
        source.writeText("changed source")
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { HybridProjectBackup(files).export(project.id, output) }
        assertEquals(0, output.size())
    }

    @Test fun removedCompletedMovieIsReportedWithoutInventingAnOutputOrLosingTheProject() {
        val files = temporary.newFolder(); val project = populate(files)
        assertTrue(File(files, "completed-renders/${project.exports.single().fileName}").delete())
        val bytes = ByteArrayOutputStream()
        val saved = HybridProjectBackup(files).export(project.id, bytes)
        assertEquals(1, saved.missingOutputs)
        val target = temporary.newFolder()
        val result = HybridProjectBackup(target).restore(bytes.toByteArray().inputStream())
        assertEquals(1, result.missingOutputs)
        val restored = HybridProjectStore(target).load(result.projectId)
        assertEquals(project.current, restored.current)
        restored.assets.forEach {
            assertEquals(it.contentHash, contentHash(ProjectAssetStore(HybridProjectStore(target).directory(restored.id)).resolve(it).readBytes()))
        }
        assertTrue(CompletedRenderStore.list(target).isEmpty())
        assertEquals(1, restored.exports.size) // Revision reference is historical evidence, not an available MP4.
    }

    private fun populate(files: File): HybridProject {
        val store = HybridProjectStore(files)
        val assets = ProjectAssetStore(store.directory("project"))
        val video = assets.import(temporary.newFile().apply { writeText("exact original video") }, ProjectAsset.Kind.VIDEO, 2_000_000)
        val music = assets.import(temporary.newFile().apply { writeText("exact original music") }, ProjectAsset.Kind.AUDIO, 2_000_000)
        val initial = storageProject(listOf(video, music))
        store.create(initial)
        val text = HybridTextState(layers = listOf(TextLayer("title", "Копия", 0, 900_000,
            TextStyle(font = TextFont.MONO, animation = TextAnimation.SCALE, plate = TextPlate.ACCENT), TextLayerKind.TITLE)))
        val historical = initial.current.copy(id = 1, parentId = 0,
            graph = initial.current.graph.copy(audioTrack = MontageGraph.AudioTrack("music", .7f, 1_234_567L)),
            textState = text, audioMix = AudioMixSettings(AudioMixMode.SPEECH_AND_MUSIC, .4f, .6f))
        var project = initial.copy(current = historical, nextRevisionId = 2, undo = listOf(initial.original),
            exports = listOf(ProjectExportRef(1, "history.mp4", ProjectExportSettings(720, 1280, 30, 1000, 100))))
        store.save(project, 0)
        for (id in 2L..55L) project = project.copy(current = historical.copy(id = id, parentId = id - 1),
            nextRevisionId = id + 1, undo = (project.undo + project.current).takeLast(50))
        store.save(project, 1)
        val output = File(temporary.newFolder(), "history.mp4").apply { writeText("published movie bytes") }
        CompletedRenderStore.publish(files, output, "Saved result", projectId = project.id)
        return store.load(project.id)
    }

    private fun archive(files: File) = ByteArrayOutputStream().also { HybridProjectBackup(files).export("project", it) }.toByteArray()
    private fun inventory(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to contentHash(it.readBytes()) }
    private fun unzip(bytes: ByteArray): MutableMap<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while (true) {
            val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes())
        } }
    }
    private fun zip(members: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> members.forEach { (name, data) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
        } }
    }.toByteArray()
    private fun updateHash(members: MutableMap<String, ByteArray>, path: String) {
        val manifest = JSONObject(members.getValue("manifest.json").toString(Charsets.UTF_8))
        val rows = manifest.getJSONArray("files")
        for (index in 0 until rows.length()) if (rows.getJSONObject(index).getString("path") == path)
            rows.getJSONObject(index).put("bytes", members.getValue(path).size).put("sha256", contentHash(members.getValue(path)))
        members["manifest.json"] = manifest.toString().toByteArray()
    }
    private fun signature(bytes: ByteArray, value: Int): Int = (0..bytes.size - 4).first {
        ByteBuffer.wrap(bytes, it, 4).order(ByteOrder.LITTLE_ENDIAN).int == value
    }
    private fun replaceAscii(bytes: ByteArray, old: String, replacement: String): ByteArray {
        val needle = old.toByteArray(); val fresh = replacement.toByteArray(); require(needle.size == fresh.size)
        return bytes.copyOf().apply {
            for (at in 0..size - needle.size) if (needle.indices.all { this[at + it] == needle[it] }) fresh.copyInto(this, at)
        }
    }
}
