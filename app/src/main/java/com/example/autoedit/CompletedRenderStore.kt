package com.example.autoedit

import java.io.File
import java.util.Properties

/** Published results are independent of job scratch files and survive navigation/process death. */
internal object CompletedRenderStore {
    data class Entry(val file: File, val summary: String, val saved: Boolean)

    fun publish(filesDir: File, source: File, summary: String): Entry {
        require(source.isFile && source.length() > 0L) { "Готовый монтаж отсутствует или пуст" }
        val directory = File(filesDir, "completed-renders").apply { mkdirs() }
        val target = File(directory, source.name)
        val temporary = File(directory, "${source.name}.partial")
        try {
            source.copyTo(temporary, overwrite = true)
            check(temporary.renameTo(target)) { "Не удалось сохранить готовый монтаж" }
            return Entry(target, summary, false).also { writeMetadata(it) }
        } finally {
            temporary.delete()
        }
    }

    fun latest(filesDir: File): Entry? = File(filesDir, "completed-renders").listFiles()
        ?.filter { it.extension == "mp4" && it.length() > 0L }
        ?.sortedByDescending(File::lastModified)
        ?.firstNotNullOfOrNull { file ->
            runCatching {
                val metadata = File(file.parentFile, "${file.name}.properties")
                val properties = Properties().apply {
                    if (metadata.isFile) runCatching { metadata.inputStream().use(::load) }
                }
                Entry(file, properties.getProperty("summary", "Готовый монтаж"),
                    properties.getProperty("saved") == "true")
            }.getOrNull()
        }

    fun markSaved(entry: Entry) = writeMetadata(entry.copy(saved = true))

    private fun writeMetadata(entry: Entry) {
        val target = File(entry.file.parentFile, "${entry.file.name}.properties")
        val temporary = File(target.path + ".partial")
        try {
            Properties().apply {
                setProperty("summary", entry.summary)
                setProperty("saved", entry.saved.toString())
            }.also { properties -> temporary.outputStream().use { properties.store(it, null) } }
            check(temporary.renameTo(target)) { "Не удалось записать состояние монтажа" }
        } finally {
            temporary.delete()
        }
    }
}
