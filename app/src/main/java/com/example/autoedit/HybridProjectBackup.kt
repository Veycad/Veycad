package com.veycad.app

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Portable raw backup; recovery forks a new project through the current canonical codec. */
class HybridProjectBackup(filesDir: File, private val beforePublish: () -> Unit = {}) {
    private val files = filesDir.canonicalFile
    private val store = HybridProjectStore(files)
    data class Receipt(val projectId: String, val fileCount: Int, val bytes: Long, val missingOutputs: Int,
        val cleanupIncomplete: Boolean = false, val legacyUnverifiedSources: Int = 0)
    private data class Entry(val path: String, val bytes: Long, val hash: String, val file: File? = null)

    fun export(projectId: String, output: OutputStream): Receipt = store.withBackupSnapshot(projectId) { project, directory ->
        val entries = mutableListOf<Entry>()
        Files.walk(directory.toPath()).use { paths -> paths.forEach { path ->
            require(!Files.isSymbolicLink(path)) { "Project links cannot be backed up" }
            if (Files.isRegularFile(path)) {
                val name = directory.toPath().relativize(path).toString().replace('\\', '/')
                // Unpublished write scratch is not a committed project snapshot.
                if (!name.substringAfterLast('/').startsWith(".pending-") && !name.substringAfterLast('/').startsWith(".import-")) {
                    require(entries.size < MAX_FILES) { "Backup has too many files" }
                    entries += inventoried("project/$name", path.toFile())
                }
            }
        } }
        require(project.exports.size <= MAX_FILES) { "Backup has too many results" }
        val unverified = validateSources(project, directory)
        var missing = 0
        for (name in project.exports.map { it.fileName }.distinct()) {
            val movie = ownedChild(ownedChild(files, "completed-renders"), name)
            if (movie.isFile && movie.length() > 0) {
                entries += inventoried("exports/$name", movie)
                val metadata = ownedChild(movie.parentFile, "$name.properties")
                if (metadata.isFile) entries += inventoried("exports/$name.properties", metadata)
            } else missing++
        }
        require(entries.size in 1..MAX_FILES)
        require(entries.map { it.path }.distinct().size == entries.size)
        val total = total(entries)
        val manifest = JSONObject().put("format", 1).put("projectId", projectId).put("missingOutputs", missing)
            .put("legacyUnverifiedSources", unverified)
            .put("files", JSONArray().apply { entries.sortedBy { it.path }.forEach {
                put(JSONObject().put("path", it.path).put("bytes", it.bytes).put("sha256", it.hash))
            } }).toString().toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_MANIFEST) { "Backup inventory is too large" }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest); zip.closeEntry()
            entries.sortedBy { it.path }.forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.path))
                val hash = MessageDigest.getInstance("SHA-256")
                val count = entry.file!!.inputStream().use { copyBounded(it, zip, entry.bytes, hash) }
                require(count == entry.bytes && hex(hash.digest()) == entry.hash) { "Project changed while its backup was written" }
                zip.closeEntry()
            }
        }
        Receipt(projectId, entries.size, total, missing, legacyUnverifiedSources = unverified)
    }

    fun restore(input: InputStream): Receipt {
        val stage = ownedChild(files, ".project-restore-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val publishedMovies = mutableListOf<File>()
        var installed = false
        var newId: String? = null
        var result: Receipt? = null
        var failure: Throwable? = null
        try {
            val archive = File(stage, "backup.zip")
            input.use { source -> FileOutputStream(archive).use { target ->
                copyBounded(source, target, MAX_BYTES + MAX_MANIFEST + MAX_FILES * 1024L, storageCheck = true)
                target.fd.sync()
            } }
            validateZipDirectory(archive)
            var originalId = ""
            var entries = emptyList<Entry>()
            var missing = 0
            var unverified = 0
            ZipFile(archive).use { zip ->
                val members = zip.entries().toList()
                require(members.size in 2..MAX_FILES + 1 && members.none { it.isDirectory })
                require(members.map { it.name }.distinct().size == members.size) { "Duplicate backup paths" }
                val header = requireNotNull(zip.getEntry("manifest.json")) { "Backup inventory is missing" }
                require(header.size in 1..MAX_MANIFEST.toLong())
                val manifest = JSONObject(zip.getInputStream(header).use { it.readBytesBounded(MAX_MANIFEST) }.toString(Charsets.UTF_8))
                require(manifest.getInt("format") == 1) { "Unsupported backup format" }
                originalId = manifest.getString("projectId")
                require(originalId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,95}")))
                missing = manifest.getInt("missingOutputs").also { require(it in 0..MAX_FILES) }
                unverified = manifest.optInt("legacyUnverifiedSources", 0).also { require(it in 0..MAX_FILES) }
                val rows = manifest.getJSONArray("files")
                require(rows.length() in 1..MAX_FILES)
                entries = (0 until rows.length()).map { index ->
                    val row = rows.getJSONObject(index)
                    Entry(row.getString("path"), row.getLong("bytes"), row.getString("sha256")).also {
                        require(validPath(it.path) && it.bytes in 0..MAX_BYTES && it.hash.matches(Regex("[a-f0-9]{64}")))
                        require(!it.path.endsWith(".properties") || it.bytes <= 1024 * 1024)
                    }
                }
                require(entries.map { it.path }.distinct().size == entries.size) { "Duplicate inventory paths" }
                require(members.map { it.name }.toSet() == entries.map { it.path }.toSet() + "manifest.json")
                require(entries.any { it.path == "project/CURRENT" })
                val total = total(entries)
                require(files.usableSpace > total + HEADROOM) { "Not enough storage to restore this backup safely" }
                for (entry in entries) {
                    val member = requireNotNull(zip.getEntry(entry.path))
                    require(member.size == entry.bytes) { "Backup length mismatch: ${entry.path}" }
                    val target = if (entry.path.startsWith("project/")) File(stage, "projects/$originalId/${entry.path.removePrefix("project/")}")
                        else File(stage, entry.path)
                    require(target.canonicalFile.toPath().startsWith(stage.toPath()))
                    check(target.parentFile.mkdirs() || target.parentFile.isDirectory)
                    val hash = MessageDigest.getInstance("SHA-256")
                    val count = zip.getInputStream(member).use { source -> FileOutputStream(target).use { destination ->
                        copyBounded(source, destination, entry.bytes, hash, storageCheck = true).also { destination.fd.sync() }
                    } }
                    require(count == entry.bytes && hex(hash.digest()) == entry.hash) { "Backup hash mismatch: ${entry.path}" }
                }
            }
            val stagedStore = HybridProjectStore(stage)
            val project = stagedStore.load(originalId)
            project.exports.forEach { stagedStore.loadRevision(originalId, it.revisionId) }
            require(validateSources(project, stagedStore.directory(originalId)) == unverified) {
                "Legacy source identity warning differs from the saved project"
            }
            val restoredId = UUID.randomUUID().toString()
            newId = restoredId
            val names = project.exports.map { it.fileName }.distinct().associateWith { "restored-$restoredId-${UUID.randomUUID()}.mp4" }
            val restored = stagedStore.forkRestoredProject(originalId, restoredId, names)
            restored.exports.forEach { stagedStore.loadRevision(restoredId, it.revisionId) }
            val results = ownedChild(files, "completed-renders").apply { check(mkdirs() || isDirectory) }
            for ((oldName, freshName) in names) {
                val movie = File(stage, "exports/$oldName")
                if (!movie.isFile) continue
                val target = ownedChild(results, freshName)
                val metadata = ownedChild(results, "$freshName.properties")
                check(!target.exists() && !metadata.exists()) { "Restore output already exists" }
                val properties = Properties().apply {
                    val previous = File(stage, "exports/$oldName.properties")
                    if (previous.isFile) previous.inputStream().use(::load)
                    setProperty("projectId", restoredId)
                }
                atomicWrite(metadata, java.io.ByteArrayOutputStream().also { properties.store(it, null) }.toByteArray())
                publishedMovies += metadata
                Files.move(movie.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                publishedMovies += target
            }
            beforePublish()
            store.installRestoredProject(stage, restoredId)
            installed = true
            result = Receipt(restoredId, entries.size, total(entries), missing, legacyUnverifiedSources = unverified)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                // A completed atomic directory move is authoritative even if the platform reports a late error.
                if (!installed && newId?.let { store.directory(it).exists() } != true) publishedMovies.asReversed().forEach {
                    check(!it.exists() || it.delete()) { "Could not remove incomplete restore output" }
                }
                removeStage(stage)
            } catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup)
                else if (installed) result = result?.copy(cleanupIncomplete = true)
                else throw cleanup
            }
        }
        return requireNotNull(result)
    }

    private fun inventoried(path: String, file: File): Entry {
        require(validPath(path) && !Files.isSymbolicLink(file.toPath()) && file.isFile)
        require(!path.endsWith(".properties") || file.length() <= 1024 * 1024)
        return Entry(path, file.length().also { require(it in 0..MAX_BYTES) }, digest(file), file)
    }

    /** Legacy placeholder identities remain historical data, never a claim of inspected SHA-256.
     * Transfer inventory still authenticates every actual archived byte on export and restore. */
    private fun validateSources(project: HybridProject, directory: File): Int {
        val legacy = project.selectedVideos == null && project.assets.none { it.videoMetadata != null }
        var unverified = 0
        for (asset in project.assets) {
            val source = ownedChild(ownedChild(directory, "sources"), asset.fileName)
            require(source.isFile) { "Project source is missing: ${asset.displayName}" }
            if (asset.contentHash.matches(Regex("[a-fA-F0-9]{64}"))) {
                require(digest(source).equals(asset.contentHash, ignoreCase = true)) { "Project source changed: ${asset.displayName}" }
            } else {
                require(legacy) { "Inspected source has no valid SHA-256: ${asset.displayName}" }
                unverified++
            }
        }
        return unverified
    }

    private fun total(entries: List<Entry>): Long = entries.fold(0L) { sum, entry ->
        require(entry.bytes <= MAX_BYTES - sum) { "Backup exceeds the supported size" }; sum + entry.bytes
    }

    private fun validPath(path: String): Boolean = path.length in 1..512 && '\\' !in path &&
        path.none { Character.isISOControl(it) } && path.split('/').let { parts ->
            parts.size in 2..8 && parts.first() in setOf("project", "exports") &&
                parts.all { it.isNotEmpty() && it != "." && it != ".." && ':' !in it && it.length <= 255 } &&
                (parts.first() != "exports" || parts.size == 2)
        }

    private fun copyBounded(input: InputStream, output: OutputStream, limit: Long, digest: MessageDigest? = null,
        storageCheck: Boolean = false): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            require(count.toLong() <= limit - total) { "Backup member exceeds its declared length" }
            if (storageCheck && total % (16L * 1024 * 1024) == 0L) {
                require(files.usableSpace > HEADROOM + count) { "Not enough storage to finish this backup safely" }
            }
            output.write(buffer, 0, count); digest?.update(buffer, 0, count); total += count
        }
        return total
    }

    private fun digest(file: File): String = file.inputStream().use { source ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = source.read(buffer); if (count < 0) break; if (count > 0) digest.update(buffer, 0, count) }
        hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun removeStage(stage: File) {
        require(stage.parentFile.canonicalFile == files && stage.name.matches(Regex("\\.project-restore-[a-f0-9-]{36}")))
        Files.walk(stage.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    /** Bound the central directory before ZipFile allocates it; ZIP64 supports large owned videos. */
    private fun validateZipDirectory(file: File) = RandomAccessFile(file, "r").use { input ->
        require(input.length() >= 22)
        val tail = ByteArray(minOf(input.length(), 65_557).toInt())
        input.seek(input.length() - tail.size); input.readFully(tail)
        fun u16(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes, at, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        fun u32(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        fun u64(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes, at, 8).order(ByteOrder.LITTLE_ENDIAN).long.also { require(it >= 0) }
        val end = (tail.size - 22 downTo 0).firstOrNull {
            u32(tail, it) == 0x06054b50L && it + 22 + u16(tail, it + 20) == tail.size
        } ?: error("Backup ZIP is incomplete")
        require(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0)
        var count = u16(tail, end + 10).toLong()
        var size = u32(tail, end + 12)
        var offset = u32(tail, end + 16)
        var directoryEnd = input.length() - tail.size + end
        if (count == 65535L || size == 0xffffffffL || offset == 0xffffffffL) {
            require(directoryEnd >= 20)
            val locator = ByteArray(20); input.seek(directoryEnd - 20); input.readFully(locator)
            require(u32(locator, 0) == 0x07064b50L && u32(locator, 4) == 0L && u32(locator, 16) == 1L)
            val position = u64(locator, 8); require(position <= directoryEnd - 76)
            val record = ByteArray(56); input.seek(position); input.readFully(record)
            require(u32(record, 0) == 0x06064b50L && u64(record, 4) in 44..1024L)
            require(u32(record, 16) == 0L && u32(record, 20) == 0L && u64(record, 24) == u64(record, 32))
            require(position + 12 + u64(record, 4) <= directoryEnd - 20)
            count = u64(record, 32); size = u64(record, 40); offset = u64(record, 48); directoryEnd = position
        } else require(u16(tail, end + 8).toLong() == count)
        require(count in 2..MAX_FILES + 1L && size in 46..16L * 1024 * 1024 && offset <= directoryEnd - size)
        val central = ByteArray(size.toInt()); input.seek(offset); input.readFully(central)
        var at = 0
        repeat(count.toInt()) {
            require(at <= central.size - 46 && u32(central, at) == 0x02014b50L)
            val mode = (u32(central, at + 38) ushr 16).toInt() and 0xf000
            require(mode == 0 || mode == 0x8000) { "Backup links and special files are forbidden" }
            require(u16(central, at + 8) and 1 == 0 && u16(central, at + 34) == 0)
            val name = u16(central, at + 28); val extra = u16(central, at + 30); val comment = u16(central, at + 32)
            require(name in 1..2048 && at.toLong() + 46 + name + extra + comment <= central.size)
            at += 46 + name + extra + comment
        }
        require(at == central.size)
    }

    companion object {
        // One project at a time; reject larger inventories rather than growing the Android heap without a bound.
        private const val MAX_FILES = 10_000
        private const val MAX_MANIFEST = 4 * 1024 * 1024
        private const val MAX_BYTES = 64L * 1024 * 1024 * 1024
        private const val HEADROOM = 16L * 1024 * 1024
    }
}
