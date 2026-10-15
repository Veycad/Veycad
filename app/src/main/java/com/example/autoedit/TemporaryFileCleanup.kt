package com.veycad.app

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** A deliberately small allowlist. Provider scratch and unknown caches belong to their owners. */
internal object TemporaryFileCleanup {
    enum class Scope { ALL_TEMPORARY, SOURCE_DRAFT, TRANSIENT_RENDER, TEXT_WORK }
    enum class DraftDisposition { UNTOUCHED, REMOVED, INCOMPLETE }
    data class Report(val failures: List<String>, val draft: DraftDisposition)

    private fun targets(filesDir: File, cacheDir: File, scope: Scope): List<Path> {
        val files = filesDir.canonicalFile.toPath()
        val cache = cacheDir.canonicalFile.toPath()
        return when (scope) {
            Scope.TEXT_WORK -> listOf(cache.resolve("text-work"))
            Scope.SOURCE_DRAFT -> listOf(files.resolve("source-draft"))
            Scope.TRANSIENT_RENDER -> listOf(files.resolve("pending-renders"), cache.resolve("imports"))
            Scope.ALL_TEMPORARY -> listOf(files.resolve("source-draft"), files.resolve("pending-renders"),
                cache.resolve("imports"), cache.resolve("full-video-analysis"), cache.resolve("text-work"))
        }
    }

    private fun safe(path: Path): Boolean = !Files.isSymbolicLink(path) &&
        path.toFile().canonicalFile.toPath() == path.toAbsolutePath().normalize()

    fun estimateBytes(filesDir: File, cacheDir: File, scope: Scope): Long {
        var total = 0L
        for (target in targets(filesDir, cacheDir, scope)) {
            if (!Files.exists(target, NOFOLLOW_LINKS)) continue
            if (!safe(target)) throw IOException("Unsafe cleanup target: $target")
            Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (safe(dir)) FileVisitResult.CONTINUE else FileVisitResult.SKIP_SUBTREE
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isSymbolicLink && safe(file)) total = Math.addExact(total, attrs.size())
                    return FileVisitResult.CONTINUE
                }
            })
        }
        return total
    }

    fun clear(filesDir: File, cacheDir: File, scope: Scope, lease: MediaWorkCoordinator.Lease,
        delete: (Path) -> Unit = { Files.delete(it) }): Report {
        lease.requireCleanupActive()
        val failures = linkedSetOf<String>()
        val paths = targets(filesDir, cacheDir, scope)
        for (target in paths) {
            if (!Files.exists(target, NOFOLLOW_LINKS)) continue
            try {
                if (!safe(target)) { failures += target.toString(); continue }
                Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (safe(dir)) return FileVisitResult.CONTINUE
                        failures += dir.toString()
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isSymbolicLink || !safe(file)) failures += file.toString()
                        else remove(file)
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        failures += file.toString()
                        return FileVisitResult.CONTINUE
                    }
                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        if (exc != null) failures += dir.toString() else remove(dir)
                        return FileVisitResult.CONTINUE
                    }
                    private fun remove(path: Path) {
                        try { delete(path) } catch (_: IOException) { failures += path.toString() }
                        catch (_: SecurityException) { failures += path.toString() }
                    }
                })
            } catch (_: IOException) { failures += target.toString() }
            catch (_: SecurityException) { failures += target.toString() }
        }
        val draft = if (scope == Scope.TRANSIENT_RENDER || scope == Scope.TEXT_WORK) DraftDisposition.UNTOUCHED
            else if (Files.exists(paths.first(), NOFOLLOW_LINKS)) DraftDisposition.INCOMPLETE else DraftDisposition.REMOVED
        return Report(failures.toList(), draft)
    }
}
