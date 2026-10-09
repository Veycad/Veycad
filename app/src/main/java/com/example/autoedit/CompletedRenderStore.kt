package com.example.autoedit

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Published results are independent of job scratch files and survive navigation/process death. */
internal object CompletedRenderStore {
    data class CaptureLink(val sessionId: String, val takeOrdinal: Int, val recommended: Boolean)
    data class Entry(val file: File, val summary: String, val saved: Boolean,
        val captureLink: CaptureLink? = null)

    fun publish(filesDir: File, source: File, summary: String,
        captureLink: CaptureLink? = null): Entry {
        require(source.isFile && source.length() > 0L) { "Готовый монтаж отсутствует или пуст" }
        val directory = File(filesDir, "completed-renders").apply { mkdirs() }
        val target = File(directory, source.name)
        val temporary = File(directory, "${source.name}.partial")
        check(!target.exists()) { "Монтаж с таким именем уже сохранён" }
        try {
            source.copyTo(temporary, overwrite = true)
            // The metadata must be durable before the MP4 becomes visible. A crash after the
            // rename can then restore the capture link without losing the published result.
            val entry = Entry(target, summary, false, captureLink)
            writeMetadata(entry)
            check(temporary.renameTo(target)) { "Не удалось сохранить готовый монтаж" }
            return entry
        } finally {
            temporary.delete()
        }
    }

    fun latest(filesDir: File): Entry? = list(filesDir).firstOrNull()

    fun list(filesDir: File): List<Entry> = File(filesDir, "completed-renders").listFiles()
        ?.filter { it.isFile && it.extension == "mp4" && it.length() > 0L }
        ?.sortedByDescending(File::lastModified)
        ?.mapNotNull { file ->
            runCatching {
                val metadata = File(file.parentFile, "${file.name}.properties")
                val properties = Properties().apply {
                    if (metadata.isFile) runCatching { metadata.inputStream().use(::load) }
                }
                val link = runCatching {
                    val sessionId = properties.getProperty("captureSessionId") ?: return@runCatching null
                    CaptureLink(sessionId, properties.getProperty("captureTakeOrdinal").toInt(),
                        properties.getProperty("captureRecommended") == "true")
                }.getOrNull()
                Entry(file, properties.getProperty("summary", "Готовый монтаж"),
                    properties.getProperty("saved") == "true", link)
            }.getOrNull()
        }.orEmpty()

    fun markSaved(entry: Entry) = writeMetadata(entry.copy(saved = true))

    /** Idempotent repair after a process dies between publication and capture manifest update. */
    fun recoverCaptureLinks(filesDir: File): List<String> {
        val captureStore = CaptureSessionStore(filesDir)
        return list(filesDir).asReversed().mapNotNull { entry ->
            val link = entry.captureLink ?: return@mapNotNull null
            runCatching { captureStore.attachResult(link.sessionId, entry.file.name,
                link.takeOrdinal, link.recommended) }
                .exceptionOrNull()?.let { entry.file.name }
        }
    }

    private fun writeMetadata(entry: Entry) {
        val target = File(entry.file.parentFile, "${entry.file.name}.properties")
        val temporary = File(target.path + ".partial")
        try {
            Properties().apply {
                setProperty("summary", entry.summary)
                setProperty("saved", entry.saved.toString())
                entry.captureLink?.let { link ->
                    setProperty("captureSessionId", link.sessionId)
                    setProperty("captureTakeOrdinal", link.takeOrdinal.toString())
                    setProperty("captureRecommended", link.recommended.toString())
                }
            }.also { properties -> temporary.outputStream().use { properties.store(it, null) } }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
    }
}
