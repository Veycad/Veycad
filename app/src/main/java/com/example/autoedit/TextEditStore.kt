package com.veycad.app

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/** Versioned, atomically replaced draft. Source identity also prevents stale drafts after replacement. */
class TextEditStore(private val directory: File) {
    private fun target(path: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(File(path).canonicalPath.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$key.properties")
    }
    private fun identity(path: String): String {
        val source = File(path)
        return "${source.canonicalPath}|${source.length()}|${source.lastModified()}"
    }
    @Synchronized fun resultPath(sourcePath: String): String? = runCatching {
        val p = Properties().apply { target(sourcePath).inputStream().use { load(it) } }
        if (p.getProperty("identity") != identity(sourcePath)) return null
        p.getProperty("result")?.takeIf { File(it).isFile }
    }.getOrNull()
    @Synchronized fun save(project: TextEditProject, resultPath: String? = null) {
        directory.mkdirs()
        val p = Properties()
        fun put(key: String, value: Any) { p.setProperty(key, value.toString()) }
        fun style(prefix: String, s: TextStyle) {
            put("$prefix.position", s.position); put("$prefix.font", s.font)
            put("$prefix.size", s.sizeRatio); put("$prefix.color", s.color)
            put("$prefix.plate", s.darkPlate); put("$prefix.animation", s.animation)
        }
        put("version", 1); put("identity", identity(project.sourcePath)); put("source", project.sourcePath)
        put("duration", project.durationUs); put("width", project.width); put("height", project.height)
        put("origin", project.sourceOriginUs)
        put("edited", project.captionsEdited); put("language", project.language)
        (resultPath ?: this.resultPath(project.sourcePath))?.let { put("result", it) }
        style("captionStyle", project.captionStyle)
        put("layers", project.layers.size); put("captions", project.captions.size)
        project.layers.forEachIndexed { i, l ->
            put("l.$i.id", l.id); put("l.$i.text", l.text); put("l.$i.start", l.startUs); put("l.$i.end", l.endUs)
            style("l.$i.style", l.style)
        }
        project.captions.forEachIndexed { i, c ->
            put("c.$i.id", c.id); put("c.$i.text", c.text); put("c.$i.start", c.startUs); put("c.$i.end", c.endUs)
        }
        val file = target(project.sourcePath)
        val partial = File(file.path + ".partial")
        java.io.FileOutputStream(partial).use { stream -> p.store(stream, "Veycad text draft"); stream.fd.sync() }
        try {
            Files.move(partial.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
    @Synchronized fun load(sourcePath: String): TextEditProject? = runCatching {
        val p = Properties().apply { target(sourcePath).inputStream().use { load(it) } }
        fun get(k: String) = p.getProperty(k) ?: error("Missing $k")
        if (get("version") != "1" || get("identity") != identity(sourcePath)) return null
        fun style(k: String) = TextStyle(TextPosition.valueOf(get("$k.position")), TextFont.valueOf(get("$k.font")),
            get("$k.size").toFloat(), get("$k.color").toInt(), get("$k.plate").toBoolean(), TextAnimation.valueOf(get("$k.animation")))
        TextEditProject(sourcePath, get("duration").toLong(), get("width").toInt(), get("height").toInt(),
            List(get("layers").toInt().also { require(it in 0..1000) }) { i ->
                TextLayer(get("l.$i.id"), get("l.$i.text"), get("l.$i.start").toLong(), get("l.$i.end").toLong(), style("l.$i.style"))
            }, List(get("captions").toInt().also { require(it in 0..100000) }) { i ->
                CaptionCue(get("c.$i.id"), get("c.$i.text"), get("c.$i.start").toLong(), get("c.$i.end").toLong())
            }, style("captionStyle"), get("edited").toBoolean(), get("language"),p.getProperty("origin","0").toLong())
    }.getOrNull()
}
