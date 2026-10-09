package com.example.autoedit

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/** Capture originals are durable user drafts, outside the render scratch/cache cleanup. */
internal class CaptureSessionStore(filesDir: File) {
    private val root = File(filesDir, "capture-sessions").apply { mkdirs() }
    enum class Status { PREPARED, RECORDING, FINALIZING, READY, INTERRUPTED, FAILED }
    data class Session(
        val id: String, val createdMs: Long, val styleId: String, val scriptVersion: Int,
        val mode: CaptureMode, val requestedTakes: Int, val status: Status = Status.PREPARED,
        val durationMs: Long = 0, val bytes: Long = 0, val frontCamera: Boolean = true,
        val rotation: Int = 0, val recordStartedNs: Long? = null,
        val selectedTake: Int? = null, val error: String? = null,
        val resultNames: List<String> = emptyList(),
        val resultTakes: Map<String, Int> = emptyMap(),
        val recommendedTake: Int? = null,
        val stopReason: String? = null
    ) {
        init {
            require(ID.matches(id) && createdMs > 0 && scriptVersion > 0)
            require(CaptureScript.forStyle(styleId) != null)
            require(requestedTakes in 1..CaptureScript.MAX_TAKES)
            require(durationMs >= 0 && bytes >= 0)
            require(selectedTake == null || selectedTake in 1..requestedTakes)
            require(recordStartedNs == null || recordStartedNs >= 0)
            require(rotation in 0..3)
            require(mode != CaptureMode.MUSIC || requestedTakes == 1)
            require(status != Status.READY || durationMs > 0 && bytes > 0)
            require(resultNames.all { it.isNotBlank() && File(it).name == it && '/' !in it && '\\' !in it && '|' !in it })
            require(resultTakes.keys.all { it in resultNames } && resultTakes.values.all { it in 1..requestedTakes })
            require(recommendedTake == null || recommendedTake in resultTakes.values)
        }
    }
    fun create(script: CaptureScript, mode: CaptureMode, takes: Int, front: Boolean, rotation: Int): Session =
        Session(UUID.randomUUID().toString(), System.currentTimeMillis(), script.styleId, script.version,
            mode, takes, frontCamera = front, rotation = rotation).also(::save)

    fun directory(id: String): File {
        require(ID.matches(id)) { "Invalid capture session" }
        return File(root, id)
    }
    fun recording(id: String) = File(directory(id), "recording.mp4")
    fun staging(id: String) = File(directory(id), "recording.pending.mp4")
    fun events(id: String) = File(directory(id), "timeline.tsv")

    @Synchronized fun save(session: Session) {
        val directory = directory(session.id).apply { check(mkdirs() || isDirectory) }
        val properties = Properties().apply {
            setProperty("schema", "1")
            setProperty("id", session.id)
            setProperty("createdMs", session.createdMs.toString())
            setProperty("styleId", session.styleId)
            setProperty("scriptVersion", session.scriptVersion.toString())
            setProperty("mode", session.mode.name)
            setProperty("requestedTakes", session.requestedTakes.toString())
            setProperty("status", session.status.name)
            setProperty("durationMs", session.durationMs.toString())
            setProperty("bytes", session.bytes.toString())
            setProperty("frontCamera", session.frontCamera.toString())
            setProperty("rotation", session.rotation.toString())
            session.recordStartedNs?.let { setProperty("recordStartedNs", it.toString()) }
            // A prepared choice is provisional. Only an associated published MP4 can become
            // the durable selection, including when an older caller saves this session.
            session.resultNames.lastOrNull()?.let(session.resultTakes::get)
                ?.let { setProperty("selectedTake", it.toString()) }
            session.recommendedTake?.let { setProperty("recommendedTake", it.toString()) }
            session.error?.let { setProperty("error", it) }
            session.stopReason?.let { setProperty("stopReason", it) }
            setProperty("results", session.resultNames.joinToString("|"))
            session.resultNames.forEachIndexed { index, name ->
                session.resultTakes[name]?.let { setProperty("resultTake.$index", it.toString()) }
            }
        }
        val pending = File(directory, "session.new")
        FileOutputStream(pending).use { out -> properties.store(out, "Veykad capture session"); out.fd.sync() }
        Files.move(pending.toPath(), File(directory, "session.properties").toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Synchronized fun load(id: String): Session? = runCatching {
        val file = File(directory(id), "session.properties")
        if (!file.isFile) return null
        val p = Properties().apply { file.inputStream().use(::load) }
        require(p.getProperty("schema") == "1" && p.getProperty("id") == id)
        val resultNames = p.getProperty("results", "").split('|').filter { it.isNotBlank() }
        val requestedTakes = p.getProperty("requestedTakes").toInt()
        // Old manifests have only `results` and perhaps a provisional `selectedTake`.
        // Keep their result links, but do not invent a take or recommendation for them.
        val resultTakes = resultNames.mapIndexedNotNull { index, name ->
            p.getProperty("resultTake.$index")?.toIntOrNull()?.takeIf { it in 1..requestedTakes }
                ?.let { name to it }
        }.toMap()
        val selectedTake = resultNames.lastOrNull()?.let(resultTakes::get)
        val recommendedTake = p.getProperty("recommendedTake")?.toIntOrNull()
            ?.takeIf { it in resultTakes.values }
        Session(id, p.getProperty("createdMs").toLong(), p.getProperty("styleId"),
            p.getProperty("scriptVersion").toInt(), CaptureMode.valueOf(p.getProperty("mode")),
            requestedTakes, Status.valueOf(p.getProperty("status")),
            p.getProperty("durationMs").toLong(), p.getProperty("bytes").toLong(),
            p.getProperty("frontCamera").toBooleanStrict(), p.getProperty("rotation").toInt(),
            p.getProperty("recordStartedNs")?.toLong(), selectedTake, p.getProperty("error"),
            resultNames, resultTakes, recommendedTake, p.getProperty("stopReason"))
    }.getOrNull()

    fun list(): List<Session> = root.listFiles().orEmpty().filter { it.isDirectory && ID.matches(it.name) }
        .mapNotNull { load(it.name) }
        .filterNot { session ->
            // A process may die after writing the manifest but before CameraX creates a file.
            // Keep that metadata for diagnosis, without presenting an empty phantom capture.
            session.status == Status.PREPARED && staging(session.id).length() == 0L &&
                recording(session.id).length() == 0L && session.resultNames.isEmpty()
        }.sortedByDescending { it.createdMs }

    @Synchronized fun attachResult(id: String, fileName: String, takeOrdinal: Int,
        automaticallyRecommended: Boolean): Session {
        val session = requireNotNull(load(id))
        require(takeOrdinal in 1..session.requestedTakes)
        require(fileName.isNotBlank() && File(fileName).name == fileName && '/' !in fileName &&
            '\\' !in fileName && '|' !in fileName)
        session.resultTakes[fileName]?.let { linked ->
            require(linked == takeOrdinal) { "Result already belongs to another take" }
            return session // Idempotent retry must not change the latest selected take.
        }
        return session.copy(resultNames = (session.resultNames + fileName).distinct(),
            resultTakes = session.resultTakes + (fileName to takeOrdinal), selectedTake = takeOrdinal,
            recommendedTake = if (automaticallyRecommended) takeOrdinal else session.recommendedTake)
            .also(::save)
    }
    fun forResult(fileName: String): Session? = list().firstOrNull { fileName in it.resultNames }

    /** Call only with live IDs supplied by the process owner, never infer liveness from a file. */
    @Synchronized fun recover(activeIds: Set<String>): List<Session> = list().map { session ->
        if (session.id !in activeIds && (session.status in setOf(Status.RECORDING, Status.FINALIZING) ||
            session.status == Status.PREPARED && staging(session.id).length() > 0)) {
            session.copy(status = Status.INTERRUPTED, error = "Запись прервана. Сохранённые данные доступны для проверки.")
                .also(::save)
        } else session
    }

    @Synchronized fun markReady(session: Session, durationMs: Long): Session {
        require(session.status == Status.FINALIZING && durationMs > 0)
        val pending = staging(session.id)
        require(pending.isFile && pending.length() > 0)
        Files.move(pending.toPath(), recording(session.id).toPath(), StandardCopyOption.ATOMIC_MOVE)
        return session.copy(status = Status.READY, durationMs = durationMs,
            bytes = recording(session.id).length(), error = null).also(::save)
    }

    /** Caller has validated this completed container after an interrupted finalization. */
    @Synchronized fun acceptRecovered(session: Session, durationMs: Long): Session {
        require(session.status in setOf(Status.INTERRUPTED, Status.FAILED) && durationMs > 0)
        val target = recording(session.id)
        if (!target.exists()) return markReady(session.copy(status = Status.FINALIZING), durationMs)
        require(target.isFile && target.length() > 0)
        return session.copy(status = Status.READY, durationMs = durationMs, bytes = target.length(), error = null).also(::save)
    }

    companion object { private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") }
}
