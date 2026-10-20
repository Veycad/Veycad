package com.veycad.app

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Temporary evidence producer: genuine unchanged old writer, never a hand-built legacy payload. */
class HybridTextV4CaptureTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun captureUnchangedA531V4TextAndCompleteStore() {
        val repo = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, "gradlew").isFile && File(it, SOURCE + "HybridProjectCodec.kt").isFile }
        val started = Instant.now()
        val executionHead = git(repo, "rev-parse", "HEAD")
        // Existing CI checkout is shallow; exact producer blobs below need no ancestor fetch.
        val guarded = linkedMapOf(
            SOURCE + "HybridProjectCodec.kt" to "2505cd323fc16d7eaffdbe393f3ab9760c7a94fd",
            SOURCE + "TextEditProject.kt" to "c975659e92399709935ec2d4220b9fb7ad82539f",
            SOURCE + "HybridProjectStore.kt" to "7c60ae4869ee587302c16d092e1888e0d994b139",
            SOURCE + "HybridProject.kt" to "cb1923fc9e9d157fe41295cf42d5eb76d978e10e",
            SOURCE + "HybridTextState.kt" to "d15511b52bfdc6990146984fb6373754a0fa3d5e",
            SOURCE + "ProjectAssetStore.kt" to "db36fd91eb9f09d4a05fc2b9f3c2746e285f4ad3",
            SOURCE + "AnalysisSidecarStore.kt" to "7caeca32f8c31056d04fe91055ad0f2f9d5fe5dd",
            SOURCE + "HybridEditCommands.kt" to "0750ef9f7ea9c4a7ca16a90143a3c923871cff5a",
            TEST_SOURCE + "HybridProjectCodecTest.kt" to "7836726f99b0175f369f003d81acd84a4d294b29",
            TEST_SOURCE + "HybridProjectStoreTest.kt" to "6d4407ed5ddc01f6ef014ae8290eb8ece35d1c80",
            "build.gradle.kts" to "f68ab10e9eff53fde211a679808e38500308c662",
            "app/build.gradle.kts" to "696f19073427c076049138e5b4deb52ee7ca62d7",
            ".github/workflows/android-baseline.yml" to "f35bf6385ef50044e326172d9589f3e918132fd9",
            ".github/workflows/android-ui.yml" to "00cb6d4349f888dbdf1beaacc6874b81d01d0160"
        )
        guarded.forEach { (path, blob) ->
            assertEquals("Committed producer: $path", blob, git(repo, "rev-parse", "HEAD:$path"))
            assertEquals("Actual source bytes: $path", blob, git(repo, "hash-object", "--no-filters", path))
        }
        val capturePath = TEST_SOURCE + "HybridTextV4CaptureTest.kt"
        val captureBlob = git(repo, "hash-object", "--no-filters", capturePath)
        assertEquals(captureBlob, git(repo, "rev-parse", "HEAD:$capturePath"))

        val output = File(repo, "app/build/reports/tests/text-v4-a531-capture").apply {
            check(!exists()) { "Capture output already exists; use the baseline clean invocation" }
            check(mkdirs())
        }
        val files = temporary.newFolder("actual-files-dir")
        val store = HybridProjectStore(files)
        val imports = ProjectAssetStore(store.directory("project"))
        val sourceInputs = linkedMapOf(
            "synthetic-video.txt" to "A2x v4 synthetic VIDEO source; no personal media.\n",
            "synthetic-audio.txt" to "A2x v4 synthetic AUDIO source; no personal media.\n"
        )
        val assets = sourceInputs.entries.mapIndexed { index, (name, text) ->
            val source = temporary.newFile(name).apply { writeText(text, Charsets.UTF_8) }
            imports.import(source, if (index == 0) ProjectAsset.Kind.VIDEO else ProjectAsset.Kind.AUDIO, 2_000_000)
        }
        val helper = storageProject(assets)
        val r0 = helper.original.copy(
            graph = helper.original.graph.copy(frameAttachments = FrameAttachmentTimeline()),
            textState = text(0))
        val r1 = r0.copy(id = 1, parentId = 0, textState = text(1),
            clips = r0.clips.map { it.copy(originalFrameOffset = -2) })
        val r2 = r0.copy(id = 2, parentId = 1, textState = text(2),
            clips = r0.clips.map { it.copy(originalFrameOffset = 3) })
        val r3 = r0.copy(id = 3, parentId = 1, textState = text(3),
            clips = r0.clips.map { it.copy(originalFrameOffset = -1) })
        val revisions = listOf(r0, r1, r2, r3)
        revisions.forEach { assertLegacyText(it.textState) }
        val p0 = helper.copy(original = r0, current = r0)
        store.create(p0)
        assertReadOnly(files, p0)
        val export = ProjectExportRef(1, "retained-r1.mp4", ProjectExportSettings(1080, 1920, 30, 8_000_000, 192_000))
        val p1 = p0.copy(current = r1, undo = listOf(r0), nextRevisionId = 2, exports = listOf(export))
        store.save(p1, 0)
        assertReadOnly(files, p1)
        val firstPublished = revisionBytes(store.directory("project"), manifest(store.directory("project")))
        val p2 = p1.copy(current = r2, undo = listOf(r0, r1), nextRevisionId = 3)
        store.save(p2, 1)
        assertReadOnly(files, p2)
        val p3 = HybridEditCommands.undo(HybridProjectStore(files).load("project"))
        assertEquals(1L, p3.current.id)
        assertEquals(listOf(0L), p3.undo.map { it.id })
        assertEquals(listOf(2L), p3.redo.map { it.id })
        assertEquals(3L, p3.nextRevisionId)
        store.save(p3, 2)
        assertReadOnly(files, p3)
        val history = manifest(store.directory("project"))
        File(output, "project-history.bin").writeBytes(history.project)
        snapshotStore(files, File(output, "store-history"), File(output, "store-history.zip"), p3)

        val p4 = p3.copy(current = r3, undo = listOf(r0), redo = emptyList(), nextRevisionId = 4)
        store.save(p4, 1)
        assertReadOnly(files, p4)
        val final = manifest(store.directory("project"))
        assertEquals(setOf(0L, 1L, 2L, 3L), final.revisions.keys)
        assertEquals(history.revisions, final.revisions.filterKeys { it < 3 })
        File(output, "project.bin").writeBytes(final.project)
        val blobs = revisionBytes(store.directory("project"), final)
        firstPublished.forEach { (id, bytes) -> assertArrayEquals(bytes, blobs.getValue(id)) }
        blobs.forEach { (id, bytes) ->
            assertEquals(0x56485942, ByteBuffer.wrap(bytes).int)
            assertEquals(4, ByteBuffer.wrap(bytes).getInt(4))
            File(output, "revision-$id.bin").writeBytes(bytes)
            assertEquals(revisions[id.toInt()], HybridProjectStore(files).loadRevision("project", id))
        }
        assertEquals(r1, HybridProjectStore(files).loadRevision("project", p4.exports.single().revisionId))
        snapshotStore(files, File(output, "store-final"), File(output, "store-final.zip"), p4)
        listOf(history, final).forEach { produced ->
            assertEquals(4, ByteBuffer.wrap(produced.project).getInt(4))
            assertEquals(0x56485942, ByteBuffer.wrap(produced.project).int)
        }

        File(output, "capture-source.kt").writeBytes(File(repo, capturePath).readBytes())
        File(output, "inputs.txt").writeText(buildString {
            appendLine("Synthetic inputs only; source files are text, not playable media. No STT/render/device claim.")
            appendLine("All rich fields use legacy defaults: plate=null, kind=LEGACY, confidence=null, both flags=false.")
            sourceInputs.forEach { (name, contents) -> appendLine("input=$name UTF8=${contents.toByteArray(Charsets.UTF_8).size} contents=${contents.trimEnd()}") }
            assets.forEach { appendLine("asset=$it") }
            revisions.forEach { appendLine("revision=${it.id} parent=${it.parentId} phase=${it.clips.single().originalFrameOffset} text=${it.textState}") }
            listOf(p0, p1, p2, p3, p4).forEachIndexed { stage, project ->
                appendLine("P$stage original=${project.original.id} current=${project.current.id} undo=${project.undo.map { it.id }} redo=${project.redo.map { it.id }} next=${project.nextRevisionId} exports=${project.exports} fps=${project.fps} schema=${project.schemaVersion}")
            }
            appendLine("P3 revisionIndex=${history.revisions}; pointer=${history.pointer}")
            appendLine("P4 revisionIndex=${final.revisions}; pointer=${final.pointer}")
            appendLine("revision-0 produced=P0; revision-1=P1/export; revision-2=P2/redo-at-P3; revision-3=P4")
            appendLine("storageProject graph/source-map/music/text/style fields are unchanged except cleared frameAttachments and stated signed clip phases; guarded helper is the authoritative graph input.")
        }, Charsets.UTF_8)
        File(output, "provenance.txt").writeText(buildString {
            appendLine("New capture of unchanged old encoder; not historic archived bytes.")
            appendLine("semanticAnchor=a53101496244fbb8476dfb314c701380a7eff80a")
            appendLine("executionBase=$BASE")
            appendLine("executionHEAD=$executionHead")
            appendLine("captureTestGitBlob=$captureBlob")
            appendLine("startedUTC=$started completedUTC=${Instant.now()}")
            appendLine("command=./gradlew --no-daemon clean baselineVerify -PpythonExecutable=python")
            appendLine("workflowArtifact=test-reports; directory=app/build/reports/tests/text-v4-a531-capture/")
            listOf("GITHUB_SHA", "GITHUB_REF", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_WORKFLOW", "GITHUB_WORKFLOW_REF", "GITHUB_REPOSITORY", "GITHUB_SERVER_URL", "GITHUB_JOB").forEach {
                appendLine("$it=${System.getenv(it) ?: "unset"}")
            }
            guarded.forEach { (path, blob) -> appendLine("producerGitBlob $blob $path") }
            appendLine("captureAssertions=completed; testCount=1; final JUnit/baseline result and artifact ID are supplied by the surrounding runner XML/log/API, not invented here")
            appendLine("Every extracted payload and immutable revision was written by production HybridProjectStore/HybridProjectCodec. Wrapper extraction only reads bytes.")
            appendLine("Store copies and ZIPs include every actual file below filesDir, including projects/.store.lock; copies reopened and ZIP byte inventories verified.")
        }, Charsets.UTF_8)
        File(output, "capture.log").writeText(buildString {
            appendLine("Capture assertions completed for $executionHead; P0..P4 production create/save/load/loadRevision.")
            appendLine("Read-only opens preserved complete file inventories; P3 redo and P4 pruned editable history/export verified.")
            appendLine("SHA256 BYTES PATH (capture.log is listed in final inventory.tsv; inventory.tsv excludes itself):")
            inventory(output).forEach { (path, entry) -> appendLine("${entry.hash} ${entry.size} $path") }
        }, Charsets.UTF_8)
        File(output, "inventory.tsv").writeText(buildString {
            appendLine("sha256\tbytes\tpath")
            inventory(output).forEach { (path, entry) -> appendLine("${entry.hash}\t${entry.size}\t$path") }
        }, Charsets.UTF_8)
        // The inventory excludes only itself; it covers both log/provenance and every store file/ZIP.
        println("Genuine old-v4 capture completed: ${output.absolutePath}; HEAD=$executionHead")
        println(File(output, "inventory.tsv").readText())
    }

    private fun text(id: Int): HybridTextState = HybridTextState(
        layers = listOf(
            TextLayer("mono-r$id", "R$id Привет 🐈 MONO/SCALE", 0, 900_000,
                TextStyle(TextPosition.TOP, TextFont.MONO, .025f, 0xff2b7c91.toInt(), false, TextAnimation.SCALE)),
            TextLayer("control-r$id", "R$id BOLD/SLIDE control", 100_000, 1_000_000,
                TextStyle(TextPosition.CENTER, TextFont.BOLD, .12f, 0xffd83456.toInt(), true, TextAnimation.SLIDE))),
        captions = listOf(CaptionCue("cue-a-r$id", "R$id Начало", 0, 500_000),
            CaptionCue("cue-b-r$id", "R$id End", 500_000, 1_000_000)),
        captionStyle = TextStyle(TextPosition.BOTTOM, TextFont.MONO, if (id % 2 == 0) .12f else .025f,
            0xffeab321.toInt(), id % 2 == 0, TextAnimation.SCALE),
        captionsEdited = id % 2 != 0, language = if (id % 2 == 0) "ru" else "en")

    private fun assertLegacyText(state: HybridTextState) {
        assertEquals(TextFont.MONO, state.layers.first().style.font)
        assertEquals(TextAnimation.SCALE, state.layers.first().style.animation)
        assertEquals(TextFont.MONO, state.captionStyle.font)
        assertEquals(TextAnimation.SCALE, state.captionStyle.animation)
        assertEquals(TextFont.BOLD, state.layers.last().style.font)
        assertEquals(TextAnimation.SLIDE, state.layers.last().style.animation)
        (state.layers.map { it.style } + state.captionStyle).forEach { assertNull(it.plate) }
        state.layers.forEach { assertEquals(TextLayerKind.LEGACY, it.kind) }
        state.captions.forEach {
            assertNull(it.confidence); assertFalse(it.manuallyReviewed); assertFalse(it.confidenceCalibrated)
        }
    }

    private fun assertReadOnly(files: File, expected: HybridProject) {
        val before = inventory(files)
        val store = HybridProjectStore(files)
        val loaded = store.load("project")
        assertEquals(expected, loaded)
        (listOf(loaded.original, loaded.current) + loaded.undo + loaded.redo).forEach {
            assertLegacyText(it.textState)
            assertEquals(it, store.loadRevision("project", it.id))
        }
        assertEquals(before, inventory(files))
    }

    private data class Manifest(val pointer: String, val project: ByteArray, val revisions: Map<Long, String>)

    /** Read-only extraction of the production schema-1 wrapper; no alternate binary writer. */
    private fun manifest(projectDir: File): Manifest {
        val pointer = File(projectDir, "CURRENT").readText(Charsets.US_ASCII)
        require(pointer.matches(Regex("[a-f0-9-]{36}\\.bin")))
        DataInputStream(ByteArrayInputStream(File(projectDir, "manifests/$pointer").readBytes())).use { input ->
            require(input.readInt() == 0x56485354 && input.readInt() == 1)
            val size = input.readInt()
            require(size in 8..HybridProjectCodec.MAX_MANIFEST_BYTES && size <= input.available())
            val project = ByteArray(size).also { input.readFully(it) }
            val count = input.readInt()
            require(count in 1..100_000 && count.toLong() * 72 <= input.available())
            val revisions = linkedMapOf<Long, String>()
            repeat(count) {
                val id = input.readLong()
                val hash = ByteArray(64).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                require(id >= 0 && hash.matches(Regex("[a-f0-9]{64}")) && revisions.put(id, hash) == null)
            }
            require(input.readInt() == 0 && input.available() == 0) // No analysis sidecars in this input.
            return Manifest(pointer, project, revisions)
        }
    }

    private fun revisionBytes(projectDir: File, manifest: Manifest): Map<Long, ByteArray> =
        manifest.revisions.mapValues { (_, hash) ->
            File(projectDir, "revisions/$hash.bin").readBytes().also { assertEquals(hash, sha256(it)) }
        }

    private data class Entry(val hash: String, val size: Long)
    private fun inventory(root: File): Map<String, Entry> = root.walkTopDown().filter { it.isFile }
        .sortedBy { it.relativeTo(root).invariantSeparatorsPath }.associate {
            it.relativeTo(root).invariantSeparatorsPath to Entry(sha256(it.readBytes()), it.length())
        }

    private fun snapshotStore(files: File, copy: File, zip: File, expected: HybridProject) {
        val before = inventory(files)
        assertTrue("Include hidden store lock", "projects/.store.lock" in before)
        assertTrue(files.copyRecursively(copy))
        assertEquals(before, inventory(copy))
        assertReadOnly(copy, expected)
        assertEquals(expected.original, HybridProjectStore(copy).loadRevision("project", 0))
        expected.exports.forEach { export ->
            assertEquals(HybridProjectStore(files).loadRevision("project", export.revisionId),
                HybridProjectStore(copy).loadRevision("project", export.revisionId))
        }
        ZipOutputStream(zip.outputStream()).use { archive ->
            before.keys.forEach { path ->
                archive.putNextEntry(ZipEntry(path))
                File(files, path).inputStream().use { it.copyTo(archive) }
                archive.closeEntry()
            }
        }
        val archived = linkedMapOf<String, Entry>()
        ZipFile(zip).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val bytes = archive.getInputStream(entry).use { it.readBytes() }
                assertNull(archived.put(entry.name, Entry(sha256(bytes), bytes.size.toLong())))
            }
        }
        assertEquals(before, archived)
        assertEquals(before, inventory(files))
        assertEquals(before, inventory(copy))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun git(repo: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(repo).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")}: $result" }
        return result
    }

    companion object {
        private const val BASE = "1d533e3554dae0d9f3d04286ad7dee0ad5027cc0"
        private const val SOURCE = "app/src/main/java/com/example/autoedit/"
        private const val TEST_SOURCE = "app/src/test/java/com/example/autoedit/"
    }
}
