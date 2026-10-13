package com.veycad.app

import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.io.Writer
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Collections
import java.util.Locale

/**
 * Bounded raw evidence for ONE immutable benchmark group. No operation authorization, media
 * clocks, percentile calculation or acceptance policy lives here. Call at the request boundary;
 * supply later work/ready time explicitly. FIRST_FRAME start is draft/Surface ready time.
 *
 * Synchronous operations share a monitor. Writing holds it until atomic publication, so overflow
 * cannot race a valid-looking report. Do not call writeReport on a presentation hot path.
 * Counts and 4096 UTF-16-unit string limits bound retention; overflow poisons publication without
 * clearing retained evidence. Invalid caller arguments throw before changing any records.
 */
class PreviewMetrics(context: Context, private val limits: Limits = Limits(),
    private val clockNs: () -> Long = SystemClock::elapsedRealtimeNanos) {
    enum class Event { FIRST_FRAME, CACHED_SCRUB, EXACT_SEEK, FORMAT_CHANGE, PLAY_START }
    enum class Status { COMPLETED, FAILED, MISSING, INCOMPLETE, OBSOLETE, SUPERSEDED }
    enum class Temperature { COLD, WARM }
    enum class FrameStatus { SHOWN, DROPPED, MISSING }
    data class Metadata(val appVersion: String, val commit: String, val draftProject: String, val draftRevision: Long,
        val device: String, val android: String, val abi: String, val codec: String)
    data class Scenario(val style: String, val aspect: String, val mode: String, val inputClass: String, val exportFps: Int)
    data class Source(val sha256: String, val codec: String, val width: Int, val height: Int, val fps: Int,
        val dynamicRange: String, val gopUs: Long, val rotation: Int, val sarNum: Int, val sarDen: Int)
    data class Context(val id: String, val metadata: Metadata, val scenario: Scenario, val sources: List<Source>)
    data class Limits(val events: Int = 8192, val playbackPasses: Int = 64, val frames: Int = 108000,
        val scrubSessions: Int = 128, val memorySamples: Int = 36000)
    data class Terminal(val status: Status, val reason: String? = null, val shownOutputUs: Long? = null,
        val completionGeneration: PreviewGeneration? = null, val presented: Boolean = false,
        val canonicalReady: Boolean = false, val exact: Boolean = false)
    data class Measurement(val token: Long, val event: Event, val requestNs: Long, val startNs: Long,
        val endNs: Long?, val requestedOutputUs: Long, val generation: PreviewGeneration,
        val terminal: Terminal, val temperature: Temperature?, val prepareNs: Long?)
    data class Frame(val index: Long, val status: FrameStatus, val shownOutputUs: Long? = null, val audioUs: Long? = null)
    data class Playback(val id: String, val observedDurationUs: Long, val recipeDurationUs: Long,
        val completed: Boolean, val frames: List<Frame>)
    data class Scrubbing(val id: String, val startNs: Long, val endNs: Long, val observedDurationUs: Long, val completed: Boolean)
    data class AppBytes(val cache: Long? = null, val pages: Long? = null, val header: Long? = null,
        val track: Long? = null, val scratch: Long? = null, val thumbnail: Long? = null, val fbo: Long? = null)
    data class ProcessBytes(val rss: Long? = null, val java: Long? = null, val native: Long? = null,
        val gpu: Long? = null, val codec: Long? = null)
    data class Memory(val atNs: Long, val appBytes: AppBytes = AppBytes(), val processBytes: ProcessBytes = ProcessBytes())
    data class Snapshot(val context: Context, val events: List<Measurement>, val playback: List<Playback>,
        val scrubbing: List<Scrubbing>, val memory: List<Memory>, val writeFailure: String?)

    private val context = context.also {
        require(it.sources.size in 1..2) { "One or two source profiles required" }
    }.copy(sources = frozen(context.sources))
    private val events = linkedMapOf<Long, Measurement>()
    private val ended = hashSetOf<Long>()
    private val playback = linkedMapOf<String, Playback>()
    private val scrubbing = linkedMapOf<String, Scrubbing>()
    private val memory = linkedMapOf<Long, Memory>()
    private var nextToken = 1L
    private var frameCount = 0
    private var writeFailure: String? = null

    init {
        listOf(limits.events, limits.playbackPasses, limits.frames, limits.scrubSessions, limits.memorySamples)
            .forEach { require(it in 1..1_000_000) { "Limits must be in 1..1000000" } }
        validateContext(this.context)
    }

    /** Measurement token belongs to this collector, independently of operation generations. */
    @Synchronized
    fun begin(event: Event, generation: PreviewGeneration, outputTimeUs: Long, requestNs: Long = clockNs(),
        startNs: Long = requestNs, temperature: Temperature? = null, prepareNs: Long? = null): Long {
        nonnegative(outputTimeUs, requestNs, startNs, prepareNs)
        require(startNs >= requestNs) { "Request must precede work/ready time" }
        require(if (event == Event.FIRST_FRAME) temperature != null else temperature == null && prepareNs == null) {
            "Temperature and preparation belong only to FIRST_FRAME (temperature required)"
        }
        capacity(events.size < limits.events, "event limit exceeded")
        capacity(nextToken < Long.MAX_VALUE, "measurement token exhausted")
        val token = nextToken
        events[token] = Measurement(token, event, requestNs, startNs, null, outputTimeUs, generation,
            Terminal(Status.INCOMPLETE, "Measurement has no terminal evidence"), temperature, prepareNs)
        nextToken++
        return token
    }

    /**
     * Keeps actual claimed completion unchanged, including unsafe stale/nonexact presentations.
     * The reporter diagnoses them. null end/completion clocks mean unknown, never invented zero.
     */
    @Synchronized
    fun end(token: Long, terminal: Terminal, endNs: Long? = clockNs()) {
        val request = requireNotNull(events[token]) { "Unknown measurement token" }
        require(token !in ended) { "Measurement already has terminal evidence" }
        nonnegative(endNs, terminal.shownOutputUs)
        require(endNs == null || endNs >= request.startNs) { "Terminal time precedes work/ready time" }
        if (terminal.status != Status.COMPLETED) text(requireNotNull(terminal.reason) { "Terminal reason required" })
        else terminal.reason?.let(::text)
        events[token] = request.copy(endNs = endNs, terminal = terminal)
        ended.add(token)
    }

    /** One observation per target-grid slot; pass durations and clocks are caller-observed. */
    @Synchronized
    fun recordPlayback(pass: Playback) {
        text(pass.id)
        require(pass.id !in playback) { "Duplicate playback ID" }
        require(pass.observedDurationUs > 0 && pass.recipeDurationUs > 0) { "Playback durations must be positive" }
        capacity(playback.size < limits.playbackPasses, "playback pass limit exceeded")
        capacity(pass.frames.size <= limits.frames - frameCount, "playback frame limit exceeded")
        val frames = frozen(pass.frames)
        // Quotient/remainder keeps the 30FPS grid representable even for Long.MAX_VALUE durations.
        val expected = pass.observedDurationUs / 1_000_000 * 30 +
            (pass.observedDurationUs % 1_000_000 * 30 + 999_999) / 1_000_000
        val indices = hashSetOf<Long>()
        val shownPts = hashSetOf<Long>()
        frames.forEach { frame ->
            nonnegative(frame.index, frame.shownOutputUs, frame.audioUs)
            require(frame.index < expected && indices.add(frame.index)) { "Outside grid or duplicate frame index" }
            require(frame.status == FrameStatus.SHOWN || frame.shownOutputUs == null && frame.audioUs == null) {
                "Non-shown slot cannot carry presentation clocks"
            }
            if (frame.status == FrameStatus.SHOWN && frame.shownOutputUs != null)
                require(shownPts.add(frame.shownOutputUs)) { "Duplicate shown output PTS" }
        }
        playback[pass.id] = pass.copy(frames = frames)
        frameCount += frames.size
    }

    @Synchronized
    fun recordScrubbing(session: Scrubbing) {
        text(session.id)
        require(session.id !in scrubbing) { "Duplicate scrub ID" }
        nonnegative(session.startNs, session.endNs, session.observedDurationUs)
        require(session.endNs >= session.startNs &&
            session.observedDurationUs <= (session.endNs - session.startNs) / 1000) { "Scrub duration outside interval" }
        require(scrubbing.values.none { it.endNs > session.startNs && session.endNs > it.startNs }) { "Overlapping scrub intervals" }
        capacity(scrubbing.size < limits.scrubSessions, "scrub session limit exceeded")
        scrubbing[session.id] = session
    }

    /** Concurrent, disjoint app buckets; overlapping process buckets are never added to them. */
    @Synchronized
    fun recordMemory(sample: Memory) {
        nonnegative(sample.atNs, sample.appBytes.cache, sample.appBytes.pages, sample.appBytes.header,
            sample.appBytes.track, sample.appBytes.scratch, sample.appBytes.thumbnail, sample.appBytes.fbo,
            sample.processBytes.rss, sample.processBytes.java, sample.processBytes.native,
            sample.processBytes.gpu, sample.processBytes.codec)
        require(sample.atNs !in memory) { "Duplicate memory timestamp" }
        capacity(memory.size < limits.memorySamples, "memory sample limit exceeded")
        memory[sample.atNs] = sample
    }

    /** Bounded shallow copies; immutable records and frozen pass frames share no mutable caller data. */
    @Synchronized
    fun snapshot() = Snapshot(context, frozen(events.values), frozen(playback.values),
        frozen(scrubbing.values), frozen(memory.values), writeFailure)

    /** Same-directory owned temp, flush/sync, then atomic replace; no destructive fallback. */
    @Synchronized
    fun writeReport(file: File) {
        check(writeFailure == null) { "Raw evidence cannot be published: $writeFailure" }
        val destination = file.toPath().toAbsolutePath()
        val parent = destination.parent
        if (!Files.isDirectory(parent) || Files.isDirectory(destination)) throw IOException("Report destination is not a file in an existing directory")
        val temporary = Files.createTempFile(parent, ".preview-metrics-", ".tmp")
        try {
            temporary.toFile().outputStream().use { stream ->
                val writer = stream.bufferedWriter(Charsets.UTF_8)
                writeJson(Json(writer))
                writer.flush()
                stream.fd.sync()
            }
            Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun capacity(available: Boolean, reason: String) {
        if (!available) {
            if (writeFailure == null) writeFailure = reason
            throw IllegalStateException(reason)
        }
    }

    private fun validateContext(value: Context) {
        text(value.id)
        with(value.metadata) {
            listOf(appVersion, commit, draftProject, device, android, abi, codec).forEach(::text)
            require(commit.matches(Regex("[0-9a-f]{40}"))) { "Exact lowercase commit SHA required" }
            nonnegative(draftRevision)
        }
        with(value.scenario) {
            listOf(style, aspect, mode, inputClass).forEach(::text)
            require(exportFps == 30 || exportFps == 60) { "Export FPS must be 30 or 60" }
        }
        require(value.sources.size in 1..2) { "One or two source profiles required" }
        value.sources.forEach { source ->
            with(source) {
                listOf(sha256, codec, dynamicRange).forEach(::text)
                require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Lowercase source SHA256 required" }
                require(width > 0 && height > 0 && fps > 0 && gopUs > 0 && sarNum > 0 && sarDen > 0) { "Positive source characteristics required" }
                require(rotation in listOf(0, 90, 180, 270)) { "Invalid source rotation" }
            }
        }
    }

    private fun writeJson(json: Json) = with(json) {
        obj {
            number("schema_version", 1)
            array("groups", listOf(context)) { group ->
                string("id", group.id)
                objectField("metadata") {
                    with(group.metadata) {
                        string("app_version", appVersion); string("commit", commit); string("draft_project", draftProject)
                        number("draft_revision", draftRevision); string("device", device); string("android", android)
                        string("abi", abi); string("codec", codec)
                    }
                }
                objectField("scenario") {
                    with(group.scenario) {
                        string("style", style); string("aspect", aspect); string("mode", mode)
                        string("input_class", inputClass); number("export_fps", exportFps.toLong())
                    }
                }
                array("sources", group.sources) { source ->
                    with(source) {
                        string("sha256", sha256); string("codec", codec); number("width", width.toLong())
                        number("height", height.toLong()); number("fps", fps.toLong()); string("dynamic_range", dynamicRange)
                        number("gop_us", gopUs); number("rotation", rotation.toLong())
                        number("sar_num", sarNum.toLong()); number("sar_den", sarDen.toLong())
                    }
                }
                array("events", events.values) { event ->
                    with(event) {
                        string("id", token.toString()); string("event", this.event.name)
                        number("request_ns", requestNs); number("start_ns", startNs); number("end_ns", endNs)
                        number("requested_output_us", requestedOutputUs); number("shown_output_us", terminal.shownOutputUs)
                        generation("generation", generation); generation("completion_generation", terminal.completionGeneration)
                        string("status", terminal.status.name.lowercase(Locale.ROOT)); string("reason", terminal.reason)
                        bool("presented", terminal.presented); bool("canonical_ready", terminal.canonicalReady); bool("exact", terminal.exact)
                        string("temperature", temperature?.name?.lowercase(Locale.ROOT)); number("prepare_ns", prepareNs)
                    }
                }
                array("playback", playback.values) { pass ->
                    string("id", pass.id); number("observed_duration_us", pass.observedDurationUs)
                    number("recipe_duration_us", pass.recipeDurationUs); bool("completed", pass.completed)
                    array("frames", pass.frames) { frame ->
                        number("index", frame.index); string("status", frame.status.name.lowercase(Locale.ROOT))
                        number("shown_output_us", frame.shownOutputUs); number("audio_us", frame.audioUs)
                    }
                }
                array("scrubbing", scrubbing.values) { session ->
                    string("id", session.id); number("start_ns", session.startNs); number("end_ns", session.endNs)
                    number("observed_duration_us", session.observedDurationUs); bool("completed", session.completed)
                }
                array("memory", memory.values) { sample ->
                    number("at_ns", sample.atNs)
                    objectField("app_bytes") {
                        with(sample.appBytes) {
                            number("cache", cache); number("pages", pages); number("header", header); number("track", track)
                            number("scratch", scratch); number("thumbnail", thumbnail); number("fbo", fbo)
                        }
                    }
                    objectField("process_bytes") {
                        with(sample.processBytes) {
                            number("rss", rss); number("java", java); number("native", native); number("gpu", gpu); number("codec", codec)
                        }
                    }
                }
            }
        }
    }

    /** Private fixed-model streaming primitives; no arbitrary maps or whole-document JSON tree. */
    private class Json(private val out: Writer) {
        private val first = java.util.ArrayDeque<Boolean>()
        fun obj(body: Json.() -> Unit) {
            out.write("{"); first.addLast(true); body(); first.removeLast(); out.write("}")
        }
        private fun key(name: String) {
            if (!first.removeLast()) out.write(",")
            first.addLast(false)
            quoted(name); out.write(":")
        }
        fun string(key: String, value: String?) { key(key); if (value == null) out.write("null") else quoted(value) }
        fun number(key: String, value: Long?) { key(key); out.write(value?.toString() ?: "null") }
        fun bool(key: String, value: Boolean) { key(key); out.write(value.toString()) }
        fun objectField(key: String, body: Json.() -> Unit) { key(key); obj(body) }
        fun generation(key: String, value: PreviewGeneration?) {
            if (value == null) { key(key); out.write("null") }
            else objectField(key) { number("project", value.project); number("surface", value.surface); number("seek", value.seek) }
        }
        fun <T> array(key: String, values: Collection<T>, emit: Json.(T) -> Unit) {
            key(key); out.write("[")
            var firstItem = true
            values.forEach { value ->
                if (!firstItem) out.write(",")
                firstItem = false
                obj { emit(value) }
            }
            out.write("]")
        }
        private fun quoted(value: String) {
            out.write("\"")
            value.forEach { character ->
                when (character) {
                    '"' -> out.write("\\\"")
                    '\\' -> out.write("\\\\")
                    '\b' -> out.write("\\b")
                    '\u000C' -> out.write("\\f")
                    '\n' -> out.write("\\n")
                    '\r' -> out.write("\\r")
                    '\t' -> out.write("\\t")
                    else -> if (character < ' ') {
                        out.write("\\u"); out.write(character.code.toString(16).padStart(4, '0'))
                    } else out.write(character.code)
                }
            }
            out.write("\"")
        }
    }

    companion object {
        private fun <T> frozen(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
        private fun nonnegative(vararg values: Long?) { require(values.all { it == null || it >= 0 }) { "Negative raw evidence" } }
        private fun text(value: String) {
            require(value.isNotBlank() && value.length <= 4096) { "Text must be nonblank and at most 4096 UTF-16 units" }
            var index = 0
            while (index < value.length) {
                val character = value[index++]
                if (Character.isHighSurrogate(character)) {
                    require(index < value.length && Character.isLowSurrogate(value[index++])) { "Unpaired text surrogate" }
                } else require(!Character.isLowSurrogate(character)) { "Unpaired text surrogate" }
            }
        }
    }
}
