package com.veycad.app

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Temporary phase1 utility. Remove only after the controller retains verified CI bytes. */
class HybridTimingV5FixtureCaptureTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun capturesUnchangedV5StoreWithLiteralRequestsAndRetainedExport() {
        val repository = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, CODEC_PATH).isFile }
        val checkoutCommit = git(repository, "rev-parse", "HEAD")
        assertTrue(checkoutCommit.matches(Regex("[0-9a-f]{40}")))
        // A pull_request checkout can be a merge ref. Guard the producer, not GITHUB_SHA == PR head.
        assertEquals(PRODUCTION_TREE, git(repository, "rev-parse", "HEAD:app/src/main"))
        assertEquals("", git(repository, "status", "--porcelain", "--untracked-files=no", "--", "app/src/main"))
        assertEquals(CODEC_BLOB, git(repository, "rev-parse", "HEAD:$CODEC_PATH"))
        assertEquals(CODEC_BLOB, gitBlob(File(repository, CODEC_PATH)))
        assertEquals(EDIT_HELPER_BLOB, git(repository, "rev-parse", "HEAD:$EDIT_HELPER_PATH"))
        assertEquals(EDIT_HELPER_BLOB, gitBlob(File(repository, EDIT_HELPER_PATH)))
        val captureBlob = git(repository, "rev-parse", "HEAD:$CAPTURE_PATH")
        assertEquals(captureBlob, gitBlob(File(repository, CAPTURE_PATH)))
        val captureCommit = git(repository, "log", "-1", "--format=%H", "--", CAPTURE_PATH)
        assertEquals(captureBlob, git(repository, "rev-parse", "$captureCommit:$CAPTURE_PATH"))
        assertEquals(WORKFLOW_BLOB, git(repository, "rev-parse", "HEAD:$WORKFLOW_PATH"))
        assertEquals(WORKFLOW_BLOB, gitBlob(File(repository, WORKFLOW_PATH)))

        val files = temporary.newFolder("store")
        val store = HybridProjectStore(files)
        val assets = ProjectAssetStore(store.directory("project"))
        val videoFile = temporary.newFile("synthetic-video.txt").apply {
            writeText("A2t-v5 synthetic video bytes\n", Charsets.UTF_8)
        }
        val audioFile = temporary.newFile("synthetic-audio.txt").apply {
            writeText("A2t-v5 synthetic audio bytes\n", Charsets.UTF_8)
        }
        assertEquals(29L, videoFile.length())
        assertEquals(29L, audioFile.length())
        assertEquals(VIDEO_HASH, contentHash(videoFile.readBytes()))
        assertEquals(AUDIO_HASH, contentHash(audioFile.readBytes()))
        val source = MediaSource("capture-selection", videoFile, "Synthetic v5 selection", 3_000_000,
            29, 0, 1080, 1920, "video/avc", 3, true, VIDEO_HASH, 120_000, 3_000_000)
        val video = assets.importVideo(source, 1f)
        val audio = assets.import(audioFile, ProjectAsset.Kind.AUDIO, 3_000_000)
        val selection = SelectedVideo.fromInspected(source, video, SourceOwnership.IMPORTED)
        val template = editProject()
        val initial = template.current.copy(clips = template.current.clips.map { it.copy(assetId = video.id) },
            music = template.current.music.copy(assetId = audio.id))
        var project = HybridProject("project", 1, 30, 1, listOf(video, audio), initial, initial,
            emptyList(), emptyList(), emptyList(), listOf(selection))
        store.create(project)
        fun publish(next: HybridProject) {
            store.save(next, project.current.id)
            project = next
        }
        publish(HybridEditCommands.moveCut(project, "right", 28)) // revision 1, signed phase -2
        publish(HybridEditCommands.slipClip(project, "right", 123_456).let { it.copy(exports = listOf(
            ProjectExportRef(2, "synthetic-old-export.mp4", ProjectExportSettings(1080, 1920, 30, 1000, 100)))) })
        publish(HybridEditCommands.restoreAutomatic(project)) // revision 3, automatic selection barrier
        val authored = SourceTimeMap(listOf(SourceTimeMap.Point(0, 300_000),
            SourceTimeMap.Point(10, 600_000), SourceTimeMap.Point(30, 1_500_000)))
        publish(HybridEditCommands.commitRevision(project, project.current.copy(clips = project.current.clips.map {
            if (it.id == "right") it.copy(sourceMap = authored) else it
        }))) // revision 4, hidden authored backing
        publish(HybridEditCommands.moveCut(project, "right", 42)) // revision 5, phase 12
        publish(HybridEditCommands.slipClip(project, "right", 123_456)) // revision 6

        val directory = store.directory(project.id)
        val beforeLoad = inventory(files)
        val reopenedStore = HybridProjectStore(files)
        val reopened = reopenedStore.loadWithAnalysisStatus(project.id)
        assertFalse(reopened.analysisRegenerationRequired)
        assertEquals(project, reopened.project)
        assertEquals(7L, reopened.project.nextRevisionId)
        assertEquals((0L..5L).toList(), reopened.project.undo.map { it.id })
        assertTrue(reopened.project.redo.isEmpty())
        assertEquals(listOf(selection), reopened.project.selectedVideos)
        reopened.project.assets.forEach { asset ->
            assertEquals(29L, assets.resolve(asset).length())
            assertEquals(asset.contentHash, contentHash(assets.resolve(asset).readBytes()))
        }
        assertEquals(120_000L, reopened.project.assets.first().videoMetadata!!.firstVideoPtsUs)
        assertEquals(3_000_000L, reopened.project.assets.first().videoMetadata!!.videoEndPtsUs)
        assertEquals(3_000_000L, reopened.project.assets.first().durationUs)
        val revisions = (0L..6L).associateWith { reopenedStore.loadRevision(project.id, it) }
        assertLiteralRequests(revisions)
        assertEquals(revisions.getValue(2), reopenedStore.loadRevision(project.id, reopened.project.exports.single().revisionId))
        val maps = File(directory, "authored-maps")
        val reader = AuthoredSourceMapStore(maps).reader()
        maps.listFiles()!!.forEach { file ->
            assertEquals(file.nameWithoutExtension, contentHash(file.readBytes()))
            reader.resolve(file.nameWithoutExtension)
        }
        assertTrue(File(directory, "analysis").walkTopDown().none { it.isFile })
        assertEquals(beforeLoad, inventory(files)) // opening/old-export/map resolution did not rewrite anything

        val output = File(repository, "app/build/reports/tests/hybrid-v5-capture")
        check(!output.exists()) { "Capture output must be fresh; use the existing clean CI gate" }
        check(output.mkdirs())
        assertTrue(files.copyRecursively(File(output, "store"))) // entire genuine tree, including all old manifests
        val codec = HybridProjectCodec(AnalysisSidecarStore(File(directory, "analysis")), AuthoredSourceMapStore(maps))
        val indexLines = mutableListOf("manifest\trevisionId\tsha256\tprojectPayloadSha256")
        val currentName = File(directory, "CURRENT").readText(Charsets.US_ASCII)
        File(directory, "manifests").listFiles()!!.sortedBy { it.name }.forEach { manifest ->
            // Read the genuine store envelope; never reconstruct it or implement a binary writer.
            DataInputStream(manifest.inputStream()).use { input ->
                assertEquals(0x56485354, input.readInt())
                assertEquals(1, input.readInt())
                val size = input.readInt()
                assertTrue(size in 8..65_536)
                val payload = ByteArray(size).also { input.readFully(it) }
                assertV5(payload)
                codec.decode(payload)
                if (manifest.name == currentName) {
                    assertEquals(project, codec.decode(payload))
                    File(output, "project.bin").writeBytes(payload) // literal extraction, duplicate of CURRENT's payload
                }
                val count = input.readInt()
                assertTrue(count in 1..7)
                repeat(count) {
                    val id = input.readLong()
                    val hash = ByteArray(64).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                    val bytes = File(directory, "revisions/$hash.bin").readBytes()
                    assertEquals(hash, contentHash(bytes))
                    assertV5(bytes)
                    assertEquals(revisions.getValue(id), codec.decodeRevision(bytes))
                    indexLines += "${manifest.name}\t$id\t$hash\t${contentHash(payload)}"
                    if (manifest.name == currentName) {
                        File(output, "revision-$id.bin").writeBytes(bytes) // duplicate of a genuine published blob
                    }
                }
                assertEquals(0, input.readInt()) // no pending analysis hashes
                assertEquals(0, input.available())
            }
        }
        assertTrue(File(output, "project.bin").isFile)
        assertEquals(beforeLoad, inventory(File(output, "store")))
        File(output, "manifest-revision-index.tsv").writeText(indexLines.joinToString("\n", postfix = "\n"))
        File(output, "literal-expectations.md").writeText(LITERAL_EXPECTATIONS)
        File(output, "observed-revisions.tsv").writeText(buildString {
            append("revision\tclip\tstart\tend\tphase\tfirstRequestUs\tlastBoundaryUs\tbackingPhase\tslipUs\tselectedRevision\tautomatic\tresetEvent\n")
            revisions.forEach { (id, revision) -> revision.clips.forEach { clip ->
                val backing = revision.sourceBackings[clip.id]
                append("$id\t${clip.id}\t${clip.span.start}\t${clip.span.endExclusive}\t${clip.originalFrameOffset}\t")
                append("${clip.sourceMap.sample(0)}\t${clip.sourceMap.sample(clip.span.length)}\t")
                append("${backing?.phaseStart}\t${backing?.slipUs}\t${backing?.selectedRevisionId}\t${backing?.automatic}\t${revision.restoresAutomaticSources}\n")
            } }
        })
        File(output, "provenance.txt").writeText(buildString {
            append("accepted_base=$BASE\ncapture_checkout_commit=$checkoutCommit\n")
            append("capture_utility_commit=$captureCommit\nexisting_baseline_workflow_blob=$WORKFLOW_BLOB\n")
            append("production_main_tree=$PRODUCTION_TREE\nproduction_codec_blob=$CODEC_BLOB\n")
            append("capture_utility_blob=$captureBlob\nedit_helper_blob=$EDIT_HELPER_BLOB\nphysical_format=5\n")
            append("current_manifest=$currentName\nsource_bytes=synthetic_only\n")
            append("creation_api=ProjectAssetStore.importVideo;SelectedVideo.fromInspected;HybridProjectStore.create/save\n")
            append("capture_api=HybridProjectStore.loadWithAnalysisStatus/loadRevision;HybridProjectCodec.decode/decodeRevision\n")
            append("execution_gate=existing Android baseline clean baselineVerify; not timing-proof RED/GREEN\n")
            listOf("GITHUB_SHA", "GITHUB_REF", "GITHUB_HEAD_REF", "GITHUB_BASE_REF", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_JOB")
                .forEach { name -> append("$name=${System.getenv(name) ?: "unavailable"}\n") }
        })
        File(output, "README.md").writeText("""
            Genuine physical-v5 capture from unchanged production code. Synthetic bytes are not media or timing measurement.
            store/ is the complete store tree; project.bin and revision-0.bin through revision-6.bin are byte-for-byte
            convenience copies extracted from its current envelope and published blobs. No hand-written binary encoder.
            provenance.txt records the actual checkout commit (possibly a CI merge ref), producer and utility blobs.
            SHA256SUMS.tsv records length and SHA-256 for every emitted file except itself.
            Verify the exact CI run/artifact digest, utility commit ancestry, producer blob, all checksums,
            CURRENT/envelope indexes, physical-v5 headers, maps and literal expectations before retaining resources.
            Utility GREEN proves fixture capture only. Phase2, proof persistence/projection and device acceptance remain pending.
        """.trimIndent() + "\n")
        val captured = inventory(output)
        assertTrue(captured.size <= 100)
        assertTrue(output.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= 1_048_576)
        File(output, "SHA256SUMS.tsv").writeText("path\tbytes\tsha256\n" + captured.entries.joinToString("\n", postfix = "\n") {
            "${it.key}\t${it.value}"
        })
    }

    private fun assertLiteralRequests(revisions: Map<Long, HybridRevision>) {
        val extended = revisions.getValue(1).clips[1]
        assertEquals(FrameSpan(28, 60), extended.span)
        assertEquals(-2, extended.originalFrameOffset)
        assertEquals(433_333L, extended.sourceMap.sample(0))
        assertEquals(500_000L, extended.sourceMap.sample(2))
        val oldExport = revisions.getValue(2).clips[1]
        assertEquals(-2, oldExport.originalFrameOffset)
        assertEquals(556_789L, oldExport.sourceMap.sample(0))
        assertEquals(623_456L, oldExport.sourceMap.sample(2))
        assertEquals(1_623_456L, oldExport.sourceMap.sample(32))
        assertEquals(123_456L, revisions.getValue(2).sourceBackings.getValue("right").slipUs)
        assertEquals(0L, revisions.getValue(2).sourceBackings.getValue("right").selectedRevisionId)
        val reset = revisions.getValue(3)
        assertTrue(reset.restoresAutomaticSources)
        assertEquals(FrameSpan(30, 60), reset.clips[1].span)
        assertEquals(500_000L, reset.clips[1].sourceMap.sample(0))
        reset.sourceBackings.values.forEach { backing ->
            assertTrue(backing.automatic); assertEquals(3L, backing.selectedRevisionId); assertEquals(0L, backing.slipUs)
        }
        val final = revisions.getValue(6)
        assertEquals(300_000L, revisions.getValue(4).clips[1].sourceMap.sample(0))
        assertEquals(600_000L, revisions.getValue(4).clips[1].sourceMap.sample(10))
        assertEquals(690_000L, revisions.getValue(5).clips[1].sourceMap.sample(0))
        assertEquals(12, revisions.getValue(5).clips[1].originalFrameOffset)
        val right = final.clips[1]
        assertFalse(final.restoresAutomaticSources)
        assertEquals(FrameSpan(42, 60), right.span)
        assertEquals(12, right.originalFrameOffset)
        assertEquals(813_456L, right.sourceMap.sample(0))
        assertEquals(1_038_456L, right.sourceMap.sample(5))
        assertEquals(1_623_456L, right.sourceMap.sample(18))
        val backing = final.sourceBackings.getValue("right")
        assertFalse(backing.automatic)
        assertEquals(0, backing.phaseStart)
        assertEquals(123_456L, backing.slipUs)
        assertEquals(4L, backing.selectedRevisionId)
        assertEquals(300_000L, backing.map.sample(0))
        assertEquals(600_000L, backing.map.sample(10))
        assertEquals(1_500_000L, backing.map.sample(30))
    }

    private fun assertV5(bytes: ByteArray) {
        assertEquals(0x56485942, ByteBuffer.wrap(bytes).getInt(0))
        assertEquals(5, ByteBuffer.wrap(bytes).getInt(4))
    }

    private fun inventory(directory: File): Map<String, String> = directory.walkTopDown().filter { it.isFile }
        .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }.associate { file ->
            check(file.length() <= 65_536) { "Capture file unexpectedly large" }
            file.relativeTo(directory).invariantSeparatorsPath to "${file.length()}\t${contentHash(file.readBytes())}"
        }

    private fun git(repository: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repository.absolutePath) + arguments).redirectErrorStream(true).start()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "Git provenance command timed out" }
        val output = process.inputStream.use { it.readBytesBounded(4096).toString(Charsets.UTF_8).trim() }
        check(process.exitValue() == 0) { "Git provenance command failed: $output" }
        return output
    }

    private fun gitBlob(file: File): String {
        val bytes = file.readBytes()
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val BASE = "266ebfa36c9ae83c256ab72d1713897c1b4e7879"
        private const val PRODUCTION_TREE = "9dbc3b957eec3d99c73a2b0c7862f7c595764270"
        private const val CODEC_PATH = "app/src/main/java/com/example/autoedit/HybridProjectCodec.kt"
        private const val CODEC_BLOB = "81dd37d2321e1f5d2b7baa67eb9dce558cb99906"
        private const val EDIT_HELPER_PATH = "app/src/test/java/com/example/autoedit/HybridEditCommandsTest.kt"
        private const val EDIT_HELPER_BLOB = "3a84064199f16fdc668209c6f478c5eb306b975d"
        private const val CAPTURE_PATH = "app/src/test/java/com/example/autoedit/HybridTimingV5FixtureCaptureTest.kt"
        private const val WORKFLOW_PATH = ".github/workflows/android-baseline.yml"
        private const val WORKFLOW_BLOB = "f35bf6385ef50044e326172d9589f3e918132fd9"
        private const val VIDEO_HASH = "588f04ca0b7c6725771dd73d2bd7fa15de84ed1660793b4a5e7ce85910b2aa1f"
        private const val AUDIO_HASH = "43e74c725c738e1b3c99eed0a69540f7251d2bbd612e2f03b6a7e50d119f9305"
        private val LITERAL_EXPECTATIONS = """
            # Literal v5 capture inputs and expectations
            Project=project; schema=1; fps=30; original=0; current=6; nextRevisionId=7; undo=0,1,2,3,4,5; redo=empty.
            Video UTF-8 bytes="A2t-v5 synthetic video bytes\n"; audio="A2t-v5 synthetic audio bytes\n"; each length=29.
            Video SHA256=$VIDEO_HASH; audio SHA256=$AUDIO_HASH. The \n denotes one literal LF byte.
            Selection=capture-selection/Synthetic v5 selection/IMPORTED; geometry=1080x1920, rotation=0, PAR=1;
            mime=video/avc, colorTransfer=3, hasAudio=true; declared=3000000; firstVideoPtsUs=120000; videoEndPtsUs=3000000.
            Both physical endpoints are unversioned facts. v5 has no recognized timing proof field.
            Three sourceIndex=0 clips: left[0,30), right[30,60), last[60,90); original maps=(0,500000),(30,1500000).
            r1 moveCut(right,28): right span[28,60), phase=-2, requests frame0=433333, frame2=500000.
            r2 slipClip(right,123456): phase=-2, frame0=556789, frame2=623456, boundary32=1623456;
            right backing phaseStart=0, selectedRevisionId=0, slipUs=123456, automatic=true.
            Export references r2 as synthetic-old-export.mp4 (reference only; no media output), 1080x1920/30fps/1000+100 bitrate.
            r3 restoreAutomatic: resetEvent=true; right[30,60), phase=0, firstRequest=500000;
            all backings automatic=true, selectedRevisionId=3, slipUs=0.
            r4 explicit right map=(0,300000),(10,600000),(30,1500000): selectedRevisionId=4, automatic=false.
            r5 moveCut(right,42): right[42,60), phase=12, firstRequest=690000, selected authored backing retained.
            r6 slipClip(right,123456): right[42,60), phase=12; frame0=813456, frame5=1038456, boundary18=1623456;
            right backing phaseStart=0, slipUs=123456, selectedRevisionId=4, automatic=false;
            hidden backing frame0=300000, frame10=600000, boundary30=1500000. Current resetEvent=false.
            All seven revisions published through the real store. All old manifests, map hashes, source bytes,
            revision blobs, CURRENT and its complete referenced manifest are retained in store/.
            No analysis planes, actual inspection, recognition version, Gallery admission, native EOS or A2b acceptance.
        """.trimIndent() + "\n"
    }
}
