package com.veycad.app

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import com.veycad.app.SourceDraftManifest.Snapshot

/** Owns the current selection. Admission and UI publication belong to EditSession. */
internal class SourceDraftStore(filesDir: File, private val io: Io = DiskIo) {
    private val base = filesDir.canonicalFile
    internal val root = File(base, "source-draft")
    data class Input(val displayName: String, val open: () -> InputStream)
    data class LegacyState(val firstName: String?, val secondName: String?, val cleanupIncomplete: Boolean)
    data class Restored(val snapshot: Snapshot, val warning: String?)
    class UncertainInvalidation(cause: Throwable) : IOException("Не удалось подтвердить сброс исходников. Файлы сохранены; выберите видео заново.", cause)
    enum class Phase { FILES_PREPARED, MANIFEST_COMMITTED }
    interface Io {
        fun copyAndSync(input: InputStream, target: File)
        fun replaceManifest(target: File, bytes: ByteArray)
        fun readManifest(target: File): ByteArray
        fun checkpoint(phase: Phase) {}
    }
    object DiskIo : Io {
        override fun copyAndSync(input: InputStream, target: File) {
            FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() }
        }
        override fun replaceManifest(target: File, bytes: ByteArray) = atomicWrite(target, bytes)
        override fun readManifest(target: File) = target.inputStream().use { it.readBytesBounded(SourceDraftManifest.MAX_BYTES) }
    }
    sealed interface ReplaceResult<out T> {
        data class Published<T>(val snapshot: Snapshot, val prepared: T, val warnings: List<String>) : ReplaceResult<T>
        data class Rejected(val failure: Throwable) : ReplaceResult<Nothing>
        data class Uncertain(val failure: Throwable) : ReplaceResult<Nothing>
    }
    internal fun checkedRoot(create: Boolean = false): File {
        checkSafe(root)
        if (create && !root.exists() && !root.mkdirs()) throw IOException("Не удалось подготовить каталог исходников")
        if (root.exists() && !Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) throw IOException("Каталог исходников недоступен")
        return root
    }
    internal fun child(name: String): File {
        checkedRoot()
        return try { ownedChild(root, name).also(::checkSafe) }
        catch (failure: IllegalArgumentException) { throw IOException("Некорректный путь исходника", failure) }
    }
    internal fun checkSafe(file: File) {
        if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile != file.absoluteFile.normalize())
            throw IOException("Ссылки в каталоге исходников не поддерживаются")
    }
    fun read(): Snapshot? {
        try {
            checkedRoot()
            val pointer = child("current.json")
            if (!Files.exists(pointer.toPath(), NOFOLLOW_LINKS)) return null
            if (!Files.isRegularFile(pointer.toPath(), NOFOLLOW_LINKS)) throw IOException("Некорректный манифест исходников")
            val snapshot = SourceDraftManifest.decode(io.readManifest(pointer))
            snapshot.entries.forEach { e ->
                val file = child(e.fileName)
                if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || file.length() != e.length)
                    throw IOException("Исходник отсутствует или изменён: ${e.displayName}")
            }
            return snapshot
        } catch (failure: IOException) { throw failure }
        catch (failure: Exception) { throw IOException("Не удалось прочитать выбор исходников. Выберите видео заново.", failure) }
    }
    fun restore(legacy: LegacyState): Restored {
        read()?.let { return Restored(it, null) }
        checkedRoot(create = true)
        val restored = SourceDraftRecovery.legacy(this, legacy)
        io.replaceManifest(child("current.json"), SourceDraftManifest.encode(restored.snapshot))
        return restored
    }
    fun invalidate(lease: MediaWorkCoordinator.Lease): Snapshot {
        lease.requireCleanupActive()
        checkedRoot(create = true)
        val empty = Snapshot(UUID.randomUUID(), SourceDraftManifest.Origin.IMPORT, emptyList())
        try { io.replaceManifest(child("current.json"), SourceDraftManifest.encode(empty)) }
        catch (failure: Exception) {
            val actual = try { read() } catch (readFailure: Exception) {
                failure.addSuppressed(readFailure); throw UncertainInvalidation(failure)
            }
            if (actual != empty) throw failure
        }
        return empty
    }
    fun collectObsolete(lease: MediaWorkCoordinator.Lease, previouslyOwned: Set<String> = emptySet()): List<String> {
        lease.requireCleanupActive()
        return try {
            val current = read() ?: throw IOException("Выбор исходников не подтверждён")
            SourceDraftRecovery.collect(this, current, previouslyOwned)
        } catch (failure: Exception) { listOf(failure.message ?: "Не удалось проверить исходники для уборки") }
    }
    fun <T> replace(inputs: List<Input>, expectedCount: Int, beforeCommit: (List<File>) -> T): ReplaceResult<T> {
        val id = UUID.randomUUID()
        val created = mutableListOf<File>()
        var commitAttempted = false
        var previousBytes: ByteArray? = null
        var candidate: Snapshot? = null
        // A box distinguishes a successfully prepared null from an uncalled callback.
        var prepared: List<T> = emptyList()
        try {
            require(expectedCount in 1..SourceDraftManifest.MAX_SOURCES && inputs.size == expectedCount) { "Выберите нужное количество видео" }
            checkedRoot(create = true)
            val pointer = child("current.json")
            check(Files.isRegularFile(pointer.toPath(), NOFOLLOW_LINKS)) { "Сначала восстановите выбор исходников" }
            previousBytes = runCatching { io.readManifest(pointer) }.getOrNull()
            val staging = child(".staging-$id")
            check(staging.mkdir()) { "Не удалось подготовить импорт" }
            created += staging
            val entries = inputs.mapIndexed { index, input ->
                val staged = File(staging, "$index.partial")
                created += staged
                input.open().use { io.copyAndSync(it, staged) }
                check(staged.isFile && staged.length() > 0) { "Выбранный файл пуст" }
                val target = child("source-$index-$id.mp4")
                Files.move(staged.toPath(), target.toPath())
                created += target
                SourceDraftManifest.Entry(target.name, input.displayName, target.length())
            }
            candidate = Snapshot(id, SourceDraftManifest.Origin.IMPORT, entries)
            val bytes = SourceDraftManifest.encode(candidate)
            prepared = listOf(beforeCommit(entries.map { child(it.fileName) }))
            io.checkpoint(Phase.FILES_PREPARED)
            commitAttempted = true
            io.replaceManifest(pointer, bytes)
            io.checkpoint(Phase.MANIFEST_COMMITTED)
            val warnings = removeOwned(created.filter { it.parentFile != root || it == staging })
            return ReplaceResult.Published(candidate, prepared.single(), warnings)
        } catch (failure: Exception) {
            if (commitAttempted) {
                // An exception is not proof that atomicWrite failed before the replacement.
                // Exact unchanged bytes also prove rejection when the OLD document was corrupt.
                val unchanged = try { previousBytes != null && io.readManifest(child("current.json")).contentEquals(previousBytes) }
                    catch (_: Exception) { false }
                if (!unchanged) {
                    val current = try { read() } catch (readFailure: Exception) {
                        failure.addSuppressed(readFailure)
                        removeOwned(created.filter { it.parentFile != root || it.name.startsWith(".staging-") })
                            .forEach { failure.addSuppressed(IOException(it)) }
                        return ReplaceResult.Uncertain(failure)
                    }
                    if (current?.batchId == id && candidate == current && prepared.isNotEmpty()) {
                        val warnings = removeOwned(created.filter { it.parentFile != root || it.name.startsWith(".staging-") })
                        return ReplaceResult.Published(current, prepared.single(), listOf(failure.message ?: "Ошибка после фиксации") + warnings)
                    }
                    return ReplaceResult.Uncertain(failure)
                }
            }
            removeOwned(created).forEach { failure.addSuppressed(IOException(it)) }
            return ReplaceResult.Rejected(failure)
        }
    }
    /** Only exact paths created by this operation, in child-before-parent order. */
    private fun removeOwned(paths: List<File>): List<String> = paths.asReversed().distinct().mapNotNull { file ->
        try {
            checkedRoot(); checkSafe(file)
            Files.deleteIfExists(file.toPath()); null
        } catch (failure: Exception) { "Не удалось удалить остаток ${file.name}: ${failure.message}" }
    }
}
