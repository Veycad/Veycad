package com.veycad.app

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.IdentityHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Diagnostic-only, compatible with the unmodified pre-custom-music model. */
internal object FrozenVisualGraphLoader {
    const val FORMAT = "custom-music-frozen-visual-graph-v1"
    private const val MAX_JSON_BYTES = 4L * 1024 * 1024
    private const val MAX_PLANE_BYTES = 64L * 1024 * 1024

    data class Loaded(
        val graph: MontageGraph,
        val source: File,
        val origin: JSONObject,
        val frozenJsonSha256: String,
        val planesSha256: String,
        val sourceSha256: String,
        val inputSha256: String,
        val planeHashes: IdentityHashMap<FloatArray, String>,
        val planeCount: Int
    )

    fun load(directory: File): Loaded {
        val jsonFile = File(directory, "frozen-graph.json")
        require(jsonFile.isFile && jsonFile.length() in 1..MAX_JSON_BYTES)
        val root = JSONObject(jsonFile.readText(Charsets.UTF_8))
        root.fields("schema_version", "format", "origin", "files", "graph")
        require(root.i("schema_version") == 1 && root.getString("format") == FORMAT)
        val origin = root.getJSONObject("origin").apply {
            fields("head_sha", "ci_run_id", "artifact_id", "snapshot_sha256", "visual_graph_sha256",
                "native_quality_gate", "native_issues")
            require(getString("head_sha").matches(Regex("[0-9a-f]{40}")))
            require(l("ci_run_id") > 0 && l("artifact_id") > 0)
            hash("snapshot_sha256"); hash("visual_graph_sha256")
            require(!getBoolean("native_quality_gate")) { "This control must preserve failed native QA" }
            getJSONArray("native_issues").each { require(it is String) }
        }
        val files = root.getJSONObject("files").apply { fields("source", "planes") }
        val source = verifyFile(directory, files.getJSONObject("source"), "editor-source.mp4")
        val planes = verifyFile(directory, files.getJSONObject("planes"), "frozen-planes.f32")
        require(planes.length() in 0..MAX_PLANE_BYTES && planes.length() % 4L == 0L)
        val sourceHash = sha256(source)
        val planesHash = sha256(planes)
        val jsonHash = sha256(jsonFile)
        val hashes = IdentityHashMap<FloatArray, String>()
        val ranges = ArrayList<LongRange>()
        val graph = RandomAccessFile(planes, "r").use { binary ->
            Parser(binary, hashes, ranges).graph(root.getJSONObject("graph"))
        }
        var end = 0L
        ranges.sortedBy { it.first }.forEach { range ->
            require(range.first == end) { "Plane binary has a gap, overlap or repeated range" }
            end = range.last + 1L
        }
        require(end == planes.length()) { "Unused or missing plane bytes" }
        require(graph.audioTrack == null && graph.version == 2)
        require(graph.outputDurationMs == 16_000L && graph.sourceDurationMs == 16_000L)
        require(graph.clips.all { it.sourceIndex == 0 })
        val inputHash = sha256("$FORMAT\n$sourceHash\n$jsonHash\n$planesHash\n".toByteArray(Charsets.UTF_8))
        return Loaded(graph, source, origin, jsonHash, planesHash, sourceHash, inputHash, hashes, ranges.size)
    }

    private class Parser(
        val binary: RandomAccessFile,
        val hashes: IdentityHashMap<FloatArray, String>,
        val ranges: MutableList<LongRange>
    ) {
        fun graph(j: JSONObject): MontageGraph {
            j.fields("sourceDurationMs", "outputDurationMs", "profile", "clips", "audioTrack", "overlays",
                "metadata", "parameterTracks", "frameAttachments", "effectGraph", "version")
            require(j.isNull("audioTrack")) { "Only the declared audioTrack=null visual projection is supported" }
            return MontageGraph(j.l("sourceDurationMs"), j.l("outputDurationMs"),
                MontageGraph.Profile.valueOf(j.getString("profile")), j.objects("clips", ::clip), null,
                j.objects("overlays", ::overlay), metadata(j.getJSONObject("metadata")),
                j.objects("parameterTracks", ::track), timeline(j.getJSONObject("frameAttachments")),
                effects(j.getJSONObject("effectGraph")), j.i("version"))
        }

        private fun metadata(j: JSONObject): NleProjectMetadata {
            j.fields("schemaVersion", "revision", "parentRevision", "generator")
            return NleProjectMetadata(j.i("schemaVersion"), j.l("revision"), j.nl("parentRevision"),
                j.getString("generator"))
        }

        private fun clip(j: JSONObject): MontageGraph.Clip {
            j.fields("id", "sourceStartMs", "sourceEndMs", "outputDurationMs", "role", "transitionIn",
                "motion", "confidence", "beatAnchorMs", "transform", "speedRamp", "exposureBias", "redBias",
                "greenBias", "blueBias", "flowStrength", "transitionDurationMs", "sourceIndex")
            return MontageGraph.Clip(j.getString("id"), j.l("sourceStartMs"), j.l("sourceEndMs"),
                j.l("outputDurationMs"), MontageGraph.ShotRole.valueOf(j.getString("role")),
                MontageGraph.Transition.valueOf(j.getString("transitionIn")),
                MontageGraph.Motion.valueOf(j.getString("motion")), j.f("confidence"), j.l("beatAnchorMs"),
                transform(j.getJSONObject("transform")), speed(j.getJSONObject("speedRamp")),
                j.f("exposureBias"), j.f("redBias"), j.f("greenBias"), j.f("blueBias"), j.f("flowStrength"),
                j.nl("transitionDurationMs"), j.i("sourceIndex"))
        }

        private fun transform(j: JSONObject): MontageGraph.ClipTransform {
            j.fields("keyframes")
            return MontageGraph.ClipTransform(j.objects("keyframes") { k ->
                k.fields("at", "scale", "translateX", "translateY", "rotationDegrees")
                MontageGraph.ClipTransform.Keyframe(k.f("at"), k.f("scale"), k.f("translateX"),
                    k.f("translateY"), k.f("rotationDegrees"))
            })
        }

        private fun speed(j: JSONObject): MontageGraph.SpeedRamp {
            j.fields("keyframes")
            return MontageGraph.SpeedRamp(j.objects("keyframes") { k ->
                k.fields("at", "speed", "curveToNext")
                MontageGraph.SpeedRamp.Keyframe(k.f("at"), k.f("speed"), curve(k.getJSONObject("curveToNext")))
            })
        }

        private fun curve(j: JSONObject): MontageGraph.SpeedRamp.CubicBezier {
            j.fields("x1", "y1", "x2", "y2")
            return MontageGraph.SpeedRamp.CubicBezier(j.f("x1"), j.f("y1"), j.f("x2"), j.f("y2"))
        }

        private fun track(j: JSONObject): ParameterTrack {
            j.fields("id", "target", "type", "keyframes")
            return ParameterTrack(j.getString("id"), j.getString("target"),
                ParameterTrack.ValueType.valueOf(j.getString("type")), j.objects("keyframes") { k ->
                    k.fields("timeUs", "value", "interpolation", "curveToNext")
                    ParameterTrack.Keyframe(k.l("timeUs"), k.floats("value"),
                        ParameterTrack.Interpolation.valueOf(k.getString("interpolation")),
                        curve(k.getJSONObject("curveToNext")))
                })
        }

        private fun overlay(j: JSONObject): MontageGraph.Overlay {
            j.fields("id", "startMs", "endMs", "kind", "blendMode", "overlayKind", "opacity", "red", "green",
                "blue", "secondarySourceOffsetMs", "secondaryTimelineStartMs")
            return MontageGraph.Overlay(j.getString("id"), j.l("startMs"), j.l("endMs"), j.getString("kind"),
                MontageGraph.BlendMode.valueOf(j.getString("blendMode")),
                MontageGraph.OverlayKind.valueOf(j.getString("overlayKind")), j.f("opacity"), j.f("red"),
                j.f("green"), j.f("blue"), j.l("secondarySourceOffsetMs"), j.nl("secondaryTimelineStartMs"))
        }

        private fun effects(j: JSONObject): GpuEffectGraph {
            j.fields("nodes")
            return GpuEffectGraph(j.objects("nodes") { n ->
                n.fields("id", "kind", "startUs", "endUs", "amount", "secondary")
                GpuEffectGraph.Node(n.getString("id"), GpuEffectGraph.Kind.valueOf(n.getString("kind")),
                    n.l("startUs"), n.l("endUs"), n.f("amount"), n.f("secondary"))
            })
        }

        private fun timeline(j: JSONObject): FrameAttachmentTimeline {
            j.fields("frames", "maskRefinements")
            return FrameAttachmentTimeline(j.objects("frames", ::attachment), j.objects("maskRefinements", ::attachment))
        }

        private fun attachment(j: JSONObject): FrameAttachments {
            j.fields("sourceTimeUs", "mask", "depth", "flow", "subjectQuality", "subjectOcclusion", "maskTemporalIou",
                "faceRegion", "maskBlendTarget", "maskBlendProgress", "maskIsOpacity")
            return FrameAttachments(j.l("sourceTimeUs"), j.optional("mask", ::plane), j.optional("depth", ::plane),
                j.optional("flow", ::flow), j.f("subjectQuality"), j.f("subjectOcclusion"), j.f("maskTemporalIou"),
                j.optional("faceRegion", ::face), j.optional("maskBlendTarget", ::plane),
                j.f("maskBlendProgress"), j.getBoolean("maskIsOpacity"))
        }

        private fun plane(j: JSONObject): FrameAttachments.Plane {
            j.fields("width", "height", "values", "confidence")
            val width = j.i("width"); val height = j.i("height")
            val marker = j.getJSONObject("values").apply { fields("format", "offset_bytes", "count", "sha256") }
            require(marker.getString("format") == "f32le")
            val count = marker.i("count"); val offset = marker.l("offset_bytes")
            require(width > 0 && height > 0 && count.toLong() == width.toLong() * height)
            require(count in 1..1_048_576 && offset >= 0 && offset % 4L == 0L)
            val size = count.toLong() * 4L
            require(offset <= binary.length() && size <= binary.length() - offset)
            val end = offset + size
            require(ranges.none { offset <= it.last && end > it.first }) {
                "Repeated or overlapping plane range"
            }
            require(ranges.sumOf { it.last - it.first + 1L } + size <= binary.length())
            ranges += offset until end
            val values = FloatArray(count)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            binary.seek(offset)
            var index = 0
            while (index < count) {
                val bytes = minOf(buffer.size, (count - index) * 4)
                binary.readFully(buffer, 0, bytes)
                digest.update(buffer, 0, bytes)
                for (cursor in 0 until bytes step 4) {
                    val bits = (buffer[cursor].toInt() and 255) or ((buffer[cursor + 1].toInt() and 255) shl 8) or
                        ((buffer[cursor + 2].toInt() and 255) shl 16) or ((buffer[cursor + 3].toInt() and 255) shl 24)
                    values[index++] = java.lang.Float.intBitsToFloat(bits)
                }
            }
            val hash = hex(digest.digest())
            require(hash == marker.hash("sha256")) { "Plane range digest mismatch" }
            hashes[values] = hash
            return FrameAttachments.Plane(width, height, values, j.f("confidence"))
        }

        private fun flow(j: JSONObject): FrameAttachments.FlowPlane {
            j.fields("width", "height", "vectors", "confidence")
            return FrameAttachments.FlowPlane(j.i("width"), j.i("height"), j.floats("vectors"), j.f("confidence"))
        }

        private fun face(j: JSONObject): FrameAttachments.FaceRegion {
            j.fields("centerX", "centerY", "width", "height", "confidence")
            return FrameAttachments.FaceRegion(j.f("centerX"), j.f("centerY"), j.f("width"), j.f("height"), j.f("confidence"))
        }
    }

    private fun verifyFile(directory: File, identity: JSONObject, expectedName: String): File {
        identity.fields("name", "bytes", "sha256")
        require(identity.getString("name") == expectedName)
        val file = File(directory, expectedName)
        require(file.isFile && file.length() == identity.l("bytes")) { "Input size mismatch: $expectedName" }
        require(sha256(file) == identity.hash("sha256")) { "Input digest mismatch: $expectedName" }
        return file
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return hex(digest.digest())
    }

    internal fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun JSONObject.hash(key: String): String = getString(key).also { require(it.matches(Regex("[0-9a-f]{64}"))) }
    private fun JSONObject.fields(vararg names: String) {
        val actual = keys().asSequence().toSet()
        require(actual == names.toSet()) { "Frozen schema fields differ: missing=${names.toSet() - actual}, extra=${actual - names.toSet()}" }
    }
    private fun JSONObject.l(key: String): Long = get(key).let {
        require(it is Int || it is Long) { "Expected exact integer: $key" }; (it as Number).toLong()
    }
    private fun JSONObject.i(key: String): Int = l(key).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    private fun JSONObject.nl(key: String): Long? = if (isNull(key)) null else l(key)
    private fun JSONObject.f(key: String): Float = getDouble(key).toFloat().also { require(it.isFinite()) }
    private fun JSONObject.floats(key: String): List<Float> = getJSONArray(key).let { a ->
        List(a.length()) { a.getDouble(it).toFloat().also { value -> require(value.isFinite()) } }
    }
    private fun <T> JSONObject.optional(key: String, read: (JSONObject) -> T): T? =
        if (isNull(key)) null else read(getJSONObject(key))
    private fun <T> JSONObject.objects(key: String, read: (JSONObject) -> T): List<T> = getJSONArray(key).let { a ->
        List(a.length()) { read(a.getJSONObject(it)) }
    }
    private fun JSONArray.each(block: (Any) -> Unit) { repeat(length()) { block(get(it)) } }
}
