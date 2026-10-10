package com.veycad.app

import java.io.File
import java.io.RandomAccessFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/** Sources/revisions are published before the sole mutable CURRENT pointer. */
class HybridProjectStore(
    filesDir: File,
    private val beforePointerReplace: () -> Unit = {}
) {
    private val root = ownedChild(filesDir, "projects")

    fun directory(projectId: String): File {
        require(projectId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,95}"))) { "Invalid project ID" }
        return ownedChild(root, projectId)
    }

    fun create(project: HybridProject): Unit = locked {
        val dir = directory(project.id)
        check(!ownedChild(dir, "CURRENT").exists()) { "Project already exists" }
        publish(project, null)
    }

    fun load(projectId: String): HybridProject = loadWithAnalysisStatus(projectId).project

    fun loadWithAnalysisStatus(projectId: String): ProjectLoadResult = locked { readCurrent(projectId).result }

    fun save(project: HybridProject, expectedRevisionId: Long): Unit = locked {
        val previous = readCurrent(project.id)
        check(previous.result.project.current.id == expectedRevisionId) { "Project changed since it was loaded" }
        require(project.nextRevisionId >= previous.result.project.nextRevisionId) { "Revision IDs cannot be reused" }
        require(project.original.id == previous.result.project.original.id && project.fps == previous.result.project.fps)
        val publishedAssets = previous.result.project.assets
        val publishedById = publishedAssets.associateBy { it.id }
        project.assets.forEach { asset ->
            val published = publishedById[asset.id]
            require(published == null || published == asset) {
                "Published asset ${asset.id} is immutable"
            }
        }
        // Revision blobs refer to asset IDs, including exports no longer present in undo history.
        // Retain their bindings and ordering; new imports are appended in the caller's order.
        val retainedAssets = publishedAssets + project.assets.filter { it.id !in publishedById }
        publish(project.copy(assets = retainedAssets), previous)
    }

    fun list(): List<HybridProject> = locked {
        root.listFiles().orEmpty().filter { it.isDirectory && File(it, "CURRENT").isFile }
            .sortedBy { it.name }.map { readCurrent(it.name).result.project }
    }

    fun delete(projectId: String): Unit = locked {
        val dir = directory(projectId)
        // Only this project's physical copies are removed. Never follow a linked subtree.
        if (dir.exists()) {
            java.nio.file.Files.walk(dir.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { java.nio.file.Files.delete(it) }
            }
        }
    }

    fun loadRevision(projectId: String, revisionId: Long): HybridRevision = locked {
        require(revisionId >= 0)
        val state = readCurrent(projectId)
        val hash = requireNotNull(state.revisions[revisionId]) { "Revision was never published" }
        readRevision(projectId, revisionId, hash)
    }

    private data class State(val result: ProjectLoadResult, val revisions: Map<Long, String>)

    private fun readCurrent(projectId: String): State {
        val dir = directory(projectId)
        val name = ownedChild(dir, "CURRENT").inputStream().use { it.readBytesBounded(128) }.toString(Charsets.US_ASCII)
        require(name.matches(Regex("[a-f0-9-]{36}\\.bin"))) { "Invalid project pointer" }
        val bytes = ownedChild(ownedChild(dir, "manifests"), name).inputStream().use {
            it.readBytesBounded(MAX_STATE_BYTES)
        }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == STATE_MAGIC && input.readInt() == 1) { "Unsupported storage schema" }
        val size = input.readInt()
        require(size in 8..HybridProjectCodec.MAX_MANIFEST_BYTES && size <= input.available())
        val projectBytes = ByteArray(size).also { input.readFully(it) }
        val count = input.readInt()
        require(count in 1..MAX_REVISIONS && count.toLong() * 72 <= input.available())
        val revisions = linkedMapOf<Long, String>()
        repeat(count) {
            val id = input.readLong()
            val hash = ByteArray(64).also { input.readFully(it) }.toString(Charsets.US_ASCII)
            require(id >= 0 && hash.matches(Regex("[a-f0-9]{64}")) && revisions.put(id, hash) == null)
        }
        val pendingCount = input.readInt()
        require(pendingCount in 0..MAX_REVISIONS && pendingCount.toLong() * 64 <= input.available())
        val sidecars = AnalysisSidecarStore(ownedChild(dir, "analysis"))
        val pending = linkedSetOf<String>()
        repeat(pendingCount) {
            val hash = ByteArray(64).also { input.readFully(it) }.toString(Charsets.US_ASCII)
            if (sidecars.read(hash) == null) pending += hash
        }
        require(input.available() == 0)
        val result = codec(dir).decodeWithAnalysisStatus(projectBytes)
        require(result.project.id == projectId && revisions.keys.all { it < result.project.nextRevisionId })
        val retained = listOf(result.project.original, result.project.current) + result.project.undo + result.project.redo
        require(retained.all { it.id in revisions } && result.project.exports.all { it.revisionId in revisions })
        return State(ProjectLoadResult(result.project, result.missingAnalysisHashes + pending), revisions)
    }

    private fun readRevision(projectId: String, revisionId: Long, hash: String): HybridRevision {
        val dir = directory(projectId)
        val bytes = ownedChild(ownedChild(dir, "revisions"), "$hash.bin").inputStream().use {
            it.readBytesBounded(HybridProjectCodec.MAX_MANIFEST_BYTES)
        }
        require(contentHash(bytes) == hash) { "Corrupt revision snapshot" }
        return codec(dir).decodeRevision(bytes).also { require(it.id == revisionId) }
    }

    private fun publish(project: HybridProject, previous: State?) {
        val dir = directory(project.id)
        val assets = ProjectAssetStore(dir)
        project.assets.forEach { require(assets.resolve(it).isFile) { "Import source before saving project: ${it.id}" } }
        val codec = codec(dir)
        // Encoding freezes caller-owned graph arrays and collections at the storage boundary.
        val bytes = codec.encode(project)
        val frozen = codec.decode(bytes)
        val revisions = (listOf(frozen.original, frozen.current) + frozen.undo + frozen.redo).distinctBy { it.id }
        val revisionDir = ownedChild(dir, "revisions")
        val index = previous?.revisions?.toMutableMap() ?: linkedMapOf()
        val snapshots = codec.encodeRevisions(revisions)
        for (revision in revisions) {
            val snapshot = snapshots.getValue(revision.id)
            val hash = contentHash(snapshot)
            val committedHash = index[revision.id]
            if (committedHash != null) {
                require(committedHash == hash || readRevision(project.id, revision.id, committedHash) == revision) {
                    "Revision ${revision.id} is immutable"
                }
            } else {
                require(previous == null || revision.id >= previous.result.project.nextRevisionId) { "Revision ID was already allocated" }
                atomicWrite(ownedChild(revisionDir, "$hash.bin"), snapshot)
                index[revision.id] = hash
            }
        }
        frozen.exports.forEach { require(it.revisionId in index) { "Export revision is not retained" } }
        require(index.size <= MAX_REVISIONS)
        val state = ByteArrayOutputStream()
        DataOutputStream(state).use { output ->
            output.writeInt(STATE_MAGIC); output.writeInt(1)
            output.writeInt(bytes.size); output.write(bytes)
            output.writeInt(index.size)
            index.forEach { (id, hash) -> output.writeLong(id); output.write(hash.toByteArray(Charsets.US_ASCII)) }
            // A degraded in-memory graph cannot erase the obligation to regenerate its missing caches.
            val pending = previous?.result?.missingAnalysisHashes.orEmpty()
            require(pending.size <= MAX_REVISIONS)
            output.writeInt(pending.size)
            pending.forEach { output.write(it.toByteArray(Charsets.US_ASCII)) }
        }
        val name = "${UUID.randomUUID()}.bin"
        atomicWrite(ownedChild(ownedChild(dir, "manifests"), name), state.toByteArray())
        atomicWrite(ownedChild(dir, "CURRENT"), name.toByteArray(Charsets.US_ASCII), beforePointerReplace)
    }

    private fun codec(dir: File) = HybridProjectCodec(AnalysisSidecarStore(ownedChild(dir, "analysis")))

    private fun <T> locked(block: () -> T): T = synchronized(processLock) {
        root.mkdirs()
        RandomAccessFile(ownedChild(root, ".store.lock"), "rw").use { file ->
            file.channel.lock().use { block() }
        }
    }

    companion object {
        private val processLock = Any()
        private const val STATE_MAGIC = 0x56485354
        private const val MAX_REVISIONS = 100_000
        private const val MAX_STATE_BYTES = HybridProjectCodec.MAX_MANIFEST_BYTES + MAX_REVISIONS * 136 + 20
    }
}
