package com.veycad.app

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import com.veycad.app.SourceDraftManifest.Entry
import com.veycad.app.SourceDraftManifest.Origin
import com.veycad.app.SourceDraftManifest.Snapshot

/** Conservative legacy recognition and an exact allowlist for abandoned store-owned files. */
internal object SourceDraftRecovery {
    private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val sourceName = Regex("source-([0-9]|1[0-9])-($UUID_PATTERN)\\.mp4")
    private val stagingName = Regex("\\.staging-$UUID_PATTERN")
    private val temporaryName = Regex("\\.pending-[A-Za-z0-9_-]+\\.tmp")
    private val stagedFile = Regex("(?:[0-9]|1[0-9])\\.partial")
    private val mediaExtensions = setOf("mp4", "mov", "m4v", "webm", "mkv", "3gp")
    fun legacy(store: SourceDraftStore, prefs: SourceDraftStore.LegacyState): SourceDraftStore.Restored {
        val empty = Snapshot(UUID.randomUUID(), Origin.IMPORT, emptyList())
        if (prefs.cleanupIncomplete) return SourceDraftStore.Restored(empty, null)
        val listed = store.checkedRoot().listFiles() ?: throw IOException("Не удалось прочитать каталог исходников")
        val candidates = listed.filter { it.extension.lowercase(java.util.Locale.ROOT) in mediaExtensions }
        val unsafe = listed.any { runCatching { store.checkSafe(it) }.isFailure }
        val sorted = candidates.sortedBy { sourceName.matchEntire(it.name)?.groupValues?.get(1) ?: it.name }
        val coherent = !unsafe && sorted.all { Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) && it.length() > 0 } && when (sorted.size) {
            0 -> true
            1 -> prefs.secondName == null && sourceName.matchEntire(sorted[0].name)?.groupValues?.get(1).let {
                it == null || it == "0"
            }
            2 -> {
                val a = sourceName.matchEntire(sorted[0].name); val b = sourceName.matchEntire(sorted[1].name)
                a != null && b != null && a.groupValues[1] == "0" && b.groupValues[1] == "1" && a.groupValues[2] == b.groupValues[2]
            }
            else -> false
        }
        if (!coherent) return SourceDraftStore.Restored(empty, "Прежний выбор неоднозначен. Выберите видео заново; файлы сохранены.")
        if (sorted.isEmpty()) return SourceDraftStore.Restored(empty, null)
        val entries = sorted.mapIndexed { index, file ->
            Entry(file.name, (if (index == 0) prefs.firstName else prefs.secondName) ?: file.name, file.length())
        }
        return SourceDraftStore.Restored(Snapshot(UUID.randomUUID(), Origin.LEGACY, entries), null)
    }
    fun collect(store: SourceDraftStore, current: Snapshot, previouslyOwned: Set<String>): List<String> {
        val selected = current.entries.map { it.fileName }.toSet()
        val listed = store.checkedRoot().listFiles() ?: throw IOException("Не удалось прочитать каталог исходников")
        val failures = mutableListOf<String>()
        listed.forEach { file ->
            if (file.name == "current.json" || file.name in selected) return@forEach
            val staging = stagingName.matches(file.name)
            val owned = staging || sourceName.matches(file.name) || temporaryName.matches(file.name) ||
                (file.name in previouslyOwned && SourceDraftManifest.directName(file.name))
            if (!owned) return@forEach
            try {
                store.checkSafe(file)
                if (staging && Files.isDirectory(file.toPath(), NOFOLLOW_LINKS)) {
                    // No recursive deletion: unexpected nested entries remain for manual cleanup.
                    val children = file.listFiles() ?: throw IOException("Не удалось прочитать staging")
                    children.forEach { child ->
                        store.checkSafe(child)
                        if (!stagedFile.matches(child.name) || !Files.isRegularFile(child.toPath(), NOFOLLOW_LINKS))
                            throw IOException("Неизвестный файл staging: ${child.name}")
                    }
                    children.forEach { Files.delete(it.toPath()) }
                    Files.delete(file.toPath())
                } else if (Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) Files.delete(file.toPath())
                else throw IOException("Неожиданный тип остатка ${file.name}")
            } catch (failure: Exception) { failures += "${file.name}: ${failure.message}" }
        }
        return failures
    }
}
