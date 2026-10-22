package com.veycad.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties

/** Only the atomic pointer commits an import; loose media never replaces a committed selection. */
internal class SourceDraftStore(filesDir: File) {
    data class Selection(val files: List<File>, val names: List<String>)
    private val directory = File(filesDir, "source-draft")
    private val manifest = File(filesDir, "source-draft.properties")

    fun initialize(legacyNames: List<String>) {
        if (!manifest.exists()) {
            val legacy = legacySelection(legacyNames)
            publish(legacy?.files.orEmpty(), legacy?.names.orEmpty())
        }
    }

    fun load(legacyNames: List<String> = emptyList()): Selection? {
        if (!manifest.exists()) return legacySelection(legacyNames)
        val properties = Properties().apply {
            manifest.inputStream().use { input -> load(input.readBytesBounded(64 * 1024).inputStream()) }
        }
        require(properties.getProperty("version") == "1") { "Не удалось прочитать выбор исходников" }
        val count = properties.getProperty("count").toInt()
        require(count in 0..2)
        if (count == 0) return null
        val files = List(count) { index ->
            ownedChild(directory, requireNotNull(properties.getProperty("file.$index"))).also { file ->
                require(file.isFile && file.length() > 0 &&
                    file.length() == properties.getProperty("size.$index").toLong()) {
                    "Сохранённый исходник изменён или недоступен. Выбери видео заново."
                }
            }
        }
        require(files.distinct().size == count)
        return Selection(files, List(count) { requireNotNull(properties.getProperty("name.$it")) })
    }

    fun publish(files: List<File>, names: List<String>) {
        require(files.size in 0..2 && names.size == files.size)
        require(files.map { it.canonicalPath }.distinct().size == files.size)
        val properties = Properties().apply {
            setProperty("version", "1")
            setProperty("count", files.size.toString())
            files.forEachIndexed { index, file ->
                require(file.canonicalFile == ownedChild(directory, file.name).canonicalFile &&
                    file.isFile && file.length() > 0)
                setProperty("file.$index", file.name)
                setProperty("size.$index", file.length().toString())
                setProperty("name.$index", names[index])
            }
        }
        val bytes = ByteArrayOutputStream().also { properties.store(it, null) }.toByteArray()
        require(bytes.size <= 64 * 1024) { "Слишком длинное имя видео" }
        atomicWrite(manifest, bytes)
    }

    fun clear() {
        publish(emptyList(), emptyList())
        directory.deleteRecursively()
    }

    private fun legacySelection(names: List<String>): Selection? {
        val files = directory.listFiles()?.filter { it.isFile && it.extension == "mp4" }
            ?.sortedBy(File::getName).orEmpty()
        if (files.isEmpty() || files.size > 2 || files.any { it.length() == 0L }) return null
        val pattern = Regex("source-([01])-([a-f0-9-]{36})\\.mp4")
        val matches = files.map { pattern.matchEntire(it.name) ?: return null }
        if (matches.map { it.groupValues[2] }.distinct().size != 1 ||
            matches.map { it.groupValues[1].toInt() } != files.indices.toList()) return null
        return Selection(files, files.mapIndexed { index, file -> names.getOrNull(index) ?: file.name })
    }
}
